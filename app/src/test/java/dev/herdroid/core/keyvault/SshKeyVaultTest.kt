package dev.herdroid.core.keyvault

import dev.herdroid.core.data.DeleteKeyMetadataResult
import dev.herdroid.core.data.KeyMetadataRepository
import dev.herdroid.core.data.NewKeyMetadata
import dev.herdroid.core.data.StoredKeyMetadata
import dev.herdroid.core.model.HardwareSecurityLevel
import dev.herdroid.core.model.SshKeyOrigin
import dev.herdroid.core.ssh.CreatedHardwareKey
import dev.herdroid.core.ssh.HardwareKeyOperations
import dev.herdroid.core.ssh.keys.SshPublicKeyCodec
import java.util.Base64
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SshKeyVaultTest {
    @Test
    fun `observation and rename use the sole canonical authorized-line codec`() = runTest {
        val repository = FakeMetadataRepository().apply { seed("phone") }
        val vault = vault(repository)
        val expectedInitial = SshPublicKeyCodec.authorizedKeyLine(PUBLIC_BLOB, "phone")

        assertEquals(expectedInitial, vault.keys.first().single().authorizedKeyLine)

        vault.rename(1, " renamed ")

        val renamed = vault.keys.first().single()
        assertEquals("renamed", renamed.name)
        assertEquals(SshPublicKeyCodec.authorizedKeyLine(PUBLIC_BLOB, "renamed"), renamed.authorizedKeyLine)
    }

    @Test
    fun `metadata failure rolls back the exact alias and suppresses cleanup failure`() = runTest {
        val metadataFailure = IllegalStateException("metadata failed")
        val cleanupFailure = IllegalStateException("cleanup failed")
        val repository = FakeMetadataRepository().apply { insertFailure = metadataFailure }
        val hardware = FakeHardware().apply { deleteFailure = cleanupFailure }
        val vault = vault(repository, hardware)

        val failure = runCatching { vault.generate("phone") }.exceptionOrNull()

        assertSame(metadataFailure, failure)
        assertEquals(listOf("herdroid.ssh.1"), hardware.deletedAliases)
        assertEquals(listOf(cleanupFailure), failure?.suppressed?.toList())
    }

    @Test
    fun `cancellation before alias allocation leaves hardware and metadata untouched`() = runTest {
        val repository = FakeMetadataRepository()
        val hardware = FakeHardware()
        val vault = vault(repository, hardware)
        lateinit var operation: Job
        operation = launch(start = CoroutineStart.LAZY) {
            currentCoroutineContext()[Job]?.cancel()
            runCatching { vault.generate("cancelled") }
        }

        operation.start()
        operation.join()

        assertEquals(0, hardware.aliases)
        assertTrue(hardware.deletedAliases.isEmpty())
        assertTrue(repository.rows.value.isEmpty())
    }

    @Test
    fun `cancellation after hardware creation removes alias before metadata`() = runTest {
        val repository = FakeMetadataRepository()
        lateinit var operation: Job
        val hardware = FakeHardware().apply { afterGenerate = { operation.cancel() } }
        val vault = vault(repository, hardware)
        operation = launch(start = CoroutineStart.LAZY) { runCatching { vault.generate("cancelled") } }

        operation.start()
        operation.join()

        assertEquals(listOf("herdroid.ssh.1"), hardware.deletedAliases)
        assertTrue(repository.rows.value.isEmpty())
    }

    @Test
    fun `create closes hardware and metadata public-key owners`() = runTest {
        val repository = FakeMetadataRepository()
        val hardware = FakeHardware()
        val vault = vault(repository, hardware)

        val created = vault.generate("phone")

        assertEquals(SshPublicKeyCodec.authorizedKeyLine(PUBLIC_BLOB, "phone"), created.authorizedKeyLine)
        assertArrayEquals(ByteArray(PUBLIC_BLOB.size), hardware.lastCreated!!.publicKeyOpenSsh())
        assertArrayEquals(ByteArray(PUBLIC_BLOB.size), repository.lastInput!!.copyPublicKeyForTransaction())
        assertTrue(repository.transactionCopies.all { copy -> copy.all { it == 0.toByte() } })
    }

    @Test
    fun `import clears caller document and passphrase when fake hardware does not`() = runTest {
        val document = ByteArray(32) { 7 }
        val passphrase = "passphrase".toCharArray()
        val vault = vault(FakeMetadataRepository(), FakeHardware())

        vault.importKey("imported", document, passphrase)

        assertArrayEquals(ByteArray(document.size), document)
        assertTrue(passphrase.all { it == '\u0000' })
    }

    @Test
    fun `delete delegates the transaction hook and preserves referenced metadata`() = runTest {
        val repository = FakeMetadataRepository().apply { seed("shared", routeNames = listOf("zeta", "alpha")) }
        val hardware = FakeHardware()
        val vault = vault(repository, hardware)

        assertEquals(DeleteKeyResult.Referenced(listOf("alpha", "zeta")), vault.delete(1))
        assertTrue(hardware.deletedAliases.isEmpty())
        assertFalse(repository.rows.value.isEmpty())

        repository.references.clear()
        assertEquals(DeleteKeyResult.Deleted, vault.delete(1))
        assertEquals(listOf("herdroid.ssh.1"), hardware.deletedAliases)
        assertTrue(repository.rows.value.isEmpty())
    }

    private fun TestScope.vault(
        repository: FakeMetadataRepository,
        hardware: FakeHardware = FakeHardware(),
    ) = SshKeyVault(repository, hardware, UnconfinedTestDispatcher(testScheduler))

    private class FakeMetadataRepository : KeyMetadataRepository {
        val rows = MutableStateFlow<List<StoredKeyMetadata>>(emptyList())
        val references = mutableMapOf<Long, List<String>>()
        val transactionCopies = mutableListOf<ByteArray>()
        var insertFailure: Exception? = null
        var lastInput: NewKeyMetadata? = null
        override val keys: Flow<List<StoredKeyMetadata>> = rows

        override suspend fun insert(input: NewKeyMetadata): StoredKeyMetadata {
            lastInput = input
            insertFailure?.let { throw it }
            val copy = input.copyPublicKeyForTransaction()
            transactionCopies += copy
            return try {
                StoredKeyMetadata(
                    id = 1,
                    name = input.name,
                    alias = input.alias,
                    publicKeyBase64 = Base64.getEncoder().encodeToString(copy),
                    fingerprint = input.fingerprint,
                    origin = input.origin,
                    securityLevel = input.securityLevel,
                    createdAtEpochMillis = input.createdAtEpochMillis,
                    routeUseCount = 0,
                ).also { rows.value = listOf(it) }
            } finally {
                copy.fill(0)
            }
        }

        override suspend fun find(id: Long): StoredKeyMetadata? = rows.value.singleOrNull { it.id == id }

        override suspend fun rename(id: Long, name: String) {
            rows.value = rows.value.map { if (it.id == id) it.copy(name = name) else it }
        }

        override suspend fun delete(id: Long, deleteAlias: (String) -> Unit): DeleteKeyMetadataResult {
            val routeNames = references[id].orEmpty().distinct().sorted()
            if (routeNames.isNotEmpty()) return DeleteKeyMetadataResult.Referenced(routeNames)
            val row = requireNotNull(find(id))
            deleteAlias(row.alias)
            rows.value = rows.value.filterNot { it.id == id }
            return DeleteKeyMetadataResult.Deleted
        }

        fun seed(name: String, routeNames: List<String> = emptyList()) {
            rows.value = listOf(
                StoredKeyMetadata(
                    1,
                    name,
                    "herdroid.ssh.1",
                    Base64.getEncoder().encodeToString(PUBLIC_BLOB),
                    "SHA256:test",
                    SshKeyOrigin.GENERATED,
                    HardwareSecurityLevel.TEE,
                    1,
                    routeNames.size,
                ),
            )
            references[1] = routeNames
        }

    }

    private class FakeHardware : HardwareKeyOperations {
        var aliases = 0
        var afterGenerate: () -> Unit = {}
        var deleteFailure: Exception? = null
        var lastCreated: CreatedHardwareKey? = null
        val deletedAliases = mutableListOf<String>()

        override fun newAlias() = "herdroid.ssh.${++aliases}"

        override fun generate(alias: String) = created(alias).also {
            lastCreated = it
            afterGenerate()
        }

        override fun importKey(alias: String, document: ByteArray, passphrase: CharArray?) = created(alias)

        override fun delete(alias: String) {
            deletedAliases += alias
            deleteFailure?.let { throw it }
        }

        private fun created(alias: String) =
            CreatedHardwareKey(alias, PUBLIC_BLOB, "SHA256:test", HardwareSecurityLevel.TEE)
    }

    private companion object {
        val PUBLIC_BLOB: ByteArray = Base64.getDecoder().decode(
            "AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBA8BRyh1vExpLAK2Hybju1pMf6FQbM4AaSe8Mxs3z6t3l+ugyqiY6+M+g6Xpy9sOJh4difVXpuMVSJ/x8PD+klI=",
        )
    }
}
