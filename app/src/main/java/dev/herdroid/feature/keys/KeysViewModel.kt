package dev.herdroid.feature.keys

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.herdroid.core.common.Dispatcher
import dev.herdroid.core.common.HerdroidDispatchers
import dev.herdroid.core.keyvault.DeleteKeyResult
import dev.herdroid.core.keyvault.KeyVault
import dev.herdroid.core.model.HardwareKeyMetadata
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull

private const val CREATED_KEY_VISIBILITY_TIMEOUT_MILLIS = 1_000L
internal const val CREATED_KEY_RECOVERY_MESSAGE =
    "Created key is not available yet. Go back and select it from the hardware key list."

enum class KeyDialog { ADD, GENERATE, IMPORT, RENAME, DELETE }

data class KeysUiState(
    val keys: List<HardwareKeyMetadata> = emptyList(),
    val dialog: KeyDialog? = null,
    val keyName: String = "",
    val passphrase: String = "",
    val selectedDocumentUri: Uri? = null,
    val selectedDocumentName: String? = null,
    val keyId: Long? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val createdKeyId: Long? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class KeysViewModel private constructor(
    private val context: Context?,
    private val vault: KeyVault,
    @Dispatcher(HerdroidDispatchers.IO) private val io: CoroutineDispatcher,
    private val testScope: CoroutineScope?,
    @Suppress("UNUSED_PARAMETER") construction: Unit,
) : ViewModel() {
    @Inject
    internal constructor(
        @ApplicationContext context: Context,
        vault: KeyVault,
        @Dispatcher(HerdroidDispatchers.IO) io: CoroutineDispatcher,
    ) : this(context, vault, io, null, Unit)

    internal constructor(
        vault: KeyVault,
        io: CoroutineDispatcher,
        scope: CoroutineScope,
    ) : this(null, vault, io, scope, Unit)

    private val scope get() = testScope ?: viewModelScope
    private val mutableUiState = MutableStateFlow(KeysUiState())
    private val keyResults: StateFlow<Result<List<HardwareKeyMetadata>>> = vault.keys
        .map<List<HardwareKeyMetadata>, Result<List<HardwareKeyMetadata>>> { Result.success(it) }
        .onCompletion { failure ->
            if (failure == null) emit(Result.failure(IllegalStateException("Authoritative key flow completed")))
        }
        .catch { failure ->
            if (failure is CancellationException) throw failure
            emit(Result.failure(failure))
        }
        .stateIn(scope, SharingStarted.Eagerly, Result.success(emptyList()))
    private var mutationToken = 0L

    val uiState: StateFlow<KeysUiState> = mutableUiState

    init {
        scope.launch {
            keyResults.collect { result ->
                mutableUiState.update { it.copy(keys = result.getOrElse { emptyList() }) }
            }
        }
    }

    fun openAdd() = replaceDraft(dialog = KeyDialog.ADD)
    fun openGenerate() = replaceDraft(dialog = KeyDialog.GENERATE)
    fun openImport() = replaceDraft(dialog = KeyDialog.IMPORT)
    fun openRename(key: HardwareKeyMetadata) = replaceDraft(KeyDialog.RENAME, key.name, key.id)
    fun openDelete(key: HardwareKeyMetadata) = replaceDraft(KeyDialog.DELETE, keyId = key.id)

    fun updateName(name: String) = mutableUiState.update { it.copy(keyName = name) }
    fun updatePassphrase(passphrase: String) = mutableUiState.update { it.copy(passphrase = passphrase) }

    fun selectDocument(uri: Uri): Job {
        mutableUiState.update {
            it.copy(
                selectedDocumentUri = uri,
                selectedDocumentName = uri.lastPathSegment ?: uri.toString(),
                error = null,
            )
        }
        return scope.launch {
            val name = try {
                documentName(uri)
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                null
            } ?: return@launch
            mutableUiState.update { current ->
                if (current.selectedDocumentUri == uri) current.copy(selectedDocumentName = name) else current
            }
        }
    }

    fun cancelDialog() = replaceDraft()

    fun generateKey(returnCreatedKey: Boolean = false): Job {
        val name = mutableUiState.value.keyName
        val token = beginMutation(clearDraft = true)
        return scope.launch {
            try {
                selectCreatedKey(returnCreatedKey, vault.generate(name), token)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                setError(token, failure.message ?: "Unable to generate key")
            } finally {
                finishMutation(token)
            }
        }
    }

    fun importKey(uri: Uri, returnCreatedKey: Boolean = false): Job {
        val current = mutableUiState.value
        val name = current.keyName
        val passphraseText = current.passphrase
        val token = beginMutation(clearDraft = true)
        return scope.launch {
            var document: ByteArray? = null
            var passphrase: CharArray? = null
            try {
                val resolver = requireNotNull(context) { "Application context is unavailable" }.contentResolver
                document = readKeyDocument(io) { resolver.openInputStream(uri) }
                passphrase = passphraseText.takeIf(String::isNotEmpty)?.toCharArray()
                val key = vault.importKey(name, requireNotNull(document), passphrase)
                document = null
                passphrase = null
                selectCreatedKey(returnCreatedKey, key, token)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                setError(token, failure.message ?: "Unable to import key")
            } finally {
                document?.fill(0)
                passphrase?.fill('\u0000')
                finishMutation(token)
            }
        }
    }

    fun renameKey(id: Long): Job {
        val name = mutableUiState.value.keyName
        val token = beginMutation(clearDraft = true)
        return scope.launch {
            try {
                vault.rename(id, name)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                setError(token, failure.message ?: "Unable to rename key")
            } finally {
                finishMutation(token)
            }
        }
    }

    fun deleteKey(id: Long): Job {
        val token = beginMutation(clearDraft = true)
        return scope.launch {
            try {
                val message = when (val result = vault.delete(id)) {
                    DeleteKeyResult.Deleted -> "Key deleted"
                    is DeleteKeyResult.Referenced -> "Key is used by: ${result.routeNames.joinToString()}"
                }
                if (mutationToken == token) mutableUiState.update { it.copy(message = message) }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                setError(token, failure.message ?: "Unable to delete key")
            } finally {
                finishMutation(token)
            }
        }
    }

    fun reportPlatformError(message: String) = mutableUiState.update { it.copy(error = message) }
    fun clearMessage() = mutableUiState.update { it.copy(message = null) }
    fun consumeCreatedKey() = mutableUiState.update { it.copy(createdKeyId = null) }

    private suspend fun selectCreatedKey(
        returnCreatedKey: Boolean,
        key: HardwareKeyMetadata,
        token: Long,
    ) {
        if (!returnCreatedKey || mutationToken != token) return
        val visible = withTimeoutOrNull(CREATED_KEY_VISIBILITY_TIMEOUT_MILLIS) {
            val result = keyResults.first { current ->
                current.isFailure || current.getOrThrow().any { it.id == key.id }
            }
            result.getOrNull()?.any { it.id == key.id } == true
        } == true
        if (mutationToken != token) return
        if (visible) mutableUiState.update { it.copy(createdKeyId = key.id) }
        else setError(token, CREATED_KEY_RECOVERY_MESSAGE)
    }

    private fun beginMutation(clearDraft: Boolean): Long {
        val token = ++mutationToken
        mutableUiState.update {
            if (clearDraft) KeysUiState(keys = it.keys, loading = true)
            else it.copy(loading = true, error = null)
        }
        return token
    }

    private fun finishMutation(token: Long) {
        if (mutationToken == token) mutableUiState.update { it.copy(loading = false) }
    }

    private fun setError(token: Long, message: String?) {
        if (mutationToken == token) mutableUiState.update { it.copy(error = message) }
    }

    private fun replaceDraft(
        dialog: KeyDialog? = null,
        keyName: String = "",
        keyId: Long? = null,
    ) = mutableUiState.update {
        KeysUiState(
            keys = it.keys,
            dialog = dialog,
            keyName = keyName,
            keyId = keyId,
            message = it.message,
            createdKeyId = it.createdKeyId,
        )
    }

    private suspend fun documentName(uri: Uri): String? = runInterruptible(io) {
        val resolver = requireNotNull(context) { "Application context is unavailable" }.contentResolver
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    override fun onCleared() {
        mutableUiState.value = KeysUiState()
        super.onCleared()
    }
}
