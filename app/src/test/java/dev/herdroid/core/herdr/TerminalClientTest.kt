package dev.herdroid.core.herdr

import dev.herdroid.core.model.RemoteOperatingSystem
import dev.herdroid.core.model.TerminalScrollDirection
import dev.herdroid.core.model.TerminalScrollSource
import dev.herdroid.core.model.TerminalState
import dev.herdroid.core.ssh.RemoteProcess
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalClientTest {
    @Test
    fun `full first frame and sequential frames expose decoded ANSI bytes`() = runBlocking {
        val harness = Harness()
        val client = harness.client()
        val first = async { client.frames.first() }

        harness.send("""{"type":"terminal.frame","seq":41,"encoding":"ansi","width":80,"height":24,"full":true,"bytes":"G1sySg=="}""")
        val initial = withTimeout(1_000) { first.await() }
        assertArrayEquals(byteArrayOf(0x1b, 0x5b, 0x32, 0x4a), initial.bytes)
        assertEquals(TerminalState.Interactive(80, 24, 42), client.state.value)

        val second = async { client.frames.first() }
        harness.send("""{"type":"terminal.frame","seq":42,"encoding":"ansi","width":100,"height":30,"full":false,"bytes":"AP+A"}""")
        val delta = withTimeout(1_000) { second.await() }
        assertArrayEquals(byteArrayOf(0, 0xff.toByte(), 0x80.toByte()), delta.bytes)
        assertEquals(TerminalState.Interactive(100, 30, 43), client.state.value)
        client.release()
        harness.close()
    }

    @Test
    fun `writes stock text binary resize scroll and release records`() = runBlocking {
        val harness = Harness()
        val client = harness.client()
        harness.send("""{"type":"terminal.frame","seq":0,"encoding":"ansi","width":80,"height":24,"full":true,"bytes":""}""")
        awaitInteractive(client)

        client.sendText("snowman ☃\n")
        client.sendBytes(byteArrayOf(0, 0xff.toByte(), 0x1b))
        client.resize(0, 5_000, 8, 16)
        client.scroll(TerminalScrollDirection.UP, 3, TerminalScrollSource.PAGE_KEY, 7, 9, 6)
        client.release()

        assertEquals(
            listOf(
                """{"type":"terminal.input","text":"snowman ☃\n"}""",
                """{"type":"terminal.input","bytes":"AP8b"}""",
                """{"type":"terminal.resize","cols":1,"rows":1000,"cell_width_px":8,"cell_height_px":16}""",
                """{"type":"terminal.scroll","direction":"up","lines":3,"source":"page_key","column":7,"row":9,"modifiers":6}""",
                """{"type":"terminal.release"}""",
            ),
            harness.writtenLines(),
        )
        assertEquals(TerminalState.Detached, client.state.value)
        harness.close()
    }

    @Test
    fun `release is best effort after the remote stream closes`() {
        val harness = Harness(object : OutputStream() {
            override fun write(value: Int) = throw IOException("stream closed")
        })
        val client = harness.client()

        assertEquals(null, runCatching { client.release() }.exceptionOrNull())
        assertEquals(TerminalState.Closed(TerminalClient.ABRUPT_DIAGNOSTIC), client.state.value)
        assertEquals(1, harness.closeCount.get())
        harness.close()
    }

    @Test
    fun `command always selects validated session and takeover is explicit`() {
        assertEquals(
            "'/opt/herdr' '--session' 'default' 'terminal' 'session' 'control' 'pane:1' '--cols' '1' '--rows' '1000'",
            TerminalClient.command(RemoteOperatingSystem.LINUX, "/opt/herdr", "default", "pane:1", 0, 5_000, false),
        )
        assertTrue(
            TerminalClient.command(RemoteOperatingSystem.LINUX, "/opt/herdr", "work", "p1", 80, 24, true)
                .endsWith("'--takeover'"),
        )
        assertThrows(IllegalArgumentException::class.java) {
            TerminalClient.command(RemoteOperatingSystem.LINUX, "/opt/herdr", "bad/name", "p1", 80, 24, false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TerminalClient.command(RemoteOperatingSystem.LINUX, "/opt/herdr", "work", "bad pane", 80, 24, false)
        }
    }

    @Test
    fun `EOF and closed before first frame are attach failures with opaque diagnostics`() = runBlocking {
        val eof = Harness()
        val eofClient = eof.client()
        eof.endOutput()
        assertTrue(awaitTerminal(eofClient) is TerminalState.AttachFailed)

        val closed = Harness()
        val closedClient = closed.client()
        closed.send("""{"type":"terminal.closed","reason":"arbitrary stock text"}""")
        assertEquals(TerminalState.AttachFailed("arbitrary stock text"), awaitTerminal(closedClient))
        eof.close()
        closed.close()
    }

    @Test
    fun `closed after first frame is lost controller state`() = runBlocking {
        val harness = Harness()
        val client = harness.client()
        harness.send("""{"type":"terminal.frame","seq":5,"encoding":"ansi","width":80,"height":24,"full":true,"bytes":""}""")
        awaitInteractive(client)
        harness.send("""{"type":"terminal.closed","reason":"taken over"}""")

        assertEquals(TerminalState.Closed("taken over"), awaitTerminal(client))
        harness.close()
    }

    @Test
    fun `non-full first frame malformed record bad base64 and sequence gap close the process`() = runBlocking {
        val cases = listOf(
            listOf("""{"type":"terminal.frame","seq":0,"encoding":"ansi","width":80,"height":24,"full":false,"bytes":""}""") to false,
            listOf("not-json") to false,
            listOf("""{"type":"terminal.frame","seq":0,"encoding":"ansi","width":80,"height":24,"full":true,"bytes":"***"}""") to false,
            listOf(
                """{"type":"terminal.frame","seq":8,"encoding":"ansi","width":80,"height":24,"full":true,"bytes":""}""",
                """{"type":"terminal.frame","seq":10,"encoding":"ansi","width":80,"height":24,"full":false,"bytes":""}""",
            ) to true,
        )
        cases.forEach { (lines, afterFrame) ->
            val harness = Harness()
            val client = harness.client()
            lines.forEach(harness::send)

            val state = awaitTerminal(client)
            assertEquals(afterFrame, state is TerminalState.Closed)
            assertTrue(state is TerminalState.AttachFailed || state is TerminalState.Closed)
            assertEquals(1, harness.closeCount.get())
            harness.close()
        }
    }

    @Test
    fun `stderr is drained concurrently so a blocked command can deliver its frame and exit`() = runBlocking {
        val stdoutWriter = PipedOutputStream()
        val stdout = PipedInputStream(stdoutWriter, 256)
        val stderrWriter = PipedOutputStream()
        val stderr = PipedInputStream(stderrWriter, 128)
        val drainStarted = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val closes = AtomicInteger()
        val process = RemoteProcess(
            stdin = ByteArrayOutputStream(),
            stdout = stdout,
            stderr = object : java.io.FilterInputStream(stderr) {
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    drainStarted.countDown()
                    return super.read(bytes, offset, length)
                }
            },
            waitFor = { exited.await(it, TimeUnit.MILLISECONDS) },
            exitStatus = { 0 },
            closeCommand = { closes.incrementAndGet() },
            closeSession = {},
            onClose = {},
            ioDispatcher = Dispatchers.IO,
        )
        val client = TerminalClient.start(process, Dispatchers.IO, Dispatchers.Default)
        assertTrue("stderr drain did not start", drainStarted.await(1, TimeUnit.SECONDS))
        val producer = Thread {
            try {
                stderrWriter.write(ByteArray(8 * 1024) { 0x65 })
                stderrWriter.close()
                stdoutWriter.write(
                    ("""{"type":"terminal.frame","seq":0,"encoding":"ansi","width":80,"height":24,"full":true,"bytes":"b2s="}""" + "\n")
                        .encodeToByteArray(),
                )
                stdoutWriter.close()
            } finally {
                exited.countDown()
            }
        }.apply { start() }

        try {
            assertArrayEquals("ok".encodeToByteArray(), withTimeout(1_000) { client.frames.first() }.bytes)
            assertTrue(awaitTerminal(client) is TerminalState.Closed)
            producer.join(1_000)
            assertTrue("stderr producer remained blocked", !producer.isAlive)
            assertEquals(1, closes.get())
        } finally {
            client.close()
            runCatching { stderrWriter.close() }
            runCatching { stdoutWriter.close() }
            producer.join(1_000)
        }
    }

    @Test
    fun `exit waits for blocked stdout owner to deliver final frame before close`() = runBlocking {
        val stdout = BlockedFinalFrameInput(
            ("""{"type":"terminal.frame","seq":0,"encoding":"ansi","width":80,"height":24,"full":true,"bytes":"ZmluYWw="}""" + "\n")
                .encodeToByteArray(),
        )
        val exitReported = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val closes = AtomicInteger()
        val client = TerminalClient.start(
            RemoteProcess(
                stdin = ByteArrayOutputStream(),
                stdout = stdout,
                stderr = ByteArrayInputStream(byteArrayOf()),
                waitFor = {
                    check(stdout.readStarted.await(1, TimeUnit.SECONDS)) { "stdout owner did not start" }
                    exitReported.countDown()
                    true
                },
                exitStatus = { 0 },
                closeCommand = {
                    closes.incrementAndGet()
                    closed.countDown()
                },
                closeSession = {},
                onClose = {},
                ioDispatcher = Dispatchers.IO,
            ),
            Dispatchers.IO,
            Dispatchers.Default,
        )

        try {
            assertTrue(exitReported.await(1, TimeUnit.SECONDS))
            val closedBeforeRead = closed.await(1, TimeUnit.SECONDS)
            stdout.allowRead.countDown()

            assertArrayEquals("final".encodeToByteArray(), withTimeout(1_000) { client.frames.first() }.bytes)
            assertTrue(awaitTerminal(client) is TerminalState.Closed)
            assertTrue("process closed before stdout owner finished", !closedBeforeRead)
            assertEquals(1, closes.get())
        } finally {
            stdout.allowRead.countDown()
            client.close()
        }
    }

    @Test
    fun `write and flush failures atomically close while release and close race`() = runBlocking {
        FailurePoint.entries.forEach { point ->
            val output = RacingFailureOutput(point)
            val harness = Harness(output)
            val client = harness.client()
            harness.send("""{"type":"terminal.frame","seq":0,"encoding":"ansi","width":80,"height":24,"full":true,"bytes":""}""")
            awaitInteractive(client)
            val send = async(Dispatchers.Default) { runCatching { client.sendText("x") }.exceptionOrNull() }
            assertTrue(output.firstEntered.await(1, TimeUnit.SECONDS))
            val release = async(Dispatchers.Default) { runCatching { client.release() }.exceptionOrNull() }
            val close = launch(Dispatchers.Default) { runCatching { client.close() } }

            try {
                output.releaseFirst.countDown()
                assertTrue(send.await() is IOException)
                assertEquals(TerminalState.Closed(TerminalClient.ABRUPT_DIAGNOSTIC), awaitTerminal(client))
                assertEquals("release attempted another ${point.name.lowercase()}", 1L, output.secondEntered.count)
                assertEquals(1, harness.closeCount.get())
            } finally {
                output.releaseSecond.countDown()
                release.await()
                close.join()
                harness.close()
            }
            assertEquals(1, harness.closeCount.get())
        }
    }

    private suspend fun awaitInteractive(client: TerminalClient) = withTimeout(1_000) {
        client.state.first { it is TerminalState.Interactive }
    }

    private suspend fun awaitTerminal(client: TerminalClient) = withTimeout(1_000) {
        client.state.first { it is TerminalState.AttachFailed || it is TerminalState.Closed }
    }

    private class Harness(
        private val stdin: OutputStream = ByteArrayOutputStream(),
    ) {
        private val server = PipedOutputStream()
        private val stdout = PipedInputStream(server, 64 * 1024)
        val closeCount = AtomicInteger()

        fun client() = TerminalClient.start(
            RemoteProcess(
                stdin = stdin,
                stdout = stdout,
                stderr = ByteArrayInputStream(byteArrayOf()),
                waitFor = { false },
                exitStatus = { null },
                closeCommand = { closeCount.incrementAndGet() },
                closeSession = {},
                onClose = {},
                ioDispatcher = Dispatchers.IO,
            ),
            Dispatchers.IO,
            Dispatchers.Default,
        )

        fun send(line: String) {
            server.write(line.encodeToByteArray())
            server.write('\n'.code)
            server.flush()
        }

        fun endOutput() = server.close()

        fun writtenLines() = (stdin as ByteArrayOutputStream).toString(Charsets.UTF_8)
            .lineSequence().filter(String::isNotEmpty).toList()

        fun close() { runCatching { server.close() } }
    }

    private enum class FailurePoint { WRITE, FLUSH }

    private class BlockedFinalFrameInput(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        val readStarted = CountDownLatch(1)
        val allowRead = CountDownLatch(1)
        private val closed = AtomicBoolean()

        override fun read(buffer: ByteArray, start: Int, length: Int): Int {
            readStarted.countDown()
            check(allowRead.await(2, TimeUnit.SECONDS)) { "test did not release stdout" }
            if (closed.get()) throw IOException("stdout closed before owner finished")
            return super.read(buffer, start, length)
        }

        override fun close() {
            closed.set(true)
        }
    }

    private class RacingFailureOutput(private val point: FailurePoint) : OutputStream() {
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        val releaseSecond = CountDownLatch(1)
        private val attempts = AtomicInteger()

        override fun write(value: Int) {
            if (point == FailurePoint.WRITE) failOrBlock()
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            if (point == FailurePoint.WRITE) failOrBlock()
        }

        override fun flush() {
            if (point == FailurePoint.FLUSH) failOrBlock()
        }

        private fun failOrBlock() {
            if (attempts.incrementAndGet() == 1) {
                firstEntered.countDown()
                check(releaseFirst.await(1, TimeUnit.SECONDS)) { "test did not release first failure" }
                throw IOException("forced ${point.name.lowercase()} failure")
            }
            secondEntered.countDown()
            check(releaseSecond.await(2, TimeUnit.SECONDS)) { "test did not release second operation" }
        }
    }
}
