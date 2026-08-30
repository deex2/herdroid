package dev.herdroid.feature.terminal

import androidx.lifecycle.ViewModelStore
import dev.herdroid.core.model.ActionOutcome
import dev.herdroid.core.model.AgentStatus
import dev.herdroid.core.model.Pane
import dev.herdroid.core.model.SessionState
import dev.herdroid.core.model.SplitDirection
import dev.herdroid.core.model.Tab
import dev.herdroid.core.model.TerminalFrame
import dev.herdroid.core.model.TerminalScrollDirection
import dev.herdroid.core.model.TerminalScrollSource
import dev.herdroid.core.model.TerminalState
import dev.herdroid.core.model.Workspace
import dev.herdroid.core.model.ZoomMode
import dev.herdroid.session.api.ConnectionState
import dev.herdroid.session.api.HierarchyCommands
import dev.herdroid.core.model.OpenLevel
import dev.herdroid.core.model.OpenResolution
import dev.herdroid.core.model.OpenTargetIdentifiers
import dev.herdroid.session.api.TerminalLease
import java.io.IOException
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.yield
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class TerminalViewModelTest {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `terminal writes are queued by the ViewModel and keep exact order`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val vmScope = CoroutineScope(SupervisorJob() + dispatcher)
        val lease = RecordingLease()
        val viewModel = TerminalViewModel(
            state = MutableStateFlow(ConnectionState.Connected(7, mapOf("work" to session()))),
            actions = unavailableActions(),
            attach = { _, _, _, _, _ -> lease },
            scope = vmScope,
            ioDispatcher = dispatcher,
        )
        runCurrent()
        val key = requireNotNull(viewModel.uiState.value.attachmentKey)

        repeat(8) { viewModel.sendText(key, "message-$it") }

        assertEquals(emptyList<String>(), lease.commands)
        runCurrent()
        assertEquals((0 until 8).map { "text:message-$it" }, lease.commands)
        viewModel.close()
        vmScope.cancel()
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `terminal writer contains a failed lease call and continues queued writes`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val uncaught = mutableListOf<Throwable>()
        val vmScope = CoroutineScope(
            SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, failure -> uncaught += failure },
        )
        val lease = RecordingLease(IOException("revoked"))
        val viewModel = TerminalViewModel(
            state = MutableStateFlow(ConnectionState.Connected(7, mapOf("work" to session()))),
            actions = unavailableActions(),
            attach = { _, _, _, _, _ -> lease },
            scope = vmScope,
            ioDispatcher = dispatcher,
        )
        runCurrent()
        val key = requireNotNull(viewModel.uiState.value.attachmentKey)

        viewModel.sendText(key, "rejected")
        viewModel.sendText(key, "accepted")
        runCurrent()

        assertEquals(emptyList<Throwable>(), uncaught)
        assertEquals(listOf("text:accepted"), lease.commands)
        viewModel.close()
        vmScope.cancel()
    }

    @Test
    fun `stale terminal callbacks never target a replacement attachment`() = runBlocking {
        val vmScope = CoroutineScope(coroutineContext + SupervisorJob())
        val state = MutableStateFlow<ConnectionState>(ConnectionState.Connected(7, mapOf("work" to session())))
        val first = RecordingLease()
        val second = RecordingLease()
        val viewModel = TerminalViewModel(
            state = state,
            actions = unavailableActions(),
            attach = { _, pane, _, _, _ -> if (pane == "p1") first else second },
            scope = vmScope,
            ioDispatcher = Dispatchers.IO,
        )
        try {
            withTimeout(1_000) { while (viewModel.uiState.value.attachmentKey?.paneId != "p1") yield() }
            val staleKey = requireNotNull(viewModel.uiState.value.attachmentKey)
            val staleText = { viewModel.sendText(staleKey, "stale") }
            val staleBytes = { viewModel.sendBytes(staleKey, byteArrayOf(1)) }
            val staleResize = { viewModel.resize(staleKey, 90, 30) }
            val staleScroll = {
                viewModel.scroll(staleKey, TerminalScrollDirection.UP, 2, TerminalScrollSource.WHEEL)
            }

            state.value = ConnectionState.Connected(
                7,
                mapOf("work" to session(focusedTab = "t2", focusedPane = "p2")),
            )
            withTimeout(1_000) { while (viewModel.uiState.value.attachmentKey?.paneId != "p2") yield() }
            val currentKey = requireNotNull(viewModel.uiState.value.attachmentKey)

            staleText()
            staleBytes()
            staleResize()
            staleScroll()
            viewModel.sendText(currentKey, "current")
            withTimeout(1_000) { while (second.commands.isEmpty()) yield() }

            assertEquals(emptyList<String>(), first.commands)
            assertEquals(listOf("text:current"), second.commands)
        } finally {
            viewModel.close()
            vmScope.cancel()
        }
    }

    @Test
    fun `stale frame collector cannot consume replacement first frame`() = runBlocking {
        val vmScope = CoroutineScope(coroutineContext + SupervisorJob())
        val state = MutableStateFlow<ConnectionState>(ConnectionState.Connected(7, mapOf("work" to session())))
        val first = RecordingLease()
        val second = RecordingLease()
        val viewModel = TerminalViewModel(
            state = state,
            actions = unavailableActions(),
            attach = { _, pane, _, _, _ -> if (pane == "p1") first else second },
            scope = vmScope,
            ioDispatcher = Dispatchers.IO,
        )
        var staleCollector: kotlinx.coroutines.Deferred<TerminalFrame>? = null
        try {
            withTimeout(1_000) { while (viewModel.uiState.value.attachmentKey?.paneId != "p1") yield() }
            val staleKey = requireNotNull(viewModel.uiState.value.attachmentKey)
            staleCollector = async(start = CoroutineStart.UNDISPATCHED) {
                viewModel.frames(staleKey).first()
            }

            state.value = ConnectionState.Connected(
                7,
                mapOf("work" to session(focusedTab = "t2", focusedPane = "p2")),
            )
            withTimeout(1_000) { while (viewModel.uiState.value.attachmentKey?.paneId != "p2") yield() }
            val currentKey = requireNotNull(viewModel.uiState.value.attachmentKey)
            repeat(10) { yield() }
            val firstFrame = TerminalFrame(1, 80, 24, true, byteArrayOf(7))

            second.emit(firstFrame)
            repeat(10) { yield() }

            assertEquals(false, staleCollector.isCompleted)
            val currentFrame = withTimeout(1_000) { viewModel.frames(currentKey).first() }
            assertEquals(firstFrame.seq, currentFrame.seq)
            assertEquals(firstFrame.bytes.toList(), currentFrame.bytes.toList())
        } finally {
            staleCollector?.cancelAndJoin()
            viewModel.close()
            vmScope.cancel()
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `ViewModelStore clear closes lease returned during cancellation exactly once`() = runTest {
        val callerJob = SupervisorJob()
        val callerScope = CoroutineScope(callerJob + kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        val attachEntered = CountDownLatch(1)
        val allowAttachReturn = CountDownLatch(1)
        val client = ClientHarness()
        val viewModel = TerminalViewModel(
            state = MutableStateFlow(ConnectionState.Connected(7, mapOf("work" to session()))),
            actions = unavailableActions(),
            attach = { _, _, _, _, _ ->
                attachEntered.countDown()
                check(allowAttachReturn.await(2, TimeUnit.SECONDS))
                client.lease
            },
            scope = callerScope,
            ioDispatcher = Dispatchers.IO,
        )
        viewModel.addCloseable(AutoCloseable { callerScope.cancel() })
        val store = ViewModelStore().also { it.put("terminal", viewModel) }
        try {
            runCurrent()
            assertEquals(true, attachEntered.await(1, TimeUnit.SECONDS))

            store.clear()
            allowAttachReturn.countDown()
            withTimeout(2_000) { callerJob.join() }

            assertEquals(1, client.closeCount.get())
            viewModel.close()
            assertEquals(1, client.closeCount.get())
        } finally {
            allowAttachReturn.countDown()
            store.clear()
            callerScope.cancel()
        }
    }

    @Test
    fun `unregistered bridge keeps terminal in not-ready state without a stale lease`() = runBlocking {
        val vmScope = CoroutineScope(coroutineContext + SupervisorJob())
        val viewModel = TerminalViewModel(
            state = MutableStateFlow(ConnectionState.Connected(7, mapOf("work" to session()))),
            actions = unavailableActions(),
            attach = { _, _, _, _, _ -> null },
            scope = vmScope,
            ioDispatcher = Dispatchers.IO,
        )

        yield()

        assertNull(viewModel.uiState.value.attachmentKey)
        assertEquals(dev.herdroid.core.model.TerminalState.Attaching, viewModel.uiState.value.terminalState)
        assertNull(viewModel.uiState.value.message)
        viewModel.close()
        vmScope.cancel()
    }

    @Test
    fun `repeated clear enqueues one release that outlives the caller scope`() = runBlocking {
        val callerScope = CoroutineScope(coroutineContext + SupervisorJob())
        val lease = RecordingLease()
        val viewModel = TerminalViewModel(
            state = MutableStateFlow(ConnectionState.Connected(7, mapOf("work" to session()))),
            actions = unavailableActions(),
            attach = { _, _, _, _, _ -> lease },
            scope = callerScope,
            ioDispatcher = Dispatchers.IO,
        )
        withTimeout(1_000) { while (viewModel.uiState.value.attachmentKey == null) yield() }

        viewModel.close()
        viewModel.close()
        callerScope.cancel()

        assertEquals(1, lease.closeCount.get())
    }

    @Test
    fun `notification open focuses only an exact live target`() = runBlocking {
        val vmScope = CoroutineScope(coroutineContext + SupervisorJob())
        val requests = mutableListOf<String>()
        val viewModel = TerminalViewModel(
            state = MutableStateFlow(ConnectionState.Connected(7, mapOf("work" to session()))),
            actions = hierarchyCommands { request ->
                requests += request
                ActionOutcome.Succeeded
            },
            attach = { _, _, _, _, _ -> null },
            scope = vmScope,
            ioDispatcher = Dispatchers.IO,
        )

        viewModel.initialize(
            OpenTargetIdentifiers(7, "work", "w1", "t2", "p2", "e1"),
        )
        withTimeout(1_000) { while (requests.isEmpty()) yield() }

        assertEquals(OpenLevel.Pane, viewModel.uiState.value.switcherLevel)
        assertEquals(listOf("pane.focus:work:p2"), requests)
        assertEquals("work", viewModel.uiState.value.selectedSession)
        assertEquals(false, viewModel.uiState.value.switcherOpen)
        assertEquals(true, viewModel.uiState.value.switchingTerminal)
        vmScope.cancel()
    }

    @Test
    fun `route initialization is idempotent for the same target`() = runBlocking {
        val vmScope = CoroutineScope(coroutineContext + SupervisorJob())
        val requests = mutableListOf<String>()
        val viewModel = TerminalViewModel(
            state = MutableStateFlow(ConnectionState.Connected(7, mapOf("work" to session()))),
            actions = hierarchyCommands { request ->
                requests += request
                ActionOutcome.Succeeded
            },
            attach = { _, _, _, _, _ -> null },
            scope = vmScope,
            ioDispatcher = Dispatchers.IO,
        )
        val target = OpenTargetIdentifiers(7, "work", "w1", "t2", "p2", "e1")

        viewModel.initialize(target)
        viewModel.initialize(target)
        withTimeout(1_000) { while (requests.isEmpty()) yield() }

        assertEquals(listOf("pane.focus:work:p2"), requests)
        vmScope.cancel()
    }

    @Test
    fun `stale notification opens the nearest live switcher level without focus`() = runBlocking {
        val vmScope = CoroutineScope(coroutineContext + SupervisorJob())
        var requests = 0
        val viewModel = TerminalViewModel(
            state = MutableStateFlow(ConnectionState.Connected(7, mapOf("work" to session()))),
            actions = hierarchyCommands {
                requests++
                ActionOutcome.Succeeded
            },
            attach = { _, _, _, _, _ -> null },
            scope = vmScope,
            ioDispatcher = Dispatchers.IO,
        )

        viewModel.initialize(
            OpenTargetIdentifiers(7, "work", "w1", "t2", "gone", "e1"),
        )
        yield()

        assertEquals(0, requests)
        assertEquals(OpenLevel.Panes, viewModel.uiState.value.switcherLevel)
        assertEquals("w1", viewModel.uiState.value.switcherWorkspaceId)
        assertEquals("t2", viewModel.uiState.value.switcherTabId)
        assertEquals(OpenTargetIdentifiers.STALE_MESSAGE, viewModel.uiState.value.message)
        assertEquals(true, viewModel.uiState.value.switcherOpen)
        vmScope.cancel()
    }

    @Test
    fun `partial-stale message survives a slower successful initial attach`() = runBlocking {
        val vmScope = CoroutineScope(coroutineContext + SupervisorJob())
        val attachEntered = CompletableDeferred<Unit>()
        val releaseAttach = CompletableDeferred<Unit>()
        val viewModel = TerminalViewModel(
            state = MutableStateFlow(ConnectionState.Connected(7, mapOf("work" to session()))),
            actions = unavailableActions(),
            attach = { _, _, _, _, _ ->
                attachEntered.complete(Unit)
                releaseAttach.await()
                RecordingLease()
            },
            scope = vmScope,
            ioDispatcher = Dispatchers.IO,
        )
        withTimeout(1_000) { attachEntered.await() }
        viewModel.initialize(
            OpenTargetIdentifiers(7, "work", "w1", "t1", "p1", "e1"),
            OpenResolution(
                OpenLevel.Panes,
                "work",
                "w1",
                "t1",
                message = OpenTargetIdentifiers.STALE_MESSAGE,
            ),
        )
        assertEquals(OpenTargetIdentifiers.STALE_MESSAGE, viewModel.uiState.value.message)

        releaseAttach.complete(Unit)
        withTimeout(1_000) { while (viewModel.uiState.value.attachmentKey == null) yield() }

        assertEquals(OpenTargetIdentifiers.STALE_MESSAGE, viewModel.uiState.value.message)
        viewModel.close()
        vmScope.cancel()
    }

    @Test
    fun `notification target is revalidated immediately before remote focus`() = runBlocking {
        val vmScope = CoroutineScope(coroutineContext + SupervisorJob())
        val state = MutableStateFlow<ConnectionState>(ConnectionState.Connected(7, mapOf("work" to session(epoch = "e1"))))
        var requests = 0
        val viewModel = TerminalViewModel(
            state = state,
            actions = hierarchyCommands {
                requests++
                ActionOutcome.Succeeded
            },
            attach = { _, _, _, _, _ -> null },
            scope = vmScope,
            ioDispatcher = Dispatchers.IO,
        )

        viewModel.initialize(OpenTargetIdentifiers(7, "work", "w1", "t2", "p2", "e1"))
        state.value = ConnectionState.Connected(7, mapOf("work" to session(epoch = "e2")))
        withTimeout(1_000) {
            while (requests == 0 && viewModel.uiState.value.switcherLevel == OpenLevel.Pane) yield()
        }

        assertEquals(0, requests)
        assertEquals(OpenLevel.Sessions, viewModel.uiState.value.switcherLevel)
        assertEquals(OpenTargetIdentifiers.STALE_MESSAGE, viewModel.uiState.value.message)
        vmScope.cancel()
    }

    @Test
    fun `direct selection sends one typed action and waits for authoritative state`() = runBlocking {
        val vmScope = CoroutineScope(coroutineContext + SupervisorJob())
        val state = MutableStateFlow<ConnectionState>(ConnectionState.Connected(7, mapOf("work" to session())))
        val requests = mutableListOf<String>()
        val viewModel = TerminalViewModel(
            state = state,
            actions = hierarchyCommands { request ->
                requests += request
                ActionOutcome.Succeeded
            },
            attach = { _, _, _, _, _ -> null },
            scope = vmScope,
            ioDispatcher = Dispatchers.IO,
        )

        viewModel.focusTab("work", "t2").join()

        assertEquals(listOf("tab.focus:work:t2"), requests)
        assertEquals("t1", viewModel.uiState.value.session?.focusedTabId)
        state.value = ConnectionState.Connected(7, mapOf("work" to session(focusedTab = "t2", focusedPane = "p2")))
        yield()
        assertEquals("t2", viewModel.uiState.value.session?.focusedTabId)
        vmScope.cancel()
    }

    @Test
    fun `tab switch loads immediately until the authoritative terminal attaches`() = runBlocking {
        val vmScope = CoroutineScope(coroutineContext + SupervisorJob())
        val state = MutableStateFlow<ConnectionState>(ConnectionState.Connected(7, mapOf("work" to session())))
        val response = CompletableDeferred<Unit>()
        val first = ClientHarness()
        val second = ClientHarness()
        val viewModel = TerminalViewModel(
            state = state,
            actions = hierarchyCommands {
                response.await()
                ActionOutcome.Succeeded
            },
            attach = { _, pane, _, _, _ -> if (pane == "p1") first.lease else second.lease },
            scope = vmScope,
            ioDispatcher = Dispatchers.IO,
        )
        withTimeout(1_000) { while (viewModel.uiState.value.attachmentKey?.paneId != "p1") yield() }
        viewModel.openSwitcher()

        val switch = viewModel.focusTab("work", "t2")

        assertEquals(true, viewModel.uiState.value.switchingTerminal)
        assertEquals(false, viewModel.uiState.value.switcherOpen)
        assertEquals("p1", viewModel.uiState.value.attachmentKey?.paneId)
        response.complete(Unit)
        switch.join()
        assertEquals(true, viewModel.uiState.value.switchingTerminal)

        state.value = ConnectionState.Connected(7, mapOf("work" to session(focusedTab = "t2", focusedPane = "p2")))
        withTimeout(1_000) { while (viewModel.uiState.value.attachmentKey?.paneId != "p2") yield() }

        assertEquals(false, viewModel.uiState.value.switchingTerminal)
        viewModel.close()
        vmScope.cancel()
    }

    @Test
    fun `failed switch restores the existing terminal`() = runBlocking {
        val vmScope = CoroutineScope(coroutineContext + SupervisorJob())
        val response = CompletableDeferred<Unit>()
        val first = ClientHarness()
        val viewModel = TerminalViewModel(
            state = MutableStateFlow(ConnectionState.Connected(7, mapOf("work" to session()))),
            actions = hierarchyCommands {
                response.await()
                throw IOException("focus failed")
            },
            attach = { _, _, _, _, _ -> first.lease },
            scope = vmScope,
            ioDispatcher = Dispatchers.IO,
        )
        withTimeout(1_000) { while (viewModel.uiState.value.attachmentKey == null) yield() }

        val switch = viewModel.focusTab("work", "t2")
        assertEquals(true, viewModel.uiState.value.switchingTerminal)
        response.complete(Unit)
        switch.join()

        assertEquals(false, viewModel.uiState.value.switchingTerminal)
        assertEquals("p1", viewModel.uiState.value.attachmentKey?.paneId)
        assertEquals("focus failed", viewModel.uiState.value.message)
        viewModel.close()
        vmScope.cancel()
    }

    @Test
    fun `failed replacement attach keeps the existing terminal and shows the error`() = runBlocking {
        val vmScope = CoroutineScope(coroutineContext + SupervisorJob())
        val state = MutableStateFlow<ConnectionState>(ConnectionState.Connected(7, mapOf("work" to session())))
        val first = ClientHarness()
        val second = ClientHarness()
        var replacementAttempts = 0
        val viewModel = TerminalViewModel(
            state = state,
            actions = hierarchyCommands(),
            attach = { _, pane, _, _, _ ->
                if (pane == "p1") first.lease
                else if (replacementAttempts++ == 0) throw IOException("attach failed")
                else second.lease
            },
            scope = vmScope,
            ioDispatcher = Dispatchers.IO,
        )
        withTimeout(1_000) { while (viewModel.uiState.value.attachmentKey?.paneId != "p1") yield() }

        viewModel.focusTab("work", "t2").join()
        state.value = ConnectionState.Connected(7, mapOf("work" to session(focusedTab = "t2", focusedPane = "p2")))
        withTimeout(1_000) { while (viewModel.uiState.value.switchingTerminal) yield() }

        assertEquals("p1", viewModel.uiState.value.attachmentKey?.paneId)
        assertEquals(0, first.closeCount.get())
        assertEquals(true, viewModel.uiState.value.switcherOpen)
        assertEquals("attach failed", viewModel.uiState.value.message)

        viewModel.focusTab("work", "t2").join()
        withTimeout(1_000) { while (viewModel.uiState.value.attachmentKey?.paneId != "p2") yield() }

        viewModel.close()
        vmScope.cancel()
    }

    @Test
    fun `already focused target stops loading after its action succeeds`() = runBlocking {
        val vmScope = CoroutineScope(coroutineContext + SupervisorJob())
        val viewModel = TerminalViewModel(
            state = MutableStateFlow(ConnectionState.Connected(7, mapOf("work" to session()))),
            actions = hierarchyCommands(),
            attach = { _, _, _, _, _ -> null },
            scope = vmScope,
            ioDispatcher = Dispatchers.IO,
        )
        yield()

        viewModel.focusTab("work", "t1").join()

        withTimeout(1_000) { while (viewModel.uiState.value.switchingTerminal) yield() }
        assertEquals(false, viewModel.uiState.value.switchingTerminal)
        vmScope.cancel()
    }

    @Test
    fun `latest switch ignores an older authoritative focus`() = runBlocking {
        val vmScope = CoroutineScope(coroutineContext + SupervisorJob())
        val state = MutableStateFlow<ConnectionState>(
            ConnectionState.Connected(7, mapOf("work" to sessionWithSecondSpace())),
        )
        val attached = mutableListOf<String>()
        val viewModel = TerminalViewModel(
            state = state,
            actions = hierarchyCommands(),
            attach = { _, pane, _, _, _ -> attached += pane; null },
            scope = vmScope,
            ioDispatcher = Dispatchers.IO,
        )
        withTimeout(1_000) { while (attached.isEmpty()) yield() }

        viewModel.focusTab("work", "t2").join()
        viewModel.focusSpace("work", "w2").join()
        state.value = ConnectionState.Connected(7, mapOf("work" to session(focusedTab = "t2", focusedPane = "p2")))
        repeat(10) { yield() }

        assertEquals(listOf("p1"), attached)
        assertEquals(true, viewModel.uiState.value.switchingTerminal)
        state.value = ConnectionState.Connected(7, mapOf("work" to sessionWithSecondSpace(focusSecond = true)))
        withTimeout(1_000) { while (attached.last() != "p3") yield() }
        withTimeout(1_000) { while (viewModel.uiState.value.switchingTerminal) yield() }
        assertEquals(false, viewModel.uiState.value.switchingTerminal)
        vmScope.cancel()
    }

    @Test
    fun `tab gestures wrap within the focused space and use Herdr focus`() = runBlocking {
        val vmScope = CoroutineScope(coroutineContext + SupervisorJob())
        val methods = java.util.Collections.synchronizedList(mutableListOf<String>())
        val viewModel = TerminalViewModel(
            state = MutableStateFlow(ConnectionState.Connected(7, mapOf("work" to session()))),
            actions = hierarchyCommands { request ->
                methods += request
                ActionOutcome.Succeeded
            },
            attach = { _, _, _, _, _ -> null },
            scope = vmScope,
            ioDispatcher = Dispatchers.IO,
        )

        viewModel.previousTab()
        viewModel.nextTab()
        withTimeout(1_000) { while (methods.size < 2) yield() }

        assertEquals(
            listOf(
                "tab.focus:work:t2",
                "tab.focus:work:t2",
            ),
            methods,
        )
        vmScope.cancel()
    }

    @Test
    fun `destructive close requires native confirmation and host confirmation is not retried`() = runBlocking {
        val vmScope = CoroutineScope(coroutineContext + SupervisorJob())
        var attempts = 0
        val viewModel = TerminalViewModel(
            state = MutableStateFlow(ConnectionState.Connected(7, mapOf("work" to session()))),
            actions = hierarchyCommands {
                attempts++
                ActionOutcome.HostConfirmationRequired("Confirm on host")
            },
            attach = { _, _, _, _, _ -> null },
            scope = vmScope,
            ioDispatcher = Dispatchers.IO,
        )

        viewModel.requestClose(HierarchyTarget.Space("work", "w1", "Space one"))
        assertEquals("Space one", viewModel.uiState.value.pendingClose?.label)
        assertEquals(0, attempts)
        viewModel.cancelClose()
        assertNull(viewModel.uiState.value.pendingClose)

        viewModel.requestClose(HierarchyTarget.Space("work", "w1", "Space one"))
        viewModel.confirmClose().join()

        assertEquals(1, attempts)
        assertEquals(
            "Herdr opened a worktree confirmation prompt on the host. Complete it there; Herdroid did not retry.",
            viewModel.uiState.value.message,
        )
        vmScope.cancel()
    }

    @Test
    fun `create waits for the new authoritative focus then opens its terminal`() = runBlocking {
        val vmScope = CoroutineScope(coroutineContext + SupervisorJob())
        val state = MutableStateFlow<ConnectionState>(ConnectionState.Connected(7, mapOf("work" to session())))
        val attached = mutableListOf<String>()
        val first = ClientHarness()
        val second = ClientHarness()
        val viewModel = TerminalViewModel(
            state = state,
            actions = hierarchyCommands(),
            attach = { session, pane, _, _, _ ->
                attached += "$session:$pane"
                if (pane == "p1") first.lease else second.lease
            },
            scope = vmScope,
            ioDispatcher = Dispatchers.IO,
        )
        withTimeout(1_000) { while (attached.isEmpty()) yield() }
        attached.clear()
        viewModel.openSwitcher()

        viewModel.createTab("work", "w1").join()
        assertEquals(true, viewModel.uiState.value.switcherOpen)
        state.value = ConnectionState.Connected(7, mapOf("work" to session(focusedTab = "t2", focusedPane = "p2")))
        withTimeout(1_000) { while (attached.isEmpty()) yield() }
        withTimeout(1_000) { while (viewModel.uiState.value.switcherOpen) yield() }

        assertEquals(listOf("work:p2"), attached)
        assertEquals(false, viewModel.uiState.value.switcherOpen)
        viewModel.close()
        vmScope.cancel()
    }

    @Test
    fun `reconnect reattaches the same authoritative pane`() = runBlocking {
        val vmScope = CoroutineScope(coroutineContext + SupervisorJob())
        val state = MutableStateFlow<ConnectionState>(ConnectionState.Connected(7, mapOf("work" to session())))
        val attached = mutableListOf<String>()
        val viewModel = TerminalViewModel(
            state = state,
            actions = unavailableActions(),
            attach = { session, pane, _, _, _ -> attached += "$session:$pane"; null },
            scope = vmScope,
            ioDispatcher = Dispatchers.IO,
        )
        withTimeout(1_000) { while (attached.size < 1) yield() }
        state.value = ConnectionState.Reconnecting(7, 1)
        withTimeout(1_000) { while (viewModel.uiState.value.sessions.isNotEmpty()) yield() }
        state.value = ConnectionState.Connected(7, mapOf("work" to session()))
        withTimeout(1_000) { while (attached.size < 2) yield() }

        assertEquals(listOf("work:p1", "work:p1"), attached)
        vmScope.cancel()
    }

    @Test
    fun `unavailable hierarchy reports exact error and runs only the failure path`() = runBlocking {
        val vmScope = CoroutineScope(coroutineContext + SupervisorJob())
        val viewModel = TerminalViewModel(
            state = MutableStateFlow(ConnectionState.Connected(7, mapOf("work" to session()))),
            actions = unavailableActions(),
            attach = { _, _, _, _, _ -> null },
            scope = vmScope,
            ioDispatcher = Dispatchers.IO,
        )
        viewModel.openSwitcher()

        viewModel.focusPane("work", "p1").join()

        assertEquals("The Herdr connection is not ready.", viewModel.uiState.value.message)
        assertEquals(false, viewModel.uiState.value.switchingTerminal)
        assertEquals(true, viewModel.uiState.value.switcherOpen)
        vmScope.cancel()
    }

    @Test
    fun `hierarchy cancellation is rethrown unchanged without failure side effects`() = runBlocking {
        val vmScope = CoroutineScope(coroutineContext + SupervisorJob())
        val cancellation = CancellationException("cancel hierarchy")
        val viewModel = TerminalViewModel(
            state = MutableStateFlow(ConnectionState.Connected(7, mapOf("work" to session()))),
            actions = hierarchyCommands { throw cancellation },
            attach = { _, _, _, _, _ -> null },
            scope = vmScope,
            ioDispatcher = Dispatchers.Unconfined,
        )
        viewModel.openSwitcher()

        val action = viewModel.focusPane("work", "p2")
        val completion = CompletableDeferred<Throwable?>()
        action.invokeOnCompletion { completion.complete(it) }
        action.join()

        assertSame(cancellation, completion.await())
        assertEquals(true, viewModel.uiState.value.switchingTerminal)
        assertEquals(false, viewModel.uiState.value.switcherOpen)
        assertNull(viewModel.uiState.value.message)
        vmScope.cancel()
    }

    @Test
    fun `create still dismisses when authoritative focus arrives before the response`() = runBlocking {
        val vmScope = CoroutineScope(coroutineContext + SupervisorJob())
        val state = MutableStateFlow<ConnectionState>(ConnectionState.Connected(7, mapOf("work" to session())))
        val response = CompletableDeferred<Unit>()
        val first = ClientHarness()
        val second = ClientHarness()
        val viewModel = TerminalViewModel(
            state = state,
            actions = hierarchyCommands {
                response.await()
                ActionOutcome.Succeeded
            },
            attach = { _, pane, _, _, _ -> if (pane == "p1") first.lease else second.lease },
            scope = vmScope,
            ioDispatcher = Dispatchers.IO,
        )
        yield()
        viewModel.openSwitcher()
        val action = viewModel.createSpace("work")
        yield()
        state.value = ConnectionState.Connected(7, mapOf("work" to session(focusedTab = "t2", focusedPane = "p2")))
        yield()
        response.complete(Unit)
        action.join()
        withTimeout(1_000) { while (viewModel.uiState.value.switcherOpen) yield() }

        assertEquals(false, viewModel.uiState.value.switcherOpen)
        viewModel.close()
        vmScope.cancel()
    }

    @Test
    fun `space selection opens its authoritative focused terminal and dismisses the switcher`() = runBlocking {
        val vmScope = CoroutineScope(coroutineContext + SupervisorJob())
        val state = MutableStateFlow<ConnectionState>(
            ConnectionState.Connected(7, mapOf("work" to sessionWithSecondSpace())),
        )
        val requests = mutableListOf<String>()
        val attached = mutableListOf<String>()
        val viewModel = TerminalViewModel(
            state = state,
            actions = hierarchyCommands { request ->
                requests += request
                ActionOutcome.Succeeded
            },
            attach = { session, pane, _, _, _ -> attached += "$session:$pane"; null },
            scope = vmScope,
            ioDispatcher = Dispatchers.IO,
        )
        withTimeout(1_000) { while (attached.isEmpty()) yield() }
        attached.clear()
        viewModel.openSwitcher()

        viewModel.focusSpace("work", "w2").join()
        assertEquals(false, viewModel.uiState.value.switcherOpen)
        assertEquals(true, viewModel.uiState.value.switchingTerminal)
        state.value = ConnectionState.Connected(7, mapOf("work" to sessionWithSecondSpace(focusSecond = true)))
        withTimeout(1_000) { while (attached.isEmpty()) yield() }
        withTimeout(1_000) { while (viewModel.uiState.value.switcherOpen) yield() }

        assertEquals(listOf("workspace.focus:work:w2"), requests)
        assertEquals(listOf("work:p3"), attached)
        vmScope.cancel()
    }

    @Test
    fun `new focus waits for the older attach and never opens controllers concurrently`() = runBlocking {
        val vmScope = CoroutineScope(coroutineContext + SupervisorJob())
        val state = MutableStateFlow<ConnectionState>(
            ConnectionState.Connected(7, mapOf("work" to session(), "other" to session())),
        )
        val firstAttach = CompletableDeferred<Unit>()
        val started = mutableListOf<String>()
        val viewModel = TerminalViewModel(
            state = state,
            actions = unavailableActions(),
            attach = { selectedSession, pane, _, _, _ ->
                started += "$selectedSession:$pane"
                if (selectedSession == "work") firstAttach.await()
                null
            },
            scope = vmScope,
            ioDispatcher = Dispatchers.IO,
        )
        withTimeout(1_000) { while (started.isEmpty()) yield() }

        viewModel.selectSession("other")
        repeat(10) { yield() }
        assertEquals(listOf("work:p1"), started)
        firstAttach.complete(Unit)
        withTimeout(1_000) { while (started.size < 2) yield() }
        assertEquals(listOf("work:p1", "other:p1"), started)
        vmScope.cancel()
    }

    @Test
    fun `replacement during blocked attach releases the stale result once and never publishes it`() = runBlocking {
        val vmScope = CoroutineScope(coroutineContext + SupervisorJob())
        val state = MutableStateFlow<ConnectionState>(ConnectionState.Connected(7, mapOf("work" to session())))
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val client = ClientHarness()
        val viewModel = TerminalViewModel(
            state = state,
            actions = unavailableActions(),
            attach = { _, _, _, _, _ -> started.complete(Unit); finish.await(); client.lease },
            scope = vmScope,
            ioDispatcher = Dispatchers.IO,
        )
        started.await()

        viewModel.close()
        val replacementAttached = CompletableDeferred<Unit>()
        val replacement = TerminalViewModel(
            state = state,
            actions = unavailableActions(),
            attach = { _, _, _, _, _ -> replacementAttached.complete(Unit); null },
            scope = vmScope,
            ioDispatcher = Dispatchers.IO,
        )
        replacementAttached.await()
        finish.complete(Unit)
        withTimeout(1_000) { while (client.closeCount.get() != 1) yield() }

        assertNull(viewModel.uiState.value.attachmentKey)
        assertEquals(1, client.closeCount.get())
        replacement.close()
        vmScope.cancel()
    }

    @Test
    fun `missing focused pane releases the current terminal`() = runBlocking {
        val vmScope = CoroutineScope(coroutineContext + SupervisorJob())
        val state = MutableStateFlow<ConnectionState>(ConnectionState.Connected(7, mapOf("work" to session())))
        val client = ClientHarness()
        val viewModel = TerminalViewModel(
            state = state,
            actions = unavailableActions(),
            attach = { _, _, _, _, _ -> client.lease },
            scope = vmScope,
            ioDispatcher = Dispatchers.IO,
        )
        withTimeout(1_000) { while (viewModel.uiState.value.attachmentKey == null) yield() }

        state.value = ConnectionState.Connected(
            7,
            mapOf("work" to session().copy(focusedPaneId = null)),
        )
        withTimeout(1_000) { while (client.closeCount.get() != 1) yield() }

        assertNull(viewModel.uiState.value.attachmentKey)
        vmScope.cancel()
    }

    @Test
    fun `new session epoch reattaches reused pane id after releasing old controller`() = runBlocking {
        val vmScope = CoroutineScope(coroutineContext + SupervisorJob())
        val state = MutableStateFlow<ConnectionState>(ConnectionState.Connected(7, mapOf("work" to session(epoch = "e1"))))
        val first = ClientHarness()
        val second = ClientHarness()
        var attempts = 0
        val viewModel = TerminalViewModel(
            state = state,
            actions = unavailableActions(),
            attach = { _, _, _, _, _ -> if (attempts++ == 0) first.lease else second.lease },
            scope = vmScope,
            ioDispatcher = Dispatchers.IO,
        )
        withTimeout(1_000) { while (viewModel.uiState.value.attachmentKey?.sessionEpoch != "e1") yield() }

        state.value = ConnectionState.Connected(7, mapOf("work" to session(epoch = "e2")))
        withTimeout(1_000) { while (viewModel.uiState.value.attachmentKey?.sessionEpoch != "e2") yield() }
        withTimeout(1_000) { while (first.closeCount.get() != 1) yield() }

        assertEquals(2, attempts)
        assertEquals(1, first.closeCount.get())
        viewModel.close()
        withTimeout(1_000) { while (second.closeCount.get() != 1) yield() }
        vmScope.cancel()
    }

    @Test
    fun `create dismissal is scoped to its requested session and prior focus`() = runBlocking {
        val vmScope = CoroutineScope(coroutineContext + SupervisorJob())
        val state = MutableStateFlow<ConnectionState>(
            ConnectionState.Connected(7, mapOf("work" to session(), "other" to session())),
        )
        val attached = java.util.concurrent.CopyOnWriteArrayList<String>()
        val viewModel = TerminalViewModel(
            state = state,
            actions = hierarchyCommands(),
            attach = { selected, pane, _, _, _ -> attached += "$selected:$pane"; null },
            scope = vmScope,
            ioDispatcher = Dispatchers.IO,
        )
        withTimeout(1_000) { while (attached.isEmpty()) yield() }
        viewModel.openSwitcher()
        viewModel.createSpace("work").join()

        viewModel.selectSession("other")
        withTimeout(1_000) { while (attached.none { it.startsWith("other:") }) yield() }
        assertEquals(false, viewModel.uiState.value.switcherOpen)

        state.value = ConnectionState.Connected(
            7,
            mapOf("work" to session(focusedTab = "t2", focusedPane = "p2"), "other" to session()),
        )
        withTimeout(1_000) { while (viewModel.uiState.value.selectedSession != "work") yield() }
        assertEquals("work", viewModel.uiState.value.selectedSession)
        vmScope.cancel()
    }

    @Test
    fun `every hierarchy operation forwards exactly once in order`() = runBlocking {
        val vmScope = CoroutineScope(coroutineContext + SupervisorJob())
        val requests = mutableListOf<String>()
        val viewModel = TerminalViewModel(
            state = MutableStateFlow(ConnectionState.Connected(7, mapOf("work" to session()))),
            actions = hierarchyCommands { request ->
                requests += request
                ActionOutcome.Succeeded
            },
            attach = { _, _, _, _, _ -> null },
            scope = vmScope,
            ioDispatcher = Dispatchers.IO,
        )

        viewModel.createSpace("work").join()
        viewModel.focusSpace("work", "w1").join()
        viewModel.rename(HierarchyTarget.Space("work", "w1", "Space one"), "Space renamed").join()
        viewModel.requestClose(HierarchyTarget.Space("work", "w1", "Space one"))
        viewModel.confirmClose().join()
        viewModel.createTab("work", "w1").join()
        viewModel.focusTab("work", "t1").join()
        viewModel.rename(HierarchyTarget.Tab("work", "t1", "Tab one"), "Tab renamed").join()
        viewModel.requestClose(HierarchyTarget.Tab("work", "t1", "Tab one"))
        viewModel.confirmClose().join()
        viewModel.focusPane("work", "p1").join()
        viewModel.splitPane("work", "p1", dev.herdroid.core.model.SplitDirection.Right).join()
        viewModel.splitPane("work", "p1", dev.herdroid.core.model.SplitDirection.Down).join()
        viewModel.zoomPane("work", "p1").join()
        viewModel.rename(HierarchyTarget.Pane("work", "p1", "Pane one"), "Pane renamed").join()
        viewModel.requestClose(HierarchyTarget.Pane("work", "p1", "Pane one"))
        viewModel.confirmClose().join()

        assertEquals(
            listOf(
                "workspace.create:work:null:null:{}",
                "workspace.focus:work:w1",
                "workspace.rename:work:w1:Space renamed",
                "workspace.close:work:w1",
                "tab.create:work:w1:null:null:{}",
                "tab.focus:work:t1",
                "tab.rename:work:t1:Tab renamed",
                "tab.close:work:t1",
                "pane.focus:work:p1",
                "pane.split:work:p1:Right:null:null:{}",
                "pane.split:work:p1:Down:null:null:{}",
                "pane.zoom:work:p1:Toggle",
                "pane.rename:work:p1:Pane renamed",
                "pane.close:work:p1",
            ),
            requests,
        )
        viewModel.close()
        vmScope.cancel()
    }

    @Test
    fun `agent copy maps only stock states and preserves reduced coverage`() {
        assertEquals("running", agentStatusLabel(AgentStatus.Working))
        assertEquals("waiting", agentStatusLabel(AgentStatus.Blocked))
        assertEquals("done", agentStatusLabel(AgentStatus.Done))
        assertEquals("idle", agentStatusLabel(AgentStatus.Idle))
        assertEquals("unknown", agentStatusLabel(AgentStatus.Unknown))
        assertEquals("waiting · reduced live coverage", paneStatusLabel(AgentStatus.Blocked, uncovered = true))
    }

    private fun hierarchyCommands(
        onCall: suspend (String) -> ActionOutcome = { ActionOutcome.Succeeded },
    ): HierarchyCommands = object : HierarchyCommands {
        override suspend fun createWorkspace(
            sessionId: String,
            cwd: String?,
            label: String?,
            env: Map<String, String>,
        ) = onCall("workspace.create:$sessionId:$cwd:$label:$env")

        override suspend fun focusWorkspace(sessionId: String, workspaceId: String) =
            onCall("workspace.focus:$sessionId:$workspaceId")

        override suspend fun renameWorkspace(sessionId: String, workspaceId: String, label: String) =
            onCall("workspace.rename:$sessionId:$workspaceId:$label")

        override suspend fun closeWorkspace(sessionId: String, workspaceId: String) =
            onCall("workspace.close:$sessionId:$workspaceId")

        override suspend fun createTab(
            sessionId: String,
            workspaceId: String?,
            cwd: String?,
            label: String?,
            env: Map<String, String>,
        ) = onCall("tab.create:$sessionId:$workspaceId:$cwd:$label:$env")

        override suspend fun focusTab(sessionId: String, tabId: String) = onCall("tab.focus:$sessionId:$tabId")
        override suspend fun renameTab(sessionId: String, tabId: String, label: String) =
            onCall("tab.rename:$sessionId:$tabId:$label")

        override suspend fun closeTab(sessionId: String, tabId: String) = onCall("tab.close:$sessionId:$tabId")
        override suspend fun focusPane(sessionId: String, paneId: String) = onCall("pane.focus:$sessionId:$paneId")

        override suspend fun splitPane(
            sessionId: String,
            paneId: String,
            direction: SplitDirection,
            ratio: Double?,
            cwd: String?,
            env: Map<String, String>,
        ) = onCall("pane.split:$sessionId:$paneId:$direction:$ratio:$cwd:$env")

        override suspend fun zoomPane(sessionId: String, paneId: String?, mode: ZoomMode) =
            onCall("pane.zoom:$sessionId:$paneId:$mode")

        override suspend fun renamePane(sessionId: String, paneId: String, label: String) =
            onCall("pane.rename:$sessionId:$paneId:$label")

        override suspend fun closePane(sessionId: String, paneId: String) = onCall("pane.close:$sessionId:$paneId")
    }

    private fun session(
        focusedTab: String = "t1",
        focusedPane: String = "p1",
        epoch: String = "e1",
    ) = SessionState(
        epoch = epoch,
        workspaces = mapOf("w1" to Workspace("w1", 1, "Space one", true, 2, 2, focusedTab, AgentStatus.Blocked)),
        tabs = mapOf(
            "t1" to Tab("t1", "w1", 1, "Tab one", focusedTab == "t1", 1, AgentStatus.Working),
            "t2" to Tab("t2", "w1", 2, "Tab two", focusedTab == "t2", 1, AgentStatus.Done),
        ),
        panes = mapOf(
            "p1" to Pane("p1", "term1", "w1", "t1", focusedPane == "p1", label = "Pane one", agentStatus = AgentStatus.Blocked),
            "p2" to Pane("p2", "term2", "w1", "t2", focusedPane == "p2", label = "Pane two", agentStatus = AgentStatus.Done),
        ),
        focusedWorkspaceId = "w1",
        focusedTabId = focusedTab,
        focusedPaneId = focusedPane,
        uncoveredAgentPaneIds = setOf("p1"),
    )

    private fun sessionWithSecondSpace(focusSecond: Boolean = false): SessionState {
        val base = session()
        return base.copy(
            workspaces = base.workspaces + (
                "w2" to Workspace("w2", 2, "Space two", focusSecond, 1, 1, "t3", AgentStatus.Idle)
            ),
            tabs = base.tabs + (
                "t3" to Tab("t3", "w2", 1, "Tab three", focusSecond, 1, AgentStatus.Idle)
            ),
            panes = base.panes + (
                "p3" to Pane("p3", "term3", "w2", "t3", focusSecond, label = "Pane three", agentStatus = AgentStatus.Idle)
            ),
            focusedWorkspaceId = if (focusSecond) "w2" else "w1",
            focusedTabId = if (focusSecond) "t3" else "t1",
            focusedPaneId = if (focusSecond) "p3" else "p1",
        )
    }

    private class ClientHarness {
        val closeCount = AtomicInteger()
        private val closed = AtomicBoolean()
        val lease = object : TerminalLease {
            override val state = MutableStateFlow<TerminalState>(TerminalState.Interactive(80, 24, 1))
            override val frames: Flow<TerminalFrame> = emptyFlow()
            override fun sendText(text: String) = Unit
            override fun sendBytes(bytes: ByteArray) = Unit
            override fun resize(cols: Int, rows: Int, cellWidthPx: Int, cellHeightPx: Int) = Unit
            override fun scroll(
                direction: TerminalScrollDirection,
                lines: Int,
                source: TerminalScrollSource,
                column: Int?,
                row: Int?,
                modifiers: Int,
            ) = Unit
            override fun close() {
                if (closed.compareAndSet(false, true)) closeCount.incrementAndGet()
            }
        }

    }

    private class RecordingLease(private var sendFailure: Throwable? = null) : TerminalLease {
        private val frameChannel = Channel<TerminalFrame>(Channel.UNLIMITED)
        override val state = MutableStateFlow<TerminalState>(TerminalState.Interactive(80, 24, 1))
        override val frames: Flow<TerminalFrame> = frameChannel.receiveAsFlow()
        val commands = mutableListOf<String>()
        val closeCount = AtomicInteger()
        override fun sendText(text: String) {
            sendFailure?.let { failure ->
                sendFailure = null
                throw failure
            }
            commands += "text:$text"
        }
        override fun sendBytes(bytes: ByteArray) { commands += "bytes:${bytes.toList()}" }
        override fun resize(cols: Int, rows: Int, cellWidthPx: Int, cellHeightPx: Int) {
            commands += "resize:$cols:$rows"
        }
        override fun scroll(
            direction: TerminalScrollDirection,
            lines: Int,
            source: TerminalScrollSource,
            column: Int?,
            row: Int?,
            modifiers: Int,
        ) {
            commands += "scroll:$direction:$lines:$source"
        }
        override fun close() { closeCount.incrementAndGet() }

        fun emit(frame: TerminalFrame) = check(frameChannel.trySend(frame).isSuccess)
    }

    private fun unavailableActions(): HierarchyCommands = Proxy.newProxyInstance(
        HierarchyCommands::class.java.classLoader,
        arrayOf(HierarchyCommands::class.java),
    ) { _, _, _ -> throw IllegalStateException("The Herdr connection is not ready.") } as HierarchyCommands
}
