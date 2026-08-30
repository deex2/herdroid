package dev.herdroid.core.herdr

import dev.herdroid.core.herdr.wire.ClientRequest
import dev.herdroid.core.herdr.wire.ServerMessage
import dev.herdroid.core.model.ActionOutcome
import dev.herdroid.core.model.RemoteOperatingSystem
import dev.herdroid.core.ssh.RemoteProcess
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeClientTest {
    @Test
    fun `canceling startup closes its process once and preserves cancellation`() = runBlocking {
        val startup = BlockingStartup()
        val cancellation = CancellationException("cancel bridge startup")
        val caught = CompletableDeferred<CancellationException>()
        val started = CoroutineScope(SupervisorJob() + Dispatchers.Default).async {
            try {
                BridgeClient.start(startup.process, expectation, Dispatchers.IO)
            } catch (failure: CancellationException) {
                caught.complete(failure)
                throw failure
            }
        }

        assertTrue(startup.entered.await(1, TimeUnit.SECONDS))
        started.cancel(cancellation)
        val closedAutomatically = withTimeoutOrNull(500) { startup.closed.await(); true } ?: false
        if (!closedAutomatically) startup.process.close()
        val failure = caught.await()
        started.join()

        assertTrue("cancellation must close the blocked process", closedAutomatically)
        assertEquals(CancellationException::class, failure::class)
        assertEquals(cancellation.message, failure.message)
        assertEquals(1, startup.processCloses.get())
        assertEquals(1, startup.routeReleases.get())
    }

    @Test
    fun `startup timeout closes its process once and reports transport timeout`() = runBlocking {
        val startup = BlockingStartup()
        val started = CoroutineScope(SupervisorJob() + Dispatchers.Default).async {
            BridgeClient.start(startup.process, expectation, Dispatchers.IO, startupTimeoutMillis = 100)
        }

        assertTrue(startup.entered.await(1, TimeUnit.SECONDS))
        val closedAutomatically = withTimeoutOrNull(500) { startup.closed.await(); true } ?: false
        if (!closedAutomatically) startup.process.close()
        val failure = runCatching { started.await() }.exceptionOrNull()

        assertTrue("timeout must close the blocked process", closedAutomatically)
        assertTrue(failure is BridgeTransportException)
        assertEquals("Bridge startup timed out", failure?.message)
        assertEquals(1, startup.processCloses.get())
        assertEquals(1, startup.routeReleases.get())
    }

    @Test
    fun `startup reads on the supplied IO dispatcher`() = runBlocking {
        val startup = """
            {"type":"hello","protocol":1,"epoch":"e","bridge_version":"0.1.0","target_os":"linux","target_arch":"x86_64","herdr_version":"0.8.1"}
            {"type":"sessions","sessions":[]}
        """.trimIndent().plus("\n").encodeToByteArray()
        val delegate = ByteArrayInputStream(startup)
        val readThread = CompletableDeferred<String>()
        val process = RemoteProcess(
            stdin = ByteArrayOutputStream(),
            stdout = object : InputStream() {
                override fun read(): Int {
                    readThread.complete(Thread.currentThread().name)
                    return delegate.read()
                }
            },
            stderr = ByteArrayInputStream(byteArrayOf()),
            waitFor = { true },
            exitStatus = { 0 },
            closeCommand = {},
            closeSession = {},
            onClose = {},
            ioDispatcher = Dispatchers.IO,
        )
        val dispatcher = Executors.newSingleThreadExecutor { task -> Thread(task, "bridge-client-io") }
            .asCoroutineDispatcher()
        try {
            BridgeClient.start(process, expectation, dispatcher).use {
                assertTrue(withTimeout(1_000) { readThread.await() }.startsWith("bridge-client-io"))
            }
        } finally {
            dispatcher.close()
        }
    }

    @Test
    fun `validates Hello then exposes the bridge first Sessions message`() = runBlocking {
        val stdout = """
            {"type":"hello","protocol":1,"epoch":"e","bridge_version":"0.1.0","target_os":"linux","target_arch":"x86_64","herdr_version":"0.8.1"}
            {"type":"sessions","sessions":[{"name":"work","running":true,"socket_path":"/tmp/work.sock"}]}
            {"type":"snapshot","session":"work","epoch":"e","baseline":true,"snapshot":{"version":"0.8.0","protocol":16,"focused_workspace_id":"w1","focused_tab_id":"t1","focused_pane_id":"p1","workspaces":[{"workspace_id":"w1","number":1,"label":"Space","focused":true,"pane_count":1,"tab_count":1,"active_tab_id":"t1","agent_status":"idle"}],"tabs":[{"tab_id":"t1","workspace_id":"w1","number":1,"label":"Tab","focused":true,"pane_count":1,"agent_status":"idle"}],"panes":[{"pane_id":"p1","terminal_id":"term","workspace_id":"w1","tab_id":"t1","focused":true,"agent_status":"idle","revision":1}],"layouts":[],"agents":[]}}
        """.trimIndent().plus("\n")

        BridgeClient.start(process(stdout.encodeToByteArray()), expectation, Dispatchers.IO).use { client ->
            assertEquals("e", client.initialHello.epoch)
            assertEquals("work", client.initialSessions.single().name)
            delay(100)
            val coordinatorScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
            try {
                val coordinator = SessionCoordinator(client, coordinatorScope)
                assertEquals("e", coordinator.state.value.epoch)
                assertEquals(setOf("work"), coordinator.state.value.sessions.keys)
                withTimeout(1_000) {
                    while (coordinator.state.value.sessions.getValue("work").panes.keys != setOf("p1")) yield()
                }
            } finally {
                coordinatorScope.cancel()
            }
        }
    }

    @Test
    fun `unknown protocol oversized line and malformed UTF8 fail bridge startup once`() = runBlocking {
        val unknown = "{\"type\":\"hello\",\"protocol\":2,\"epoch\":\"e\",\"bridge_version\":\"0.1.0\",\"target_os\":\"linux\",\"target_arch\":\"x86_64\",\"herdr_version\":\"0.8.1\"}\n"
        listOf(
            unknown.encodeToByteArray(),
            "x".repeat(BridgeClient.MAX_LINE_BYTES + 1).encodeToByteArray(),
            malformedUtf8Startup(),
        ).forEach { bytes ->
            val closes = AtomicInteger()

            expectFailure<BridgeProtocolException> {
                BridgeClient.start(process(bytes, closes = closes), expectation, Dispatchers.IO)
            }

            assertEquals(1, closes.get())
        }
    }

    @Test
    fun `startup rejects non canonical target OS spelling`() = runBlocking {
        listOf("LINUX", "Linux").forEach { targetOs ->
            val startup = """
                {"type":"hello","protocol":1,"epoch":"e","bridge_version":"0.1.0","target_os":"$targetOs","target_arch":"x86_64","herdr_version":"0.8.1"}
                {"type":"sessions","sessions":[]}
            """.trimIndent().plus("\n").encodeToByteArray()
            val closes = AtomicInteger()

            expectFailure<BridgeProtocolException> {
                BridgeClient.start(process(startup, closes = closes), expectation, Dispatchers.IO)
            }

            assertEquals(targetOs, 1, closes.get())
        }
    }

    @Test
    fun `all inbound identifier shapes fail before completion or emission`() = runBlocking {
        val invalidLines = listOf(
            "sessions session" to """{"type":"sessions","sessions":[{"name":"bad/name","running":true,"socket_path":"/tmp/bad"}]}""",
            "response session" to """{"type":"response","id":"r","session":"bad/name","result":{}}""",
            "response nested pane" to """{"type":"response","id":"r","session":"work","result":{"nested":{"focused_pane_id":"bad/id"}}}""",
            "response nested pane array" to """{"type":"response","id":"r","session":"work","result":{"nested":{"selected_pane_ids":["bad/id"]}}}""",
            "error session" to """{"type":"error","id":"r","session":"bad/name","code":"bad","message":"text"}""",
            "snapshot session" to """{"type":"snapshot","session":"bad/name","epoch":"e","baseline":false,"snapshot":{}}""",
            "snapshot nested pane" to """{"type":"snapshot","session":"work","epoch":"e","baseline":false,"snapshot":{"panes":[{"pane_id":"bad/id"}]}}""",
            "agent status session" to """{"type":"agent_status","session":"bad/name","epoch":"e","pane_id":"p1","status":"idle"}""",
            "agent status pane" to """{"type":"agent_status","session":"work","epoch":"e","pane_id":"bad/id","status":"idle"}""",
            "degraded session" to """{"type":"degraded","session":"bad/name","epoch":"e","code":"bad","message":"text","uncovered_pane_ids":[]}""",
            "degraded pane array" to """{"type":"degraded","session":"work","epoch":"e","code":"bad","message":"text","uncovered_pane_ids":["bad/id"]}""",
        )
        val invalid = invalidLines.map { (name, line) -> name to line.encodeToByteArray() } +
            ("malformed UTF-8" to malformedUtf8Message())

        invalid.forEach { (case, bytes) ->
            val requestOutput = RequestOutput()
            val harness = ClientHarness(requestOutput)
            val client = harness.start()
            val emitted = CompletableDeferred<ServerMessage>()
            val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                client.messages.collect { emitted.complete(it) }
            }
            val pending = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { client.request(request("r")) }.exceptionOrNull()
            }
            requestOutput.flushes.receive()
            try {
                harness.send(bytes)

                assertTrue("$case did not close", withTimeout(1_000) { harness.closed.await(); true })
                assertTrue("$case completed normally", pending.await() is BridgeProtocolException)
                assertNull("$case was emitted", withTimeoutOrNull(100) { emitted.await() })
                assertEquals("$case closed more than once", 1, harness.closeCount.get())
            } finally {
                collector.cancelAndJoin()
                client.close()
                harness.close()
            }
        }
    }

    @Test
    fun `arbitrary text in response is not treated as a pane id`() = runBlocking {
        val output = RequestOutput()
        val harness = ClientHarness(output)
        val client = harness.start()
        val response = async(start = CoroutineStart.UNDISPATCHED) { client.request(request("ok")) }
        output.flushes.receive()

        harness.send("""{"type":"response","id":"ok","session":"work","result":{"message":"bad/value","label":"also/not-an-id"}}""")

        assertEquals("work", withTimeout(1_000) { response.await() }.session)
        assertEquals(0, harness.closeCount.get())
        client.close()
        assertEquals(1, harness.closeCount.get())
        harness.close()
    }

    @Test
    fun `write and flush failures fail two pending requests with one normalized failure`() = runBlocking {
        listOf(FailurePoint.WRITE, FailurePoint.FLUSH).forEach { point ->
            val output = BarrierFailingOutput(point)
            val harness = ClientHarness(output)
            val client = harness.start()
            val first = async(Dispatchers.Default) { runCatching { client.request(request("one")) }.exceptionOrNull() }
            assertTrue(output.entered.await(1, TimeUnit.SECONDS))
            val second = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { client.request(request("two")) }.exceptionOrNull()
            }
            output.release.countDown()

            val firstFailure = withTimeout(1_000) { first.await() }
            val secondFailure = withTimeout(1_000) { second.await() }
            assertTrue(firstFailure is BridgeTransportException)
            assertSame(firstFailure, secondFailure)
            assertEquals("Bridge write failed", firstFailure?.message)
            assertEquals(1, harness.closeCount.get())
            val afterClose = runCatching { client.request(request("one")) }.exceptionOrNull()
            assertSame(firstFailure, afterClose)
            client.close()
            assertEquals(1, harness.closeCount.get())
            harness.close()
        }
    }

    @Test
    fun `duplicate cancellation and EOF keep request races bounded`() = runBlocking {
        val output = RequestOutput()
        val harness = ClientHarness(output)
        val client = harness.start()
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { client.request(request("duplicate")) }.exceptionOrNull()
        }
        output.flushes.receive()
        expectFailure<IllegalStateException> { client.request(request("duplicate")) }

        val cancelled = launch(start = CoroutineStart.UNDISPATCHED) { client.request(request("cancelled")) }
        output.flushes.receive()
        cancelled.cancelAndJoin()
        harness.send("""{"type":"response","id":"cancelled","session":"work","result":{}}""")

        val live = async(start = CoroutineStart.UNDISPATCHED) { client.request(request("live")) }
        output.flushes.receive()
        harness.send("""{"type":"response","id":"live","session":"work","result":{}}""")
        assertEquals("live", withTimeout(1_000) { live.await() }.id)

        harness.closeOutput()
        assertTrue(withTimeout(1_000) { first.await() } is BridgeProtocolException)
        assertTrue(withTimeout(1_000) { client.failure.filterNotNull().first() } is BridgeTransportException)
        assertEquals(1, harness.closeCount.get())
        client.close()
        harness.close()
    }

    @Test
    fun `remote errors preserve stock code detail and session without closing bridge`() = runBlocking {
        val output = RequestOutput()
        val harness = ClientHarness(output)
        val client = harness.start()
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { client.request(request("confirm")) }.exceptionOrNull()
        }
        output.flushes.receive()

        harness.send("""{"type":"error","id":"confirm","session":"work","code":"confirmation_required","message":"Confirm on the host"}""")

        val failure = withTimeout(1_000) { pending.await() } as BridgeRemoteException
        assertEquals("confirmation_required", failure.code)
        assertEquals("Confirm on the host", failure.detail)
        assertEquals("work", failure.session)
        assertEquals(0, harness.closeCount.get())
        client.close()
        harness.close()
    }

    @Test
    fun `authoritative messages backpressure a starved subscriber without losing order`() = runBlocking {
        val harness = ClientHarness(ByteArrayOutputStream())
        val client = harness.start()
        val blocked = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val received = mutableListOf<String>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            client.messages.collect { message ->
                when (message) {
                    is ServerMessage.Snapshot -> {
                        received += "baseline"
                        blocked.complete(Unit)
                        release.await()
                    }
                    is ServerMessage.AgentStatus -> received += message.status
                    else -> Unit
                }
            }
        }

        try {
            harness.send("""{"type":"snapshot","session":"work","epoch":"e","baseline":true,"snapshot":{}}""")
            withTimeout(1_000) { blocked.await() }
            val expectedStatuses = List(80) { "status-$it" }
            expectedStatuses.forEach { status ->
                harness.send("""{"type":"agent_status","session":"work","epoch":"e","pane_id":"p1","status":"$status"}""")
            }
            harness.closeOutput()

            assertNull("reader bypassed backpressure", withTimeoutOrNull(500) { harness.closed.await() })
            release.complete(Unit)
            withTimeout(2_000) {
                while (received.size != expectedStatuses.size + 1) yield()
                harness.closed.await()
            }

            assertEquals(listOf("baseline") + expectedStatuses, received)
            assertEquals(1, harness.closeCount.get())
        } finally {
            release.complete(Unit)
            collector.cancelAndJoin()
            client.close()
            harness.close()
        }
    }

    @Test
    fun `coordinator construction registers before an immediate action request`() = runBlocking {
        val output = RequestOutput()
        val harness = ClientHarness(output)
        val client = harness.start(collectMessages = false)
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val coordinatorScope = CoroutineScope(dispatcher + SupervisorJob())
        val occupied = CountDownLatch(1)
        val release = CountDownLatch(1)
        coordinatorScope.launch {
            occupied.countDown()
            release.await(2, TimeUnit.SECONDS)
        }
        assertTrue(occupied.await(1, TimeUnit.SECONDS))

        try {
            val coordinator = SessionCoordinator(client, coordinatorScope)
            val action = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { HerdrActions(client).focusPane("work", "p1") }
            }
            withTimeout(1_000) { output.flushes.receive() }
            val request = Json.decodeFromString(ClientRequest.serializer(), output.toString(Charsets.UTF_8).trim())
            harness.send("""{"type":"snapshot","session":"work","epoch":"e","baseline":true,"snapshot":{"workspaces":[],"tabs":[],"panes":[{"pane_id":"p1","terminal_id":"term","workspace_id":"w1","tab_id":"t1","focused":true,"agent_status":"idle"}]}}""")
            harness.send("""{"type":"response","id":"${request.id}","session":"work","result":{}}""")
            release.countDown()

            assertEquals(ActionOutcome.Succeeded, withTimeout(1_000) { action.await() }.getOrThrow())
            withTimeout(1_000) {
                while (coordinator.state.value.sessions.getValue("work").panes.keys != setOf("p1")) yield()
            }
        } finally {
            release.countDown()
            coordinatorScope.cancel()
            dispatcher.close()
            client.close()
            harness.close()
        }
    }

    @Test
    fun `requests before message collection are rejected instead of dropping state`() = runBlocking {
        val stdout = """
            {"type":"hello","protocol":1,"epoch":"e","bridge_version":"0.1.0","target_os":"linux","target_arch":"x86_64","herdr_version":"0.8.1"}
            {"type":"sessions","sessions":[{"name":"work","running":true,"socket_path":"/tmp/work.sock"}]}
        """.trimIndent().plus("\n")
        val client = BridgeClient.start(process(stdout.encodeToByteArray()), expectation, Dispatchers.IO)

        val failure = expectFailure<IllegalStateException> { client.request(request("early")) }

        assertEquals("Collect bridge messages before requests", failure.message)
        client.close()
    }

    @Test
    fun `outbound requests include the bridge envelope defaults`() = runBlocking {
        val output = RequestOutput()
        val harness = ClientHarness(output)
        val client = harness.start()
        val pending = async(start = CoroutineStart.UNDISPATCHED) { client.request(request("wire")) }
        output.flushes.receive()

        val wire = Json.parseToJsonElement(output.toString(Charsets.UTF_8).trim()).jsonObject
        assertEquals("request", wire.getValue("type").jsonPrimitive.content)
        assertEquals(1, wire.getValue("protocol").jsonPrimitive.int)
        harness.send("""{"type":"response","id":"wire","session":"work","result":{}}""")
        pending.await()

        client.close()
        harness.close()
    }

    @Test
    fun `oversized outbound request is rejected without poisoning the client`() = runBlocking {
        val output = RequestOutput()
        val harness = ClientHarness(output)
        val client = harness.start()
        try {
            val oversized = ClientRequest(
                "large",
                "work",
                "pane.rename",
                buildJsonObject { put("label", JsonPrimitive("x".repeat(BridgeClient.MAX_LINE_BYTES))) },
            )
            val failure = expectFailure<BridgeProtocolException> { withTimeout(1_000) { client.request(oversized) } }
            assertEquals("Bridge request exceeds limit", failure.message)
            assertEquals(0, output.size())

            val response = async { client.request(request("small")) }
            withTimeout(1_000) { output.flushes.receive() }
            harness.send("""{"type":"response","id":"small","session":"work","result":{}}""")
            assertEquals("small", withTimeout(1_000) { response.await() }.id)
        } finally {
            client.close()
            harness.close()
        }
    }

    @Test
    fun `stream IO failure is exposed as typed transport failure`() = runBlocking {
        val startup = """
            {"type":"hello","protocol":1,"epoch":"e","bridge_version":"0.1.0","target_os":"linux","target_arch":"x86_64","herdr_version":"0.8.1"}
            {"type":"sessions","sessions":[{"name":"work","running":true,"socket_path":"/tmp/work.sock"}]}
        """.trimIndent().plus("\n").encodeToByteArray()
        val delegate = ByteArrayInputStream(startup)
        val stdout = object : InputStream() {
            override fun read(): Int = if (delegate.available() > 0) delegate.read() else throw IOException("socket reset")
        }
        val closes = AtomicInteger()
        val closeStarted = CountDownLatch(1)
        val allowClose = CountDownLatch(1)
        val process = RemoteProcess(
            stdin = ByteArrayOutputStream(),
            stdout = stdout,
            stderr = ByteArrayInputStream(byteArrayOf()),
            waitFor = { true },
            exitStatus = { 0 },
            closeCommand = {
                closes.incrementAndGet()
                closeStarted.countDown()
                check(allowClose.await(1, TimeUnit.SECONDS))
            },
            closeSession = {},
            onClose = {},
            ioDispatcher = Dispatchers.IO,
        )
        val client = BridgeClient.start(process, expectation, Dispatchers.IO)
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { client.messages.collect() }
        val observed = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            client.failure.filterNotNull().first()
        }
        try {
            assertTrue(closeStarted.await(1, TimeUnit.SECONDS))
            assertFalse("failure must not publish before process cleanup", observed.isCompleted)
            allowClose.countDown()
            val failure = withTimeout(1_000) { observed.await() }

            assertTrue(failure is BridgeTransportException)
            assertTrue(failure.cause is IOException)
            assertEquals(1, closes.get())
        } finally {
            allowClose.countDown()
            collector.cancelAndJoin()
            client.close()
        }
    }

    private fun request(id: String) = ClientRequest(id, "work", "session.snapshot", buildJsonObject {})

    private fun malformedUtf8Startup(): ByteArray {
        val prefix = "{\"type\":\"hello\",\"protocol\":1,\"epoch\":\"".encodeToByteArray()
        val suffix = "\",\"bridge_version\":\"0.1.0\",\"target_os\":\"linux\",\"target_arch\":\"x86_64\",\"herdr_version\":\"0.8.1\"}\n{\"type\":\"sessions\",\"sessions\":[]}\n".encodeToByteArray()
        return prefix + byteArrayOf(0xc3.toByte(), 0x28) + suffix
    }

    private fun malformedUtf8Message(): ByteArray =
        "{\"type\":\"heartbeat\",\"epoch\":\"".encodeToByteArray() +
            byteArrayOf(0xc3.toByte(), 0x28) +
            "\"}".encodeToByteArray()

    private fun process(
        stdout: ByteArray,
        stdin: OutputStream = ByteArrayOutputStream(),
        closes: AtomicInteger = AtomicInteger(),
    ) = RemoteProcess(
        stdin = stdin,
        stdout = ByteArrayInputStream(stdout),
        stderr = ByteArrayInputStream(byteArrayOf()),
        waitFor = { true },
        exitStatus = { 0 },
        closeCommand = { closes.incrementAndGet() },
        closeSession = {},
        onClose = {},
        ioDispatcher = Dispatchers.IO,
    )

    private class ClientHarness(stdin: OutputStream) {
        private val server = PipedOutputStream()
        private val stdout = PipedInputStream(server, 64 * 1024)
        private val readerScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val closeCount = AtomicInteger()
        val closed = CompletableDeferred<Unit>()
        private val process = RemoteProcess(
            stdin = stdin,
            stdout = stdout,
            stderr = ByteArrayInputStream(byteArrayOf()),
            waitFor = { true },
            exitStatus = { 0 },
            closeCommand = {
                closeCount.incrementAndGet()
                closed.complete(Unit)
            },
            closeSession = {},
            onClose = {},
            ioDispatcher = Dispatchers.IO,
        )

        suspend fun start(collectMessages: Boolean = true): BridgeClient {
            send("""{"type":"hello","protocol":1,"epoch":"e","bridge_version":"0.1.0","target_os":"linux","target_arch":"x86_64","herdr_version":"0.8.1"}""")
            send("""{"type":"sessions","sessions":[{"name":"work","running":true,"socket_path":"/tmp/work.sock"}]}""")
            return BridgeClient.start(process, expectation, Dispatchers.IO).also { client ->
                if (collectMessages) readerScope.launch(start = CoroutineStart.UNDISPATCHED) { client.messages.collect() }
            }
        }

        fun send(line: String) {
            send(line.encodeToByteArray())
        }

        fun send(bytes: ByteArray) {
            server.write(bytes)
            server.write('\n'.code)
            server.flush()
        }

        fun closeOutput() = server.close()

        fun close() {
            readerScope.cancel()
            runCatching { server.close() }
        }
    }

    private class RequestOutput : ByteArrayOutputStream() {
        val flushes = Channel<Unit>(Channel.UNLIMITED)

        override fun flush() {
            super.flush()
            flushes.trySend(Unit)
        }
    }

    private class BlockingStartup {
        val entered = CountDownLatch(1)
        val closed = CompletableDeferred<Unit>()
        val processCloses = AtomicInteger()
        val routeReleases = AtomicInteger()
        private val release = CountDownLatch(1)
        val process = RemoteProcess(
            stdin = ByteArrayOutputStream(),
            stdout = object : InputStream() {
                override fun read(): Int {
                    entered.countDown()
                    release.await()
                    return -1
                }

                override fun close() {
                    release.countDown()
                    closed.complete(Unit)
                }
            },
            stderr = ByteArrayInputStream(byteArrayOf()),
            waitFor = { true },
            exitStatus = { 0 },
            closeCommand = { processCloses.incrementAndGet() },
            closeSession = {},
            onClose = { routeReleases.incrementAndGet() },
            ioDispatcher = Dispatchers.IO,
        )
    }

    private enum class FailurePoint { WRITE, FLUSH }

    private class BarrierFailingOutput(private val point: FailurePoint) : OutputStream() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)

        override fun write(value: Int) {
            if (point == FailurePoint.WRITE) fail()
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            if (point == FailurePoint.WRITE) fail()
        }

        override fun flush() {
            if (point == FailurePoint.FLUSH) fail()
        }

        private fun fail(): Nothing {
            entered.countDown()
            check(release.await(1, TimeUnit.SECONDS)) { "test did not release writer" }
            throw IOException("forced $point failure")
        }
    }

    private companion object {
        val expectation = BridgeExpectation(RemoteOperatingSystem.LINUX, "x86_64", "0.1.0", "0.8.0")
    }
}
