package dev.herdroid.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.herdroid.core.data.db.HerdroidDatabase
import dev.herdroid.core.data.db.LocalDataReady
import dev.herdroid.core.data.db.SecretStore
import dev.herdroid.core.model.HardwareSecurityLevel
import dev.herdroid.core.model.SshKeyOrigin
import java.security.KeyStore
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeyMetadataRepositoryDatabaseRaceTest {
    private lateinit var context: Context

    @Before
    fun resetLocalData() {
        context = ApplicationProvider.getApplicationContext()
        clearTestData()
    }

    @After
    fun clearLocalData() = clearTestData()

    @Test
    fun final_reference_check_and_alias_delete_exclude_concurrent_route_save() = runBlocking {
        val database = openReady()
        val store = RouteStore(database)
        val key = insertKey(store, "concurrent")
        val aliasDeleteEntered = CountDownLatch(1)
        val allowAliasDelete = CountDownLatch(1)
        val aliasDestroyed = AtomicBoolean(false)
        val deletion = async(Dispatchers.IO) {
            store.delete(key.id) { alias ->
                assertEquals(key.alias, alias)
                aliasDeleteEntered.countDown()
                check(allowAliasDelete.await(10, TimeUnit.SECONDS))
                aliasDestroyed.set(true)
            }
        }

        assertTrue(withContext(Dispatchers.IO) { aliasDeleteEntered.await(10, TimeUnit.SECONDS) })
        val saveFinished = CountDownLatch(1)
        val saving = async(Dispatchers.IO) {
            runCatching {
                keyRoute("racing", key.id).use { store.save(it) }
            }.also { saveFinished.countDown() }
        }
        val saveCompletedWhileAliasDeleteBlocked = try {
            withContext(Dispatchers.IO) { saveFinished.await(2, TimeUnit.SECONDS) }
        } finally {
            allowAliasDelete.countDown()
        }
        val deleteResult = deletion.await()
        val saveResult = saving.await()
        val routePersisted = saveResult.getOrNull()?.let { store.findEditable(it) } != null

        assertFalse(saveCompletedWhileAliasDeleteBlocked)
        assertFalse(aliasDestroyed.get() && routePersisted)
        assertEquals(DeleteKeyMetadataResult.Deleted, deleteResult)
        assertTrue(saveResult.isFailure)
        database.close()
    }

    @Test
    fun referenced_key_returns_sorted_routes_without_deleting_alias_or_metadata() = runBlocking {
        val database = openReady()
        val store = RouteStore(database)
        val key = insertKey(store, "shared")
        val zeta = keyRoute("zeta", key.id).use { store.save(it) }
        val alpha = keyRoute("alpha", key.id).use { store.save(it) }
        var aliasesDeleted = 0

        assertEquals(
            DeleteKeyMetadataResult.Referenced(listOf("alpha", "zeta")),
            store.delete(key.id) { aliasesDeleted += 1 },
        )
        assertEquals(0, aliasesDeleted)
        assertEquals(key.id, store.find(key.id)?.id)

        store.delete(zeta)
        store.delete(alpha)
        assertEquals(DeleteKeyMetadataResult.Deleted, store.delete(key.id) { aliasesDeleted += 1 })
        assertEquals(1, aliasesDeleted)
        assertEquals(null, store.find(key.id))
        database.close()
    }

    @Test
    fun cancelled_key_insert_rolls_back_metadata_and_wipes_transaction_copy() = runBlocking {
        val database = openReady()
        val store = RouteStore(database, afterKeyInsert = { throw CancellationException("cancel after insert") })
        val input = keyInput("cancelled")

        val failure = runCatching { input.use { store.insert(it) } }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertTrue(database.routeDao().observeKeys().first().isEmpty())
        assertTrue(input.ownedPublicKey().all { it == 0.toByte() })
        database.close()
    }

    @Test
    fun cancellation_after_alias_delete_still_commits_metadata_delete() = runBlocking {
        val database = openReady()
        val store = RouteStore(database, afterKeyInsert = {}, afterAliasDelete = { yield() })
        val key = insertKey(store, "cancel-delete")
        val aliasDestroyed = AtomicBoolean(false)
        lateinit var deletion: Job
        deletion = launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            runCatching {
                store.delete(key.id) {
                    aliasDestroyed.set(true)
                    deletion.cancel()
                }
            }
        }

        deletion.start()
        deletion.join()

        assertTrue(aliasDestroyed.get())
        assertTrue(database.routeDao().observeKeys().first().isEmpty())
        database.close()
    }

    @Test
    fun cancellation_after_key_commit_keeps_committed_metadata() = runBlocking {
        val database = openReady()
        lateinit var insertion: Job
        val store = RouteStore(
            database,
            afterKeyInsert = {},
            afterKeyCommit = {
                insertion.cancel()
                yield()
            },
        )
        insertion = launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            runCatching { keyInput("committed").use { store.insert(it) } }
        }

        insertion.start()
        insertion.join()

        assertEquals("committed", database.routeDao().observeKeys().first().single().name)
        database.close()
    }

    @Test
    fun cancelled_delete_queued_behind_transaction_never_deletes_alias() = runBlocking {
        val database = openReady()
        val store = RouteStore(database)
        val first = insertKey(store, "first")
        val second = insertKey(store, "second")
        val firstAliasDeleteEntered = CountDownLatch(1)
        val allowFirstAliasDelete = CountDownLatch(1)
        val deletedAliases = mutableListOf<String>()
        val firstDeletion = async(Dispatchers.IO) {
            store.delete(first.id) {
                firstAliasDeleteEntered.countDown()
                check(allowFirstAliasDelete.await(10, TimeUnit.SECONDS))
                deletedAliases += it
            }
        }
        assertTrue(withContext(Dispatchers.IO) { firstAliasDeleteEntered.await(10, TimeUnit.SECONDS) })
        val secondDeletion = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            runCatching { store.delete(second.id) { deletedAliases += it } }
        }

        secondDeletion.cancel()
        allowFirstAliasDelete.countDown()
        firstDeletion.await()
        secondDeletion.join()

        assertEquals(listOf(first.alias), deletedAliases)
        assertEquals(listOf(second.alias), database.routeDao().observeKeys().first().map { it.alias })
        database.close()
    }

    private suspend fun insertKey(store: RouteStore, name: String): StoredKeyMetadata =
        keyInput(name).use { store.insert(it) }

    private fun keyInput(name: String) = NewKeyMetadata(
        name,
        "herdroid.ssh.$name",
        byteArrayOf(1, 2, 3),
        "SHA256:$name",
        SshKeyOrigin.GENERATED,
        HardwareSecurityLevel.TEE,
        1,
    )

    private fun keyRoute(name: String, keyId: Long) = RouteWriteInput(
        0,
        name,
        EndpointWriteInput(
            "$name.example",
            22,
            "developer",
            EndpointAuthenticationInput.HardwareKey(keyId),
            null,
        ),
        null,
    )

    private fun NewKeyMetadata.ownedPublicKey(): ByteArray = javaClass.getDeclaredField("ownedPublicKey").run {
        isAccessible = true
        (get(this@ownedPublicKey) as ByteArray).copyOf()
    }

    private fun openReady(): HerdroidDatabase {
        val result = HerdroidDatabase.open(context)
        assertTrue(result is LocalDataReady)
        return (result as LocalDataReady).database
    }

    private fun clearTestData() {
        if (!::context.isInitialized) return
        context.deleteDatabase(HerdroidDatabase.DATABASE_NAME)
        context.getSharedPreferences(SecretStore.PREFERENCES_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.run {
            if (containsAlias(SecretStore.KEY_ALIAS)) deleteEntry(SecretStore.KEY_ALIAS)
        }
    }
}
