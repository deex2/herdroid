package dev.herdroid.session.impl

import dev.herdroid.session.api.*

import dev.herdroid.core.data.ConnectionAuthenticationInput as SshAuthentication
import dev.herdroid.core.data.ConnectionEndpointInput
import dev.herdroid.core.data.ConnectionRouteInput
import dev.herdroid.core.model.KnownHostRecord
import dev.herdroid.core.data.LocalDataUnavailableException
import dev.herdroid.core.herdr.BridgeTransportException
import dev.herdroid.core.model.SessionState
import dev.herdroid.core.herdr.TerminalClient
import dev.herdroid.core.model.BridgeApproval
import dev.herdroid.core.ssh.HostKeyApprovalRequired
import dev.herdroid.core.model.HostKeyCandidate
import dev.herdroid.core.ssh.HostKeyChangedException
import dev.herdroid.core.model.HostKeyDecision
import dev.herdroid.core.ssh.HardwareKeyUnavailableException
import dev.herdroid.core.model.Hop
import dev.herdroid.core.model.RemoteOperatingSystem
import dev.herdroid.core.ssh.RemoteProcess
import dev.herdroid.core.ssh.SshAuthenticationFailedException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.concurrent.thread

class ConnectionOwnerTest {
    @Test
    fun `cache success skips discovery and cache failure finishes before fallback`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val events = mutableListOf<String>()
        var attempt = 0
        fun active() = ConnectionActiveBridge(
            collectUntilFailure = { publish ->
                publish(mapOf("work" to SessionState("epoch")))
                awaitCancellation()
            },
            close = {},
        )
        val owner = ConnectionOwner(
            scope,
            ConnectionStateMachine(),
            testDependencies(
                storeProvider = { storeSession() },
                connectRoute = { saved, _, _ ->
                    if (++attempt == 1) {
                        ConnectionRouteAttempt(
                            prepareBridge = { error("discovery must not run") },
                            close = {},
                            startCachedBridge = { events += "cached"; active() },
                        )
                    } else {
                        ConnectionRouteAttempt(
                            prepareBridge = {
                                events += "cold"
                                ConnectionBridgePlan(
                                    preview(saved.id),
                                    verifyExisting = { true },
                                    install = {},
                                    start = ::active,
                                )
                            },
                            close = {},
                            startCachedBridge = {
                                events += "failed"
                                events += "cleanup"
                                null
                            },
                        )
                    }
                },
                waitForRetry = ::unexpectedRetry,
            ),
        )
        try {
            owner.connect(1).join()
            awaitState(owner) { it is ConnectionState.Connected }
            owner.disconnect().join()
            assertEquals(listOf("cached"), events)

            owner.connect(1).join()
            awaitState(owner) { it is ConnectionState.Connected }
            assertEquals(listOf("cached", "failed", "cleanup", "cold"), events)
        } finally {
            owner.disconnect().join()
            scope.cancel()
        }
    }

    @Test
    fun `service destroy returns while blocked attach drains before bridge and route`() = runBlocking {
        val ownerJob = SupervisorJob()
        val releaseJob = SupervisorJob()
        val ownerScope = CoroutineScope(ownerJob + Dispatchers.Default)
        val releaseScope = CoroutineScope(releaseJob + Dispatchers.Default)
        val events = Collections.synchronizedList(mutableListOf<String>())
        val active = CompletableDeferred<Unit>()
        val attachEntered = CountDownLatch(1)
        val allowAttach = CountDownLatch(1)
        val closeEntered = CountDownLatch(1)
        val allowClose = CountDownLatch(1)
        val terminalOutput = PipedOutputStream()
        val client = TerminalClient.start(
            RemoteProcess(
                stdin = ByteArrayOutputStream(),
                stdout = PipedInputStream(terminalOutput),
                stderr = ByteArrayInputStream(byteArrayOf()),
                waitFor = { true },
                exitStatus = { null },
                closeCommand = {},
                closeSession = {},
                onClose = {
                    closeEntered.countDown()
                    check(allowClose.await(2, TimeUnit.SECONDS))
                    events += "lease"
                },
                ioDispatcher = Dispatchers.IO,
            ),
            Dispatchers.IO,
            Dispatchers.Default,
        )
        val owner = ConnectionOwner(
            ownerScope,
            ConnectionStateMachine(),
            testDependencies(
                storeProvider = { storeSession() },
                connectRoute = { saved, _, _ ->
                    ConnectionRouteAttempt(
                        prepareBridge = {
                            ConnectionBridgePlan(
                                preview(saved.id),
                                verifyExisting = { true },
                                install = {},
                                start = {
                                    ConnectionActiveBridge(
                                        collectUntilFailure = { publish ->
                                            publish(mapOf("work" to SessionState("epoch")))
                                            active.complete(Unit)
                                            awaitCancellation()
                                        },
                                        close = { events += "bridge" },
                                        attachTerminal = { _, _, _, _, _ ->
                                            attachEntered.countDown()
                                            check(allowAttach.await(2, TimeUnit.SECONDS))
                                            client
                                        },
                                    )
                                },
                            )
                        },
                        close = { events += "route" },
                    )
                },
                waitForRetry = ::unexpectedRetry,
            ),
            releaseScope = releaseScope,
        )
        try {
            owner.connect(1).join()
            withTimeout(1_000) { active.await() }
            val attachment = async(Dispatchers.Default) {
                owner.attachTerminal("work", "pane", 80, 24, false)
            }
            assertTrue(attachEntered.await(1, TimeUnit.SECONDS))

            val returned = CountDownLatch(1)
            val destroyCaller = thread {
                requestServiceOwnerShutdown(owner, ownerScope, releaseScope)
                returned.countDown()
            }
            assertTrue("onDestroy blocked on connection cleanup", returned.await(1, TimeUnit.SECONDS))
            assertEquals(ConnectionState.Disconnected, owner.state.value)
            allowAttach.countDown()
            assertTrue(closeEntered.await(1, TimeUnit.SECONDS))

            assertTrue("terminal release scope ended before its drain", releaseJob.isActive)
            assertEquals(emptyList<String>(), events)

            allowClose.countDown()
            withTimeout(2_000) { while (events.size != 3) yield() }
            assertEquals(null, attachment.await())
            assertEquals(listOf("lease", "bridge", "route"), events)
            destroyCaller.join(2_000)
            assertFalse("owner scope survived cleanup", ownerJob.isActive)
            assertFalse("terminal release scope survived cleanup", releaseJob.isActive)
        } finally {
            allowAttach.countDown()
            allowClose.countDown()
            client.close()
            terminalOutput.close()
            ownerScope.cancel()
            releaseScope.cancel()
        }
    }

    @Test
    fun `attempt cleanup revokes lease before bridge and route`() = runBlocking {
        val ownerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val events = Collections.synchronizedList(mutableListOf<String>())
        val active = CompletableDeferred<Unit>()
        val terminalOutput = PipedOutputStream()
        val client = TerminalClient.start(
            RemoteProcess(
                stdin = ByteArrayOutputStream(),
                stdout = PipedInputStream(terminalOutput),
                stderr = ByteArrayInputStream(byteArrayOf()),
                waitFor = { true },
                exitStatus = { null },
                closeCommand = {},
                closeSession = {},
                onClose = { events += "lease" },
                ioDispatcher = Dispatchers.IO,
            ),
            Dispatchers.IO,
            Dispatchers.Default,
        )
        val owner = ConnectionOwner(
            ownerScope,
            ConnectionStateMachine(),
            testDependencies(
                storeProvider = { storeSession() },
                connectRoute = { saved, _, _ ->
                    ConnectionRouteAttempt(
                        prepareBridge = {
                            ConnectionBridgePlan(
                                preview(saved.id),
                                verifyExisting = { true },
                                install = {},
                                start = {
                                    ConnectionActiveBridge(
                                        collectUntilFailure = { publish ->
                                            publish(mapOf("work" to SessionState("epoch")))
                                            active.complete(Unit)
                                            awaitCancellation()
                                        },
                                        close = { events += "bridge" },
                                        attachTerminal = { _, _, _, _, _ -> client },
                                    )
                                },
                            )
                        },
                        close = { events += "route" },
                    )
                },
                waitForRetry = ::unexpectedRetry,
            ),
        )
        try {
            owner.connect(1).join()
            withTimeout(1_000) { active.await() }
            requireNotNull(owner.attachTerminal("work", "pane", 80, 24, false))

            owner.disconnect().join()

            assertEquals(listOf("lease", "bridge", "route"), events)
        } finally {
            client.close()
            terminalOutput.close()
            ownerScope.cancel()
        }
    }

    @Test
    fun `reattach waits for the previous terminal release`() = runBlocking {
        val ownerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val releaseScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val active = CompletableDeferred<Unit>()
        val releaseEntered = CountDownLatch(1)
        val allowRelease = CountDownLatch(1)
        val secondAttach = CompletableDeferred<Unit>()
        val outputs = mutableListOf<PipedOutputStream>()
        fun client(onClose: () -> Unit = {}): TerminalClient {
            val output = PipedOutputStream().also(outputs::add)
            return TerminalClient.start(
                RemoteProcess(
                    stdin = ByteArrayOutputStream(),
                    stdout = PipedInputStream(output),
                    stderr = ByteArrayInputStream(byteArrayOf()),
                    waitFor = { true },
                    exitStatus = { null },
                    closeCommand = {},
                    closeSession = {},
                    onClose = onClose,
                    ioDispatcher = Dispatchers.IO,
                ),
                Dispatchers.IO,
                Dispatchers.Default,
            )
        }
        val first = client {
            releaseEntered.countDown()
            check(allowRelease.await(2, TimeUnit.SECONDS))
        }
        val second = client()
        val attachCount = AtomicInteger()
        val owner = ConnectionOwner(
            ownerScope,
            ConnectionStateMachine(),
            testDependencies(
                storeProvider = { storeSession() },
                connectRoute = { saved, _, _ ->
                    ConnectionRouteAttempt(
                        prepareBridge = {
                            ConnectionBridgePlan(
                                preview(saved.id),
                                verifyExisting = { true },
                                install = {},
                                start = {
                                    ConnectionActiveBridge(
                                        collectUntilFailure = { publish ->
                                            publish(mapOf("work" to SessionState("epoch")))
                                            active.complete(Unit)
                                            awaitCancellation()
                                        },
                                        close = {},
                                        attachTerminal = { _, _, _, _, _ ->
                                            if (attachCount.incrementAndGet() == 1) first else second.also { secondAttach.complete(Unit) }
                                        },
                                    )
                                },
                            )
                        },
                        close = {},
                    )
                },
                waitForRetry = ::unexpectedRetry,
            ),
            releaseScope = releaseScope,
        )
        try {
            owner.connect(1).join()
            withTimeout(1_000) { active.await() }
            requireNotNull(owner.attachTerminal("work", "pane", 80, 24, false)).close()
            assertTrue(releaseEntered.await(1, TimeUnit.SECONDS))

            val attached = async { owner.attachTerminal("work", "pane", 80, 24, false) }
            assertFalse(withTimeoutOrNull(100) { secondAttach.await(); true } ?: false)
            allowRelease.countDown()
            requireNotNull(attached.await()).close()
        } finally {
            allowRelease.countDown()
            owner.cancel()
            first.close()
            second.close()
            outputs.forEach(PipedOutputStream::close)
            ownerScope.cancel()
            releaseScope.cancel()
        }
    }

    @Test
    fun `hardware key failure is terminal with replacement guidance and no retry`() = runBlocking {
        val ownerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val retries = AtomicInteger()
        val owner = ConnectionOwner(
            ownerScope,
            ConnectionStateMachine(),
            testDependencies(
                storeProvider = { storeSession() },
                connectRoute = { _, _, _ -> throw HardwareKeyUnavailableException(IOException("invalidated")) },
                waitForRetry = { _, _ -> retries.incrementAndGet() },
            ),
        )
        try {
            owner.connect(1).join()
            awaitState(owner) { it is ConnectionState.Failed }

            assertEquals(
                ConnectionState.Failed(
                    1,
                    "connection_failed",
                    "Hardware key unavailable. Select or create a replacement key.",
                ),
                owner.state.value,
            )
            assertEquals(0, retries.get())
        } finally {
            owner.cancel()
            ownerScope.cancel()
        }
    }

    @Test
    fun `confirmed changed key forgets only the exact predecessor before retrust`() = runBlocking {
        val ownerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val expected = HostKeyCandidate(Hop.TARGET, "target", 22, "ssh-ed25519", "old", "old-key")
        val actual = expected.copy(sha256 = "new", keyBase64 = "new-key")
        val deleted = mutableListOf<HostKeyCandidate>()
        val updated = mutableListOf<dev.herdroid.core.model.KnownHostRecord>()
        val cacheClears = mutableListOf<Long>()
        val attempts = AtomicInteger()
        val owner = ConnectionOwner(
            ownerScope,
            ConnectionStateMachine(),
            testDependencies(
                storeProvider = {
                    TestStoreSession(
                        findRoute = { route(it) },
                        knownHosts = { _, _ -> emptyList() },
                        updateKnownHost = { updated += it },
                        deleteKnownHost = { deleted += it },
                        clearBridgeCache = { cacheClears += it },
                    )
                },
                connectRoute = { _, _, _ ->
                    when (attempts.incrementAndGet()) {
                        1 -> throw HostKeyChangedException(HostKeyDecision.RejectChanged(expected, actual), IOException("changed"))
                        2 -> throw HostKeyApprovalRequired(HostKeyDecision.Ask(actual), IOException("unknown"))
                        else -> ConnectionRouteAttempt(
                            prepareBridge = {
                                ConnectionBridgePlan(
                                    preview(1),
                                    verifyExisting = { true },
                                    install = {},
                                    start = {
                                        ConnectionActiveBridge(
                                            collectUntilFailure = { publish ->
                                                publish(mapOf("work" to SessionState("epoch")))
                                                awaitCancellation()
                                            },
                                            close = {},
                                        )
                                    },
                                )
                            },
                            close = {},
                        )
                    }
                },
                waitForRetry = { _, _ -> },
            ),
        )
        try {
            owner.connect(1).join()
            awaitState(owner) { it is ConnectionState.NeedsHostKeyReset }
            owner.approveHostKeyReset(true)
            awaitState(owner) { it is ConnectionState.NeedsTrust }
            owner.approveTrust(true)
            awaitState(owner) { it is ConnectionState.Connected }

            assertEquals(listOf(expected), deleted)
            assertEquals(listOf("new-key"), updated.map { it.keyBase64 })
            assertEquals(listOf(1L), cacheClears)
        } finally {
            owner.cancel()
            ownerScope.cancel()
        }
    }

    @Test
    fun `connection attempt clears owned authentication buffers`() = runBlocking {
        val ownerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val password = "secret".encodeToByteArray()
        val saved = ConnectionRouteInput(
            1,
            "route",
            ConnectionEndpointInput("host", 22, "user", SshAuthentication.Password(password), null),
            null,
        )
        val owner = ConnectionOwner(
            ownerScope,
            ConnectionStateMachine(),
            testDependencies(
                storeProvider = {
                    TestStoreSession({ saved }, { _, _ -> emptyList() }, {})
                },
                connectRoute = { route, _, _ ->
                    assertTrue((route.target.authentication as SshAuthentication.Password).ownedBytes().any { it != 0.toByte() })
                    throw TerminalConnectionFailure("stop", "stop")
                },
                waitForRetry = { _, _ -> },
            ),
        )
        try {
            owner.connect(1).join()
            awaitState(owner) { it is ConnectionState.Failed }
            assertTrue(password.all { it == 0.toByte() })
        } finally {
            owner.cancel()
            ownerScope.cancel()
        }
    }

    @Test
    fun `known host lookup exits clear all route authentication buffers`() = runBlocking {
        listOf(1, 2).forEach { failingLookup ->
            listOf(IOException("lookup failed"), CancellationException("lookup cancelled")).forEach { failure ->
                val ownerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val targetPassword = "target-secret".encodeToByteArray()
                val jumpPassword = "jump-secret".encodeToByteArray()
                val saved = ConnectionRouteInput(
                    1,
                    "route",
                    ConnectionEndpointInput("target", 22, "user", SshAuthentication.Password(targetPassword), null),
                    ConnectionEndpointInput(
                        "jump",
                        22,
                        "user",
                        SshAuthentication.Password(jumpPassword),
                        null,
                    ),
                )
                val lookupExited = CompletableDeferred<Unit>()
                var lookups = 0
                val owner = ConnectionOwner(
                    ownerScope,
                    ConnectionStateMachine(),
            testDependencies(
                storeProvider = {
                    TestStoreSession(
                                findRoute = { saved },
                                knownHosts = { _, _ ->
                                    lookups += 1
                                    if (lookups == failingLookup) {
                                        lookupExited.complete(Unit)
                                        throw failure
                                    }
                                    emptyList()
                                },
                                updateKnownHost = {},
                            )
                        },
                        connectRoute = { _, _, _ -> error("SSH must not start") },
                        waitForRetry = { _, _ -> },
                    ),
                )
                try {
                    owner.connect(1).join()
                    withTimeout(1_000) { lookupExited.await() }
                    owner.release()

                    assertTrue(targetPassword.all { it == 0.toByte() })
                    assertTrue(jumpPassword.all { it == 0.toByte() })
                } finally {
                    owner.cancel()
                    ownerScope.cancel()
                }
            }
        }
    }

    @Test
    fun `releasing terminal ownership preserves the failed state for retry`() = runBlocking {
        val ownerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val owner = ConnectionOwner(
            ownerScope,
            ConnectionStateMachine(),
            testDependencies(
                storeProvider = { storeSession() },
                connectRoute = { _, _, _ -> throw TerminalConnectionFailure("failed", "retry me") },
                waitForRetry = { _, _ -> },
            ),
        )
        try {
            owner.connect(1).join()
            awaitState(owner) { it is ConnectionState.Failed }

            owner.release()

            assertEquals(ConnectionState.Failed(1, "failed", "retry me"), owner.state.value)
        } finally {
            owner.cancel()
            ownerScope.cancel()
        }
    }

    @Test
    fun `replacement and disconnect wait for reverse cleanup and reject stale publication`() = runBlocking {
        val ownerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val events = Collections.synchronizedList(mutableListOf<String>())
        val enteredA = CompletableDeferred<Unit>()
        val enteredB = CompletableDeferred<Unit>()
        val routes = AtomicInteger()
        val bridges = AtomicInteger()
        val terminals = AtomicInteger()
        val history = Collections.synchronizedList(mutableListOf<ConnectionState>())
        val owner = ConnectionOwner(
            ownerScope,
            ConnectionStateMachine(),
            testDependencies(
                storeProvider = {
                    TestStoreSession(
                        findRoute = { id -> route(id) },
                        knownHosts = { _, _ -> emptyList() },
                        updateKnownHost = {},
                    )
                },
                connectRoute = { saved, _, _ ->
                    routes.incrementAndGet()
                    ConnectionRouteAttempt(
                        prepareBridge = {
                            ConnectionBridgePlan(
                                preview(saved.id),
                                verifyExisting = { true },
                                install = {},
                                start = {
                                    bridges.incrementAndGet()
                                    ConnectionActiveBridge(
                                        collectUntilFailure = { publish ->
                                            if (saved.id == 1L) {
                                                enteredA.complete(Unit)
                                                try {
                                                    awaitCancellation()
                                                } finally {
                                                    withContext(NonCancellable) {
                                                        publish(mapOf("stale-a" to SessionState("a")))
                                                    }
                                                }
                                            } else {
                                                publish(mapOf("live-b" to SessionState("b")))
                                                enteredB.complete(Unit)
                                                awaitCancellation()
                                            }
                                        },
                                        close = {
                                            events += "${saved.id}-terminal"
                                            terminals.incrementAndGet()
                                            events += "${saved.id}-bridge"
                                        },
                                    )
                                },
                            )
                        },
                        close = { events += "${saved.id}-route" },
                    )
                },
                waitForRetry = ::unexpectedRetry,
            ),
        )
        val stateCollector = ownerScope.launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            owner.state.collect { history += it }
        }

        try {
            owner.connect(1).join()
            withTimeout(1_000) { enteredA.await() }
            owner.connect(2).join()
            withTimeout(1_000) { enteredB.await() }

            assertEquals(
                ConnectionState.Connected(2, mapOf("live-b" to SessionState("b"))),
                owner.state.value,
            )
            assertTrue(history.none { it is ConnectionState.Connected && "stale-a" in it.sessions })
            assertEquals(listOf("1-terminal", "1-bridge", "1-route"), events.filter { it.startsWith("1-") })

            owner.disconnect().join()

            assertEquals(ConnectionState.Disconnected, owner.state.value)
            assertEquals(2, routes.get())
            assertEquals(2, bridges.get())
            assertEquals(2, terminals.get())
            assertEquals(listOf("2-terminal", "2-bridge", "2-route"), events.filter { it.startsWith("2-") })
        } finally {
            stateCollector.cancel()
            owner.cancel()
            ownerScope.cancel()
        }
    }

    @Test
    fun `every terminal stage closes exactly the resources it acquired and authentication never retries`() = runBlocking {
        TerminalStage.entries.forEach { stage ->
            val ownerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val routeCloses = AtomicInteger()
            val bridgeCloses = AtomicInteger()
            val retries = AtomicInteger()
            val terminalCallbacks = AtomicInteger()
            val events = Collections.synchronizedList(mutableListOf<String>())
            val owner = ConnectionOwner(
                ownerScope,
                ConnectionStateMachine(),
            testDependencies(
                storeProvider = {
                        if (stage == TerminalStage.STORAGE) null else TestStoreSession(
                            findRoute = { if (stage == TerminalStage.ROUTE_MISSING) null else route(it) },
                            knownHosts = { _, _ -> emptyList() },
                            updateKnownHost = {},
                        )
                    },
                    connectRoute = { saved, _, _ ->
                        if (stage == TerminalStage.CONNECT) {
                            throw SshAuthenticationFailedException(IOException("bad password"))
                        }
                        ConnectionRouteAttempt(
                            prepareBridge = {
                                if (stage == TerminalStage.PREPARE) throw TerminalConnectionFailure("prepare", "prepare failed")
                                ConnectionBridgePlan(
                                    preview(saved.id),
                                    verifyExisting = {
                                        if (stage == TerminalStage.VERIFY) throw TerminalConnectionFailure("verify", "verify failed")
                                        stage != TerminalStage.INSTALL
                                    },
                                    install = { throw TerminalConnectionFailure("install", "install failed") },
                                    start = {
                                        if (stage == TerminalStage.START) throw TerminalConnectionFailure("start", "start failed")
                                        ConnectionActiveBridge(
                                            collectUntilFailure = {
                                                throw SshAuthenticationFailedException(IOException("expired credential"))
                                            },
                                            close = { bridgeCloses.incrementAndGet(); events += "bridge" },
                                        )
                                    },
                                )
                            },
                            close = { routeCloses.incrementAndGet(); events += "route" },
                        )
                    },
                    waitForRetry = { _, _ -> retries.incrementAndGet() },
                ),
                onTerminal = { _ -> terminalCallbacks.incrementAndGet(); events += "terminal" },
            )
            try {
                owner.connect(7).join()
                if (stage == TerminalStage.INSTALL) {
                    awaitState(owner) { it is ConnectionState.NeedsBridgeApproval }
                    owner.approveBridgeInstall(true)
                }
                awaitState(owner) { it is ConnectionState.Failed }
                val expectedRouteCloses = if (stage.ordinal >= TerminalStage.PREPARE.ordinal) 1 else 0
                val expectedBridgeCloses = if (stage == TerminalStage.ACTIVE) 1 else 0
                withTimeout(1_000) {
                    while (
                        terminalCallbacks.get() != 1 ||
                        routeCloses.get() != expectedRouteCloses ||
                        bridgeCloses.get() != expectedBridgeCloses
                    ) yield()
                }

                assertEquals(stage.name, 1, terminalCallbacks.get())
                assertEquals(stage.name, 0, retries.get())
                if (stage == TerminalStage.STORAGE) {
                    assertEquals(
                        ConnectionState.Failed(7, "storage_unavailable", "Route storage is unavailable"),
                        owner.state.value,
                    )
                }
                assertEquals(stage.name, expectedRouteCloses, routeCloses.get())
                assertEquals(stage.name, expectedBridgeCloses, bridgeCloses.get())
                if (stage == TerminalStage.ACTIVE) {
                    assertEquals(listOf("terminal", "bridge", "route"), events)
                }
            } finally {
                owner.cancel()
                ownerScope.cancel()
            }
        }
    }

    @Test
    fun `cancellation at every owned suspension stage closes acquired resources once`() = runBlocking {
        CancellationStage.entries.forEach { stage ->
            val ownerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val entered = CompletableDeferred<Unit>()
            val routeCloses = AtomicInteger()
            val bridgeCloses = AtomicInteger()
            val events = mutableListOf<String>()
            val owner = ConnectionOwner(
                ownerScope,
                ConnectionStateMachine(),
            testDependencies(
                storeProvider = {
                    TestStoreSession(
                            findRoute = {
                                if (stage == CancellationStage.LOAD) {
                                    entered.complete(Unit)
                                    awaitCancellation()
                                }
                                route(it)
                            },
                            knownHosts = { _, _ -> emptyList() },
                            updateKnownHost = {},
                        )
                    },
                    connectRoute = { saved, _, _ ->
                        ConnectionRouteAttempt(
                            prepareBridge = {
                                if (stage == CancellationStage.PREPARE) {
                                    entered.complete(Unit)
                                    awaitCancellation()
                                }
                                ConnectionBridgePlan(
                                    preview(saved.id),
                                    verifyExisting = {
                                        if (stage == CancellationStage.VERIFY) {
                                            entered.complete(Unit)
                                            awaitCancellation()
                                        }
                                        stage !in setOf(CancellationStage.APPROVAL, CancellationStage.INSTALL)
                                    },
                                    install = {
                                        if (stage == CancellationStage.INSTALL) {
                                            entered.complete(Unit)
                                            awaitCancellation()
                                        }
                                    },
                                    start = {
                                        if (stage == CancellationStage.START) {
                                            entered.complete(Unit)
                                            awaitCancellation()
                                        }
                                        ConnectionActiveBridge(
                                            collectUntilFailure = {
                                                if (stage == CancellationStage.RETRY) throw IOException("retry")
                                                entered.complete(Unit)
                                                awaitCancellation()
                                            },
                                            close = { bridgeCloses.incrementAndGet(); events += "bridge" },
                                        )
                                    },
                                )
                            },
                            close = { routeCloses.incrementAndGet(); events += "route" },
                        )
                    },
                    waitForRetry = { _, _ ->
                        entered.complete(Unit)
                        awaitCancellation()
                    },
                ),
            )
            try {
                owner.connect(13).join()
                when (stage) {
                    CancellationStage.APPROVAL -> awaitState(owner) { it is ConnectionState.NeedsBridgeApproval }
                    CancellationStage.INSTALL -> {
                        awaitState(owner) { it is ConnectionState.NeedsBridgeApproval }
                        owner.approveBridgeInstall(true)
                        withTimeout(1_000) { entered.await() }
                    }
                    else -> withTimeout(1_000) { entered.await() }
                }

                owner.shutdown()

                assertEquals(stage.name, if (stage == CancellationStage.LOAD) 0 else 1, routeCloses.get())
                assertEquals(
                    stage.name,
                    if (stage == CancellationStage.ACTIVE || stage == CancellationStage.RETRY) 1 else 0,
                    bridgeCloses.get(),
                )
                val expectedOrder = when (stage) {
                    CancellationStage.LOAD -> emptyList()
                    CancellationStage.ACTIVE, CancellationStage.RETRY -> listOf("bridge", "route")
                    else -> listOf("route")
                }
                assertEquals(stage.name, expectedOrder, events)
                assertEquals(stage.name, ConnectionState.Disconnected, owner.state.value)
            } finally {
                owner.cancel()
                ownerScope.cancel()
            }
        }
    }

    @Test
    fun `destruction interrupts connect and returns before resistant close while cleanup finishes once`() = runBlocking {
        val connectScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val connectEntered = CountDownLatch(1)
        val connectRelease = CountDownLatch(1)
        val connectInterrupted = CompletableDeferred<Unit>()
        val connectingOwner = ConnectionOwner(
            connectScope,
            ConnectionStateMachine(),
            testDependencies(
                storeProvider = {
                    TestStoreSession(
                        findRoute = { route(it) },
                        knownHosts = { _, _ -> emptyList() },
                        updateKnownHost = {},
                    )
                },
                connectRoute = { _, _, _ ->
                    connectEntered.countDown()
                    while (true) {
                        try {
                            connectRelease.await()
                            break
                        } catch (_: InterruptedException) {
                            connectInterrupted.complete(Unit)
                        }
                    }
                    throw IOException("released")
                },
                waitForRetry = ::unexpectedRetry,
            ),
        )
        try {
            connectingOwner.connect(20).join()
            assertTrue(connectEntered.await(1, TimeUnit.SECONDS))

            withTimeout(250) { launch(Dispatchers.Default) { connectingOwner.cancel() }.join() }
            withTimeout(1_000) { connectInterrupted.await() }
            connectRelease.countDown()
            connectingOwner.cancel()
        } finally {
            connectRelease.countDown()
            connectingOwner.cancel()
            connectScope.cancel()
        }

        val closeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val active = CompletableDeferred<Unit>()
        val closeEntered = CountDownLatch(1)
        val closeRelease = CountDownLatch(1)
        val events = Collections.synchronizedList(mutableListOf<String>())
        val closingOwner = ConnectionOwner(
            closeScope,
            ConnectionStateMachine(),
            testDependencies(
                storeProvider = {
                    TestStoreSession(
                        findRoute = { route(it) },
                        knownHosts = { _, _ -> emptyList() },
                        updateKnownHost = {},
                    )
                },
                connectRoute = { saved, _, _ ->
                    ConnectionRouteAttempt(
                        prepareBridge = {
                            ConnectionBridgePlan(
                                preview(saved.id),
                                verifyExisting = { true },
                                install = {},
                                start = {
                                    ConnectionActiveBridge(
                                        collectUntilFailure = {
                                            active.complete(Unit)
                                            awaitCancellation()
                                        },
                                        close = {
                                            closeEntered.countDown()
                                            while (true) {
                                                try {
                                                    if (closeRelease.await(10, TimeUnit.MILLISECONDS)) break
                                                } catch (_: InterruptedException) {
                                                    // Deliberately cancellation-resistant transport close.
                                                }
                                            }
                                            events += "bridge"
                                        },
                                    )
                                },
                            )
                        },
                        close = { events += "route" },
                    )
                },
                waitForRetry = ::unexpectedRetry,
            ),
        )
        try {
            closingOwner.connect(21).join()
            withTimeout(1_000) { active.await() }

            withTimeout(250) { launch(Dispatchers.Default) { closingOwner.cancel() }.join() }
            assertTrue(closeEntered.await(1, TimeUnit.SECONDS))
            assertTrue(events.isEmpty())
            closeRelease.countDown()
            withTimeout(1_000) { while (events.size != 2) yield() }
            closingOwner.cancel()

            assertEquals(listOf("bridge", "route"), events)
        } finally {
            closeRelease.countDown()
            closingOwner.cancel()
            closeScope.cancel()
        }
    }

    @Test
    fun `compatible reconnect skips approval while failed install requires retry and renewed approval`() = runBlocking {
        val compatible = approvalScenario(compatible = true)
        assertEquals(0, compatible.approvals)
        assertEquals(0, compatible.installs)
        assertEquals(2, compatible.attempts)

        val missing = approvalScenario(compatible = false)
        assertEquals(2, missing.approvals)
        assertEquals(2, missing.installs)
        assertEquals(2, missing.attempts)
    }

    @Test
    fun `approved bridge install publishes a connecting stage before upload completes`() = runBlocking {
        val ownerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val installEntered = CompletableDeferred<Unit>()
        val owner = ConnectionOwner(
            ownerScope,
            ConnectionStateMachine(),
            testDependencies(
                storeProvider = { storeSession() },
                connectRoute = { saved, _, _ ->
                    ConnectionRouteAttempt(
                        prepareBridge = {
                            ConnectionBridgePlan(
                                preview(saved.id),
                                verifyExisting = { false },
                                install = {
                                    installEntered.complete(Unit)
                                    awaitCancellation()
                                },
                                start = { error("install must complete before bridge start") },
                            )
                        },
                        close = {},
                    )
                },
                waitForRetry = ::unexpectedRetry,
            ),
        )
        try {
            owner.connect(12).join()
            awaitState(owner) { it is ConnectionState.NeedsBridgeApproval }

            owner.approveBridgeInstall(true)
            withTimeout(1_000) { installEntered.await() }

            assertEquals(ConnectStage.InstallingBridge, (owner.state.value as ConnectionState.Connecting).stage)
        } finally {
            owner.cancel()
            ownerScope.cancel()
        }
    }

    @Test
    fun `transient retries use exact capped backoff`() = runBlocking {
        val ownerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val delays = Collections.synchronizedList(mutableListOf<Long>())
        val owner = ConnectionOwner(
            ownerScope,
            ConnectionStateMachine(),
            testDependencies(
                storeProvider = { storeSession() },
                connectRoute = { _, _, _ ->
                    throw BridgeTransportException("Bridge transport failed", IOException("offline"))
                },
                waitForRetry = { seconds, _ ->
                    delays += seconds
                    if (delays.size == 7) awaitCancellation()
                },
            ),
        )
        try {
            owner.connect(8).join()
            withTimeout(1_000) { while (delays.size < 7) yield() }

            assertEquals(listOf(1L, 2L, 4L, 8L, 16L, 30L, 30L), delays)
        } finally {
            owner.disconnect().join()
            ownerScope.cancel()
        }
    }

    @Test
    fun `concurrent early network callbacks provide one lossless immediate wake`() = runBlocking {
        val ownerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val waiterEntered = CompletableDeferred<Unit>()
        val receiveWake = CompletableDeferred<Unit>()
        val connected = CompletableDeferred<Unit>()
        val attempts = AtomicInteger()
        val waits = AtomicInteger()
        val owner = ConnectionOwner(
            ownerScope,
            ConnectionStateMachine(),
            testDependencies(
                storeProvider = { storeSession() },
                connectRoute = { saved, _, _ ->
                    if (attempts.incrementAndGet() == 1) throw IOException("network changed")
                    ConnectionRouteAttempt(
                        prepareBridge = {
                            ConnectionBridgePlan(
                                preview(saved.id),
                                verifyExisting = { true },
                                install = {},
                                start = {
                                    ConnectionActiveBridge(
                                        collectUntilFailure = { publish ->
                                            publish(mapOf("work" to SessionState("epoch")))
                                            connected.complete(Unit)
                                            awaitCancellation()
                                        },
                                        close = {},
                                    )
                                },
                            )
                        },
                        close = {},
                    )
                },
                waitForRetry = { _, wake ->
                    waits.incrementAndGet()
                    waiterEntered.complete(Unit)
                    receiveWake.await()
                    wake.receive()
                },
            ),
        )
        try {
            owner.connect(9).join()
            withTimeout(1_000) { waiterEntered.await() }
            val callbacks = List(24) { launch(Dispatchers.Default) { owner.networkAvailable() } }
            callbacks.forEach { it.join() }
            receiveWake.complete(Unit)
            withTimeout(1_000) { connected.await() }

            assertEquals(2, attempts.get())
            assertEquals(1, waits.get())
            assertTrue(owner.state.value is ConnectionState.Connected)
        } finally {
            owner.disconnect().join()
            ownerScope.cancel()
        }
    }

    private suspend fun approvalScenario(compatible: Boolean): ApprovalResult {
        val ownerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val approvals = AtomicInteger()
        val installs = AtomicInteger()
        val attempts = AtomicInteger()
        val active = CompletableDeferred<Unit>()
        val owner = ConnectionOwner(
            ownerScope,
            ConnectionStateMachine(),
            testDependencies(
                storeProvider = { storeSession() },
                connectRoute = { saved, _, _ ->
                    val attempt = attempts.incrementAndGet()
                    ConnectionRouteAttempt(
                        prepareBridge = {
                            ConnectionBridgePlan(
                                preview(saved.id),
                                verifyExisting = { compatible },
                                install = {
                                    installs.incrementAndGet()
                                    if (attempt == 1) throw IOException("upload interrupted")
                                },
                                start = {
                                    ConnectionActiveBridge(
                                        collectUntilFailure = { publish ->
                                            if (attempt == 1) throw IOException("bridge EOF")
                                            publish(mapOf("work" to SessionState("epoch")))
                                            active.complete(Unit)
                                            awaitCancellation()
                                        },
                                        close = {},
                                    )
                                },
                            )
                        },
                        close = {},
                    )
                },
                waitForRetry = { _, _ -> },
            ),
        )
        try {
            owner.connect(11).join()
            if (!compatible) {
                awaitState(owner) { it is ConnectionState.NeedsBridgeApproval }
                approvals.incrementAndGet()
                owner.approveBridgeInstall(true)
                awaitState(owner) { it is ConnectionState.Failed }
                owner.connect(11).join()
                awaitState(owner) { it is ConnectionState.NeedsBridgeApproval }
                approvals.incrementAndGet()
                owner.approveBridgeInstall(true)
            }
            withTimeout(1_000) { active.await() }
            return ApprovalResult(approvals.get(), installs.get(), attempts.get())
        } finally {
            owner.disconnect().join()
            ownerScope.cancel()
        }
    }

    private fun storeSession() = TestStoreSession(
        findRoute = { route(it) },
        knownHosts = { _, _ -> emptyList() },
        updateKnownHost = {},
    )

    private fun testDependencies(
        storeProvider: suspend () -> TestStoreSession?,
        connectRoute: (ConnectionRouteInput, List<KnownHostRecord>, List<KnownHostRecord>) -> ConnectionRouteAttempt,
        waitForRetry: suspend (Long, ReceiveChannel<Unit>) -> Unit,
        nowMillis: () -> Long = System::currentTimeMillis,
    ): ConnectionDependencies {
        var borrowed: TestStoreSession? = null
        suspend fun store() = borrowed ?: storeProvider()?.also { borrowed = it }
            ?: throw LocalDataUnavailableException()
        return ConnectionDependencies(
            findRoute = { store().findRoute(it) },
            knownHosts = { host, port -> store().knownHosts(host, port) },
            updateKnownHost = { store().updateKnownHost(it) },
            deleteKnownHost = { store().deleteKnownHost(it) },
            clearBridgeCache = { store().clearBridgeCache(it) },
            connectRoute = connectRoute,
            waitForRetry = waitForRetry,
            nowMillis = nowMillis,
        )
    }

    private class TestStoreSession(
        val findRoute: suspend (Long) -> ConnectionRouteInput?,
        val knownHosts: suspend (String, Int) -> List<KnownHostRecord>,
        val updateKnownHost: suspend (KnownHostRecord) -> Unit,
        val deleteKnownHost: suspend (HostKeyCandidate) -> Unit = {},
        val clearBridgeCache: suspend (Long) -> Unit = {},
    )

    private val ConnectionRouteInput.id: Long get() = routeId

    private fun SshAuthentication.Password.ownedBytes(): ByteArray =
        javaClass.getDeclaredField("ownedBytes").run {
            isAccessible = true
            get(this@ownedBytes) as ByteArray
        }

    private fun route(id: Long) = ConnectionRouteInput(
        id,
        "route-$id",
        ConnectionEndpointInput("host$id", 22, "user", SshAuthentication.Password("secret".encodeToByteArray()), null),
        null,
    )

    private fun preview(routeId: Long) = BridgeApproval(
        "route-$routeId",
        RemoteOperatingSystem.LINUX,
        "x86_64",
        "x86_64-unknown-linux-gnu",
        "/home/user/.herdroid/plugins/dev.herdroid.bridge/0.1.0/x86_64-unknown-linux-gnu",
        "0.1.0",
        "0.8.0",
        "a".repeat(64),
    )

    private suspend fun awaitState(owner: ConnectionOwner, predicate: (ConnectionState) -> Boolean) =
        withTimeout(1_000) { owner.state.first(predicate) }

    private suspend fun unexpectedRetry(@Suppress("UNUSED_PARAMETER") seconds: Long, @Suppress("UNUSED_PARAMETER") wake: ReceiveChannel<Unit>) {
        throw AssertionError("unexpected retry")
    }

    private data class ApprovalResult(val approvals: Int, val installs: Int, val attempts: Int)

    private enum class TerminalStage {
        STORAGE,
        ROUTE_MISSING,
        CONNECT,
        PREPARE,
        VERIFY,
        INSTALL,
        START,
        ACTIVE,
    }

    private enum class CancellationStage {
        LOAD,
        PREPARE,
        VERIFY,
        APPROVAL,
        INSTALL,
        START,
        ACTIVE,
        RETRY,
    }
}
