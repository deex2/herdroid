package dev.herdroid.feature.connections

import androidx.lifecycle.SavedStateHandle
import dev.herdroid.core.data.EditableEndpoint
import dev.herdroid.core.data.EditableRoute
import dev.herdroid.core.data.EndpointAuthenticationInput
import dev.herdroid.core.data.RouteRepository
import dev.herdroid.core.data.RouteWriteInput
import dev.herdroid.core.keyvault.DeleteKeyResult
import dev.herdroid.core.keyvault.KeyVault
import dev.herdroid.core.testing.FakeKeyVault
import dev.herdroid.core.testing.FakeRouteStore
import dev.herdroid.core.testing.testEditableRoute
import dev.herdroid.core.testing.testHardwareKeyMetadata
import dev.herdroid.core.model.HardwareKeyMetadata
import dev.herdroid.core.model.SavedRouteSummary
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionEditorViewModelTest {
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    @Test
    fun `failed save keeps draft and wipes both authentication buffers`() = runBlocking {
        lateinit var captured: RouteWriteInput
        val routes = object : RouteRepository {
            override val routes: Flow<List<SavedRouteSummary>> = MutableStateFlow(emptyList())
            override suspend fun findEditable(routeId: Long): EditableRoute? = null
            override suspend fun save(input: RouteWriteInput): Long {
                captured = input
                error("SQLCipher unavailable")
            }
            override suspend fun delete(routeId: Long) = Unit
        }
        val viewModel = editor(routes)
        val collecting = scope.launch { viewModel.uiState.collect() }
        viewModel.resume(null)
        viewModel.updateDraft(passwordDraft())

        viewModel.save {}.join()

        assertEquals("SQLCipher unavailable", viewModel.uiState.value.saveError)
        assertTrue(viewModel.uiState.value.draft != null)
        assertAuthenticationCleared(captured)
        collecting.cancel()
    }

    @Test
    fun `cancelled save wipes both authentication buffers`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val hold = CompletableDeferred<Unit>()
        lateinit var captured: RouteWriteInput
        val routes = object : RouteRepository {
            override val routes: Flow<List<SavedRouteSummary>> = MutableStateFlow(emptyList())
            override suspend fun findEditable(routeId: Long): EditableRoute? = null
            override suspend fun save(input: RouteWriteInput): Long {
                captured = input
                started.complete(Unit)
                hold.await()
                return 1
            }
            override suspend fun delete(routeId: Long) = Unit
        }
        val viewModel = editor(routes)
        val collecting = scope.launch { viewModel.uiState.collect() }
        viewModel.resume(null)
        viewModel.updateDraft(passwordDraft())

        val saving = viewModel.save {}
        started.await()
        saving.cancel()
        saving.join()

        assertTrue(saving.isCancelled)
        assertAuthenticationCleared(captured)
        collecting.cancel()
    }

    @Test
    fun `a suspended save owns the destination and ignores a second save`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val callbacks = AtomicInteger()
        val routes = object : RouteRepository {
            override val routes: Flow<List<SavedRouteSummary>> = MutableStateFlow(emptyList())
            override suspend fun findEditable(routeId: Long): EditableRoute? = null
            override suspend fun save(input: RouteWriteInput): Long {
                calls.incrementAndGet()
                started.complete(Unit)
                release.await()
                return 1
            }
            override suspend fun delete(routeId: Long) = Unit
        }
        val viewModel = editor(routes)
        val collecting = scope.launch { viewModel.uiState.collect() }
        viewModel.resume(null)
        viewModel.updateDraft(passwordDraft())

        val first = viewModel.save { callbacks.incrementAndGet() }
        started.await()
        val second = viewModel.save { callbacks.incrementAndGet() }
        yield()

        assertEquals(1, calls.get())
        release.complete(Unit)
        first.join()
        second.join()
        assertEquals(1, callbacks.get())
        collecting.cancel()
    }

    @Test
    fun `initial loading owns the destination and save cannot clear it`() = runBlocking {
        val loadStarted = CompletableDeferred<Unit>()
        val releaseLoad = CompletableDeferred<Unit>()
        val saveCalls = AtomicInteger()
        val routes = object : RouteRepository {
            override val routes: Flow<List<SavedRouteSummary>> = MutableStateFlow(emptyList())
            override suspend fun findEditable(routeId: Long): EditableRoute {
                loadStarted.complete(Unit)
                releaseLoad.await()
                return testEditableRoute(id = routeId)
            }
            override suspend fun save(input: RouteWriteInput): Long = saveCalls.incrementAndGet().toLong()
            override suspend fun delete(routeId: Long) = Unit
        }
        val viewModel = editor(routes)
        val collecting = scope.launch { viewModel.uiState.collect() }
        viewModel.resume(3)
        loadStarted.await()

        viewModel.save {}.join()

        assertEquals(0, saveCalls.get())
        assertTrue(viewModel.uiState.value.loading)
        assertNull(viewModel.uiState.value.saveError)
        releaseLoad.complete(Unit)
        withTimeout(1_000) {
            while (viewModel.uiState.value.loading) yield()
        }
        collecting.cancel()
    }

    @Test
    fun `loading an editable route never prepopulates password`() = runBlocking {
        val routes = FakeRouteStore().apply {
            seedEditable(
                EditableRoute(
                    3,
                    "office",
                    EditableEndpoint("target", 22, "sam", keyId = null, herdrPath = null),
                    null,
                ),
            )
        }
        val viewModel = editor(routes)
        val collecting = scope.launch { viewModel.uiState.collect() }

        viewModel.resume(3)

        assertEquals("office", viewModel.uiState.value.draft?.name)
        assertEquals("", viewModel.uiState.value.draft?.target?.password)
        collecting.cancel()
    }

    @Test
    fun `duplicate opens an unsaved copy without passwords`() = runBlocking {
        val routes = FakeRouteStore().apply {
            seedEditable(
                testEditableRoute(
                    id = 3,
                    name = "office",
                    target = EditableEndpoint("target", 22, "sam", keyId = 7, herdrPath = "/opt/herdr"),
                    jump = EditableEndpoint("jump", 2200, "proxy", keyId = null, herdrPath = null),
                ),
            )
        }
        val viewModel = editor(routes)
        val collecting = scope.launch { viewModel.uiState.collect() }

        viewModel.resume(3, SavedStateHandle(mapOf("duplicate" to true)))

        val copy = requireNotNull(viewModel.uiState.value.draft)
        assertEquals(0L, copy.id)
        assertEquals("Copy of office", copy.name)
        assertEquals(7L, copy.target.keyId)
        assertEquals("", copy.target.password)
        assertEquals("jump", copy.jump?.hostname)
        assertEquals("", copy.jump?.password)
        collecting.cancel()
    }

    @Test
    fun `delete removes the route and clears the editor`() = runBlocking {
        val routes = FakeRouteStore().apply { seedEditable(testEditableRoute(id = 3)) }
        val viewModel = editor(routes)
        val collecting = scope.launch { viewModel.uiState.collect() }
        var deleted = false
        viewModel.resume(3)
        viewModel.requestDelete()

        viewModel.delete { deleted = true }.join()

        assertEquals(1, routes.deleteCalls.get())
        assertNull(routes.findEditable(3))
        assertNull(viewModel.uiState.value.draft)
        assertFalse(viewModel.uiState.value.confirmDelete)
        assertTrue(deleted)
        collecting.cancel()
    }

    @Test
    fun `a suspended delete owns the destination and ignores a second delete`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val callbacks = AtomicInteger()
        val routes = object : RouteRepository {
            override val routes: Flow<List<SavedRouteSummary>> = MutableStateFlow(emptyList())
            override suspend fun findEditable(routeId: Long): EditableRoute? = testEditableRoute(id = routeId)
            override suspend fun save(input: RouteWriteInput): Long = error("unused")
            override suspend fun delete(routeId: Long) {
                calls.incrementAndGet()
                started.complete(Unit)
                release.await()
            }
        }
        val viewModel = editor(routes)
        val collecting = scope.launch { viewModel.uiState.collect() }
        viewModel.resume(3)

        val first = viewModel.delete { callbacks.incrementAndGet() }
        started.await()
        val second = viewModel.delete { callbacks.incrementAndGet() }
        yield()

        assertEquals(1, calls.get())
        release.complete(Unit)
        first.join()
        second.join()
        assertEquals(1, callbacks.get())
        collecting.cancel()
    }

    @Test
    fun `created key result is removed and applied once to requested endpoint`() = runBlocking {
        val handle = SavedStateHandle()
        val viewModel = ConnectionEditorViewModel(
            handle,
            FakeRouteStore(),
            FakeKeyVault(listOf(testHardwareKeyMetadata(id = 7))),
            scope,
        )
        val collecting = scope.launch { viewModel.uiState.collect() }
        viewModel.resume(null)
        viewModel.requestCreatedKey(forTarget = true)

        handle[CREATED_KEY_ID] = 7L
        viewModel.resume(null)

        assertEquals(7L, viewModel.uiState.value.draft?.target?.keyId)
        assertEquals(emptySet<String>(), handle.keys())
        collecting.cancel()
    }

    @Test
    fun `editor pause transfers key flow ownership before created key return`() = runBlocking {
        val holdEditor = CompletableDeferred<Unit>()
        val holdManager = CompletableDeferred<Unit>()
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val subscriptions = AtomicInteger()
        val key = testHardwareKeyMetadata(id = 7, name = "phone")
        val keyVault = keysOnlyVault(
            flow {
                val subscription = subscriptions.incrementAndGet()
                val collectors = active.incrementAndGet()
                maximum.updateAndGet { maxOf(it, collectors) }
                try {
                    when (subscription) {
                        1 -> holdEditor.await()
                        2 -> {
                            emit(emptyList())
                            holdManager.await()
                        }
                        3 -> emit(listOf(key))
                        else -> error("Unexpected key subscription $subscription")
                    }
                } finally {
                    active.decrementAndGet()
                }
            },
        )
        val handle = SavedStateHandle()
        val viewModel = ConnectionEditorViewModel(handle, FakeRouteStore(), keyVault, scope)
        val collecting = scope.launch { viewModel.uiState.collect() }
        viewModel.resume(null)
        viewModel.requestCreatedKey(forTarget = true)
        yield()
        val manager = scope.launch { keyVault.keys.collect() }
        try {
            assertEquals(1, maximum.get())
            assertEquals(2, subscriptions.get())
            manager.cancel()
            manager.join()
            handle[CREATED_KEY_ID] = 7L

            viewModel.resume(null)

            withTimeout(1_000) {
                while (viewModel.uiState.value.draft?.target?.keyId != 7L) yield()
            }
            assertEquals(1, maximum.get())
            assertEquals(3, subscriptions.get())
            assertFalse(handle.contains(CREATED_KEY_ID))
        } finally {
            manager.cancel()
            holdEditor.complete(Unit)
            holdManager.complete(Unit)
            collecting.cancel()
        }
    }

    @Test
    fun `cancelled resume preserves created key result for replacement snapshot`() = runBlocking {
        val cancelled = CancellationException("snapshot cancelled")
        val key = testHardwareKeyMetadata(id = 7, name = "phone")
        var subscriptions = 0
        val handle = SavedStateHandle().apply { this[CREATED_KEY_ID] = 7L }
        val viewModel = ConnectionEditorViewModel(
            handle,
            FakeRouteStore(),
            keysOnlyVault(
                flow {
                    if (++subscriptions == 1) throw cancelled
                    emit(listOf(key))
                },
            ),
            scope,
        )
        val collecting = scope.launch { viewModel.uiState.collect() }
        viewModel.requestCreatedKey(forTarget = true)

        viewModel.resume(null)

        assertTrue(handle.contains(CREATED_KEY_ID))
        viewModel.resume(null)
        withTimeout(1_000) {
            while (viewModel.uiState.value.draft?.target?.keyId != 7L) yield()
        }
        assertFalse(handle.contains(CREATED_KEY_ID))
        assertEquals(2, subscriptions)
        collecting.cancel()
    }

    @Test
    fun `superseding resume rejects old key navigation handoff`() = runBlocking {
        val cancellationStarted = CompletableDeferred<Unit>()
        val releaseCancellation = CompletableDeferred<Unit>()
        val replacementStarted = CompletableDeferred<Unit>()
        var subscriptions = 0
        val viewModel = ConnectionEditorViewModel(
            SavedStateHandle(),
            FakeRouteStore(),
            keysOnlyVault(
                flow {
                    when (++subscriptions) {
                        1 -> try {
                            awaitCancellation()
                        } finally {
                            withContext(NonCancellable) {
                                cancellationStarted.complete(Unit)
                                releaseCancellation.await()
                            }
                        }
                        2 -> {
                            replacementStarted.complete(Unit)
                            emit(emptyList())
                        }
                        else -> error("Unexpected key subscription $subscriptions")
                    }
                },
            ),
            scope,
        )
        val collecting = scope.launch { viewModel.uiState.collect() }
        viewModel.resume(null)

        val handoff = async { viewModel.requestCreatedKey(forTarget = true) }
        cancellationStarted.await()
        assertFalse(viewModel.requestCreatedKey(forTarget = false))
        viewModel.resume(null)
        releaseCancellation.complete(Unit)
        replacementStarted.await()

        assertFalse(handoff.await())
        viewModel.pause()
        collecting.cancel()
    }

    @Test
    fun `cancelled created key result remains paired with requested endpoint`() = runBlocking {
        val key = testHardwareKeyMetadata(id = 7, name = "phone")
        var subscriptions = 0
        val handle = SavedStateHandle()
        val viewModel = ConnectionEditorViewModel(
            handle,
            FakeRouteStore(),
            keysOnlyVault(
                flow {
                    when (++subscriptions) {
                        1 -> emit(emptyList())
                        2 -> throw CancellationException("snapshot cancelled")
                        3 -> emit(listOf(key))
                        else -> error("Unexpected key subscription $subscriptions")
                    }
                },
            ),
            scope,
        )
        val collecting = scope.launch { viewModel.uiState.collect() }
        viewModel.resume(null)
        viewModel.updateDraft(
            RouteDraft(
                target = EndpointDraft("target", "22", "sam"),
                jump = EndpointDraft("jump", "22", "proxy"),
            ),
        )
        viewModel.requestCreatedKey(forTarget = true)
        handle[CREATED_KEY_ID] = 7L

        viewModel.resume(null)

        assertTrue(handle.contains(CREATED_KEY_ID))
        assertFalse(viewModel.requestCreatedKey(forTarget = false))
        viewModel.resume(null)

        assertEquals(7L, viewModel.uiState.value.draft?.target?.keyId)
        assertNull(viewModel.uiState.value.draft?.jump?.keyId)
        assertFalse(handle.contains(CREATED_KEY_ID))
        collecting.cancel()
    }

    @Test
    fun `editor re-entry refreshes a renamed key`() = runBlocking {
        val keyVault = FakeKeyVault(listOf(testHardwareKeyMetadata(id = 7, name = "phone")))
        val viewModel = editor(FakeRouteStore(), keyVault)
        val collecting = scope.launch { viewModel.uiState.collect() }
        viewModel.resume(null)
        assertEquals("phone", viewModel.uiState.value.keys.single().name)

        keyVault.setKeys(listOf(testHardwareKeyMetadata(id = 7, name = "laptop")))
        viewModel.resume(null)

        assertEquals("laptop", viewModel.uiState.value.keys.single().name)
        collecting.cancel()
    }

    @Test
    fun `editor re-entry rejects save after selected key was deleted`() = runBlocking {
        val routes = FakeRouteStore()
        val keyVault = FakeKeyVault(listOf(testHardwareKeyMetadata(id = 7, name = "phone")))
        val viewModel = editor(routes, keyVault)
        val collecting = scope.launch { viewModel.uiState.collect() }
        viewModel.resume(null)
        viewModel.updateDraft(
            RouteDraft(
                name = "office",
                target = EndpointDraft("target", "22", "sam", keyId = 7),
            ),
        )

        keyVault.setKeys(emptyList())
        viewModel.resume(null)
        viewModel.save {}.join()

        assertEquals(0, routes.saveCalls.get())
        assertTrue(viewModel.uiState.value.saveError.orEmpty().contains("unavailable"))
        assertEquals(7L, viewModel.uiState.value.draft?.target?.keyId)
        collecting.cancel()
    }

    @Test
    fun `save awaits suspended re-entry snapshot before validating a deleted key`() = runBlocking {
        val refreshStarted = CompletableDeferred<Unit>()
        val releaseRefresh = CompletableDeferred<Unit>()
        val key = testHardwareKeyMetadata(id = 7, name = "phone")
        var subscriptions = 0
        val keyVault = keysOnlyVault(
            flow {
                subscriptions++
                if (subscriptions == 1) {
                    emit(listOf(key))
                } else {
                    refreshStarted.complete(Unit)
                    releaseRefresh.await()
                    emit(emptyList())
                }
            },
        )
        val routes = FakeRouteStore()
        val viewModel = editor(routes, keyVault)
        val collecting = scope.launch { viewModel.uiState.collect() }
        viewModel.resume(null)
        viewModel.updateDraft(
            RouteDraft(
                name = "office",
                target = EndpointDraft("target", "22", "sam", keyId = 7),
            ),
        )
        viewModel.resume(null)
        refreshStarted.await()

        val saving = viewModel.save {}
        try {
            assertEquals(0, routes.saveCalls.get())
            assertTrue(saving.isActive)
        } finally {
            releaseRefresh.complete(Unit)
        }
        saving.join()

        assertEquals(0, routes.saveCalls.get())
        assertTrue(viewModel.uiState.value.saveError.orEmpty().contains("unavailable"))
        assertEquals(7L, viewModel.uiState.value.draft?.target?.keyId)
        collecting.cancel()
    }

    @Test
    fun `route validation rejects missing keys relative paths and mixed credentials`() {
        val missing = RouteDraft(
            name = "office",
            target = EndpointDraft("target", "22", "sam", keyId = 99),
        )
        assertFailure("key") { missing.toRouteWriteInput() }

        val relative = RouteDraft(
            name = "office",
            target = EndpointDraft("target", "22", "sam", password = "secret", herdrPath = "bin/herdr"),
        )
        assertFailure("absolute") { relative.toRouteWriteInput() }

        val mixed = RouteDraft(
            name = "office",
            target = EndpointDraft("target", "22", "sam", password = "secret", keyId = 7),
        )
        assertFailure("either") { mixed.toRouteWriteInput() }
    }

    @Test
    fun `route draft redacts credentials from diagnostic text`() {
        val draft = RouteDraft(
            name = "office",
            target = EndpointDraft("target", "22", "sam", password = "never-log-this"),
        )

        assertTrue(draft.toString().contains("redacted"))
        assertFalse(draft.toString().contains("never-log-this"))
    }

    private fun editor(
        routes: RouteRepository,
        keys: KeyVault = FakeKeyVault(),
    ) = ConnectionEditorViewModel(SavedStateHandle(), routes, keys, scope)

    private fun keysOnlyVault(keys: Flow<List<HardwareKeyMetadata>>) =
        object : KeyVault {
            override val keys = keys
            override suspend fun generate(name: String) = error("unused")
            override suspend fun importKey(name: String, document: ByteArray, passphrase: CharArray?) =
                error("unused")
            override suspend fun rename(id: Long, name: String) = error("unused")
            override suspend fun delete(id: Long): DeleteKeyResult = error("unused")
        }

    private fun passwordDraft() = RouteDraft(
        name = "office",
        target = EndpointDraft("target", "22", "sam", password = "target-secret"),
        jump = EndpointDraft("jump", "22", "proxy", password = "jump-secret"),
    )

    private fun assertAuthenticationCleared(route: RouteWriteInput) {
        val buffers = listOfNotNull(route.jump, route.target).map { endpoint ->
            (endpoint.authentication as EndpointAuthenticationInput.Password).ownedBytes()
        }
        assertEquals(2, buffers.size)
        assertTrue(buffers.all { buffer -> buffer.all { it == 0.toByte() } })
    }

    private fun EndpointAuthenticationInput.Password.ownedBytes(): ByteArray =
        javaClass.getDeclaredField("ownedBytes").run {
            isAccessible = true
            get(this@ownedBytes) as ByteArray
        }

    private fun assertFailure(message: String, block: () -> Unit) {
        val failure = assertThrows(IllegalArgumentException::class.java) { block() }
        assertTrue(failure.message.orEmpty().contains(message))
    }
}
