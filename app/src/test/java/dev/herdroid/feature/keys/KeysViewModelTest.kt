package dev.herdroid.feature.keys

import dev.herdroid.core.keyvault.DeleteKeyResult
import dev.herdroid.core.keyvault.KeyVault
import dev.herdroid.core.model.HardwareKeyMetadata
import dev.herdroid.core.model.HardwareSecurityLevel
import dev.herdroid.core.model.SshKeyOrigin
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class KeysViewModelTest {
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    @Test
    fun `document acquisition runs on supplied IO dispatcher`() = runBlocking {
        val caller = Thread.currentThread()
        val dispatcher = Executors.newSingleThreadExecutor { task -> Thread(task, "key-document-io") }
            .asCoroutineDispatcher()
        try {
            var readThread: Thread? = null

            val document = readKeyDocument(dispatcher) {
                readThread = Thread.currentThread()
                ByteArrayInputStream("private-document".encodeToByteArray())
            }

            assertNotEquals(caller, readThread)
            assertTrue(readThread?.name.orEmpty().startsWith("key-document-io"))
            assertEquals("private-document", document.decodeToString())
            document.fill(0)
        } finally {
            dispatcher.close()
        }
    }

    @Test
    fun `document reader rejects oversized input and wipes its working buffer`() {
        val success = RecordingInputStream(byteArrayOf(1, 2, 3))
        assertArrayEquals(byteArrayOf(1, 2, 3), readKeyDocument(success))
        assertTrue(success.buffers.all { buffer -> buffer.all { it == 0.toByte() } })

        val failure = RecordingInputStream(byteArrayOf(4, 5, 6), failAfterCopyOnRead = 1)
        assertThrows(IOException::class.java) { readKeyDocument(failure) }
        assertTrue(failure.buffers.all { buffer -> buffer.all { it == 0.toByte() } })

        val oversized = assertThrows(IllegalArgumentException::class.java) {
            readKeyDocument(ByteArrayInputStream(ByteArray(256 * 1024 + 1)))
        }
        assertTrue(oversized.message.orEmpty().contains("too large"))
    }

    @Test
    fun `generated key result waits for authoritative flow and emits once`() = runBlocking {
        val keys = MutableStateFlow<List<HardwareKeyMetadata>>(emptyList())
        val created = key()
        val vault = FakeVault(keys, generate = { created })
        val viewModel = viewModel(vault)
        viewModel.openGenerate()
        viewModel.updateName("phone")

        val creation = viewModel.generateKey(returnCreatedKey = true)
        assertTrue(creation.isActive)
        assertNull(viewModel.uiState.value.createdKeyId)
        keys.value = listOf(created)
        creation.join()

        assertEquals(7L, viewModel.uiState.value.createdKeyId)
        viewModel.consumeCreatedKey()
        assertNull(viewModel.uiState.value.createdKeyId)
    }

    @Test
    fun `missing authoritative key times out with recovery and remains unselected`() = runBlocking {
        val viewModel = viewModel(FakeVault(generate = { key() }))
        viewModel.openGenerate()
        viewModel.updateName("phone")
        val creation = viewModel.generateKey(returnCreatedKey = true)
        val finished = withTimeoutOrNull(1_500) { creation.join(); true } == true

        assertTrue(finished)
        assertNull(viewModel.uiState.value.createdKeyId)
        assertEquals(CREATED_KEY_RECOVERY_MESSAGE, viewModel.uiState.value.error)
    }

    @Test
    fun `newer mutation owns visible error over stale creation`() = runBlocking {
        val firstStarted = CompletableDeferred<Unit>()
        val finishFirst = CompletableDeferred<Unit>()
        var generateCalls = 0
        val vault = FakeVault(
            generate = {
                generateCalls++
                firstStarted.complete(Unit)
                finishFirst.await()
                key()
            },
            rename = { _, _ -> error("newer rename failed") },
        )
        val viewModel = viewModel(vault)
        viewModel.openGenerate()
        viewModel.updateName("phone")
        val stale = viewModel.generateKey(returnCreatedKey = true)
        firstStarted.await()
        viewModel.openRename(key())
        viewModel.updateName("renamed")

        viewModel.renameKey(7).join()
        finishFirst.complete(Unit)
        stale.join()

        assertEquals(1, generateCalls)
        assertEquals("newer rename failed", viewModel.uiState.value.error)
    }

    @Test
    fun `cancel clears every sensitive import draft reference`() = runBlocking {
        val viewModel = viewModel(FakeVault())
        viewModel.openImport()
        viewModel.updateName("phone")
        viewModel.updatePassphrase("passphrase-marker")

        assertEquals("phone", viewModel.uiState.value.keyName)
        assertEquals("passphrase-marker", viewModel.uiState.value.passphrase)

        viewModel.cancelDialog()

        assertEquals("", viewModel.uiState.value.keyName)
        assertEquals("", viewModel.uiState.value.passphrase)
        assertNull(viewModel.uiState.value.selectedDocumentUri)
        assertNull(viewModel.uiState.value.selectedDocumentName)
    }

    @Test
    fun `rename and delete outcomes remain durable UI state`() = runBlocking {
        var renamed: Pair<Long, String>? = null
        val vault = FakeVault(
            rename = { id, name -> renamed = id to name },
            delete = { DeleteKeyResult.Referenced(listOf("alpha", "zeta")) },
        )
        val viewModel = viewModel(vault)
        viewModel.openRename(key())
        viewModel.updateName("laptop")
        viewModel.renameKey(7).join()
        assertEquals(7L to "laptop", renamed)

        viewModel.openDelete(key())
        viewModel.deleteKey(7).join()
        assertEquals("Key is used by: alpha, zeta", viewModel.uiState.value.message)
    }

    private fun viewModel(vault: KeyVault) = KeysViewModel(vault, Dispatchers.Unconfined, scope)

    private fun key(id: Long = 7, name: String = "phone") = HardwareKeyMetadata(
        id,
        name,
        "SHA256:abc",
        SshKeyOrigin.GENERATED,
        HardwareSecurityLevel.TEE,
        1,
        0,
        "ecdsa-sha2-nistp256 AAAA herdroid:$name",
    )

    private class FakeVault(
        override val keys: Flow<List<HardwareKeyMetadata>> = MutableStateFlow(emptyList()),
        private val generate: suspend (String) -> HardwareKeyMetadata = { error("unused") },
        private val rename: suspend (Long, String) -> Unit = { _, _ -> },
        private val delete: suspend (Long) -> DeleteKeyResult = { DeleteKeyResult.Deleted },
    ) : KeyVault {
        override suspend fun generate(name: String) = generate.invoke(name)
        override suspend fun importKey(name: String, document: ByteArray, passphrase: CharArray?) =
            error("URI import is covered by device flow")
        override suspend fun rename(id: Long, name: String) = rename.invoke(id, name)
        override suspend fun delete(id: Long) = delete.invoke(id)
    }

    private class RecordingInputStream(
        private val bytes: ByteArray,
        private val failAfterCopyOnRead: Int? = null,
    ) : InputStream() {
        val buffers = mutableListOf<ByteArray>()
        private var offset = 0
        private var reads = 0

        override fun read(): Int = if (offset == bytes.size) -1 else bytes[offset++].toInt() and 0xff

        override fun read(buffer: ByteArray, targetOffset: Int, length: Int): Int {
            buffers += buffer
            if (offset == bytes.size) return -1
            val count = minOf(length, bytes.size - offset)
            bytes.copyInto(buffer, targetOffset, offset, offset + count)
            offset += count
            reads++
            if (reads == failAfterCopyOnRead) throw IOException("injected read failure")
            return count
        }
    }
}
