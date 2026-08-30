package dev.herdroid.feature.connections

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.herdroid.core.data.RouteRepository
import dev.herdroid.core.keyvault.KeyVault
import dev.herdroid.core.model.HardwareKeyMetadata
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

const val CREATED_KEY_ID = "created_key_id"

private data class PendingCreatedKey(
    val id: Long,
    val forTarget: Boolean,
)

data class ConnectionEditorUiState(
    val draft: RouteDraft? = null,
    val keys: List<HardwareKeyMetadata> = emptyList(),
    val saveError: String? = null,
    val loading: Boolean = false,
    val confirmDelete: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ConnectionEditorViewModel private constructor(
    private val savedStateHandle: SavedStateHandle,
    private val routes: RouteRepository,
    private val keyVault: KeyVault,
    private val testScope: CoroutineScope?,
    @Suppress("UNUSED_PARAMETER") construction: Unit,
) : ViewModel() {
    @Inject
    internal constructor(
        savedStateHandle: SavedStateHandle,
        routes: RouteRepository,
        keyVault: KeyVault,
    ) : this(savedStateHandle, routes, keyVault, null, Unit)

    internal constructor(
        savedStateHandle: SavedStateHandle,
        routes: RouteRepository,
        keyVault: KeyVault,
        scope: CoroutineScope,
    ) : this(savedStateHandle, routes, keyVault, scope, Unit)

    private val scope get() = testScope ?: viewModelScope
    private val draft = MutableStateFlow<RouteDraft?>(null)
    private val saveError = MutableStateFlow<String?>(null)
    private val loading = MutableStateFlow(false)
    private val confirmDelete = MutableStateFlow(false)
    private val keys = MutableStateFlow<List<HardwareKeyMetadata>>(emptyList())
    private val keyRefreshMutex = Mutex()
    private val keyTransferMutex = Mutex()
    private var keyRefresh: Deferred<Unit>? = null
    private var initialized = false
    private var targetKeyRequested: Boolean? = null
    private var pendingCreatedKey: PendingCreatedKey? = null

    val uiState: StateFlow<ConnectionEditorUiState> = combine(
        draft,
        keys,
        saveError,
        loading,
        confirmDelete,
    ) { currentDraft, currentKeys, error, isLoading, deleting ->
        ConnectionEditorUiState(currentDraft, currentKeys, error, isLoading, deleting)
    }.stateIn(scope, SharingStarted.WhileSubscribed(), ConnectionEditorUiState())

    fun resume(routeId: Long?, results: SavedStateHandle = savedStateHandle) {
        pause()
        initializeDraft(routeId, results.get<Boolean>("duplicate") == true)
        val keyId = results.get<Long>(CREATED_KEY_ID)
        if (pendingCreatedKey == null && keyId != null) {
            targetKeyRequested?.let { target ->
                pendingCreatedKey = PendingCreatedKey(keyId, target)
            }
        } else if (keyId == null && pendingCreatedKey == null) {
            targetKeyRequested = null
        }
        val pending = pendingCreatedKey
        keyRefresh = scope.async {
            refreshKeysNow()
            if (pending != null && pendingCreatedKey === pending) {
                draft.value?.let { current ->
                    draft.value = when (pending.forTarget) {
                        true -> current.copy(target = current.target.withKey(pending.id))
                        false -> current.copy(jump = current.jump?.withKey(pending.id))
                    }
                    pendingCreatedKey = null
                    targetKeyRequested = null
                    if (results.get<Long>(CREATED_KEY_ID) == pending.id) {
                        results.remove<Long>(CREATED_KEY_ID)
                    }
                }
            }
        }
    }

    private fun initializeDraft(routeId: Long?, duplicate: Boolean) {
        if (initialized) return
        initialized = true
        if (routeId == null) {
            draft.value = RouteDraft()
            return
        }
        loading.value = true
        scope.launch {
            try {
                val route = routes.findEditable(routeId)
                if (route == null) saveError.value = "Connection not found"
                else draft.value = RouteDraft.from(route).let {
                    if (duplicate) it.copy(id = 0, name = "Copy of ${it.name}") else it
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                saveError.value = failure.message ?: "Unable to load connection"
            } finally {
                loading.value = false
            }
        }
    }

    fun updateDraft(value: RouteDraft) {
        draft.value = value
    }

    fun requestDelete() {
        confirmDelete.value = true
    }

    fun dismissDelete() {
        confirmDelete.value = false
    }

    suspend fun requestCreatedKey(forTarget: Boolean): Boolean {
        if (!keyTransferMutex.tryLock()) return false
        return try {
            if (targetKeyRequested != null || pendingCreatedKey != null) return false
            val refresh = keyRefresh
            refresh?.cancelAndJoin()
            if (keyRefresh !== refresh) return false
            targetKeyRequested = forTarget
            true
        } finally {
            keyTransferMutex.unlock()
        }
    }

    fun pause() {
        keyRefresh?.cancel()
    }

    private suspend fun refreshKeysNow() = keyRefreshMutex.withLock {
        val snapshot = try {
            keyVault.keys.first()
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            emptyList()
        }
        keys.value = snapshot
    }

    fun save(onSaved: () -> Unit) = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        if (!loading.compareAndSet(false, true)) return@launch
        saveError.value = null
        var input: dev.herdroid.core.data.RouteWriteInput? = null
        try {
            keyRefresh?.await()
            input = requireNotNull(draft.value).toRouteWriteInput(keys.value)
            routes.save(input)
            draft.value = null
            onSaved()
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            saveError.value = failure.message ?: "Unable to save connection"
        } finally {
            input?.close()
            loading.value = false
        }
    }

    fun delete(onDeleted: () -> Unit) = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        if (!loading.compareAndSet(false, true)) return@launch
        saveError.value = null
        try {
            routes.delete(requireNotNull(draft.value).id)
            draft.value = null
            confirmDelete.value = false
            onDeleted()
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            saveError.value = failure.message ?: "Unable to delete connection"
        } finally {
            loading.value = false
        }
    }

    fun discard() {
        draft.value = null
        targetKeyRequested = null
        pendingCreatedKey = null
        confirmDelete.value = false
    }

    override fun onCleared() {
        discard()
        super.onCleared()
    }
}
