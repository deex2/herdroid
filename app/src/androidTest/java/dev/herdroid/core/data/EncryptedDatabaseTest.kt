package dev.herdroid.core.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.herdroid.core.data.db.HerdroidDatabase
import dev.herdroid.core.data.db.LocalDataReady
import dev.herdroid.core.data.db.LocalDataUnavailable
import dev.herdroid.core.data.db.PassphraseOwningOpenHelperFactory
import dev.herdroid.core.data.db.SecretStore
import dev.herdroid.core.model.HardwareSecurityLevel
import dev.herdroid.core.model.KnownHostRecord
import dev.herdroid.core.model.SshKeyOrigin
import java.io.File
import java.security.KeyStore
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.KeyGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EncryptedDatabaseTest {
    private lateinit var context: Context

    @Before
    fun resetLocalData() {
        context = ApplicationProvider.getApplicationContext()
        clearTestData()
    }

    @After
    fun clearLocalData() = clearTestData()

    @Test
    fun transactional_save_after_process_database_close_fails_with_exact_unavailable_error() = runBlocking {
        val available = AtomicBoolean(true)
        val opened = HerdroidDatabase.open(context) { available.set(false) } as LocalDataReady
        val store = RouteStore(opened.database, available::get)

        opened.database.close()
        val failure = runCatching {
            passwordRoute("closed", "closed.example", "secret").use { store.save(it) }
        }.exceptionOrNull()

        assertTrue("actual=$failure", failure is LocalDataUnavailableException)
        assertEquals("Route storage is unavailable", failure?.message)
    }

    @Test
    fun transactional_save_normalizes_close_race_to_exact_unavailable_error() = runBlocking {
        val available = AtomicBoolean(true)
        val opened = HerdroidDatabase.open(context) { available.set(false) } as LocalDataReady
        lateinit var database: HerdroidDatabase
        val store = RouteStore(opened.database) {
            available.get().also { wasAvailable -> if (wasAvailable) database.close() }
        }
        database = opened.database

        val failure = runCatching {
            passwordRoute("racing", "race.example", "secret").use { store.save(it) }
        }.exceptionOrNull()

        assertTrue("actual=$failure", failure is LocalDataUnavailableException)
        assertEquals("Route storage is unavailable", failure?.message)
    }

    @Test
    fun repository_read_normalizes_real_room_close_race_to_exact_unavailable_error() = runBlocking {
        val available = AtomicBoolean(true)
        val opened = HerdroidDatabase.open(context) { available.set(false) } as LocalDataReady
        val store = RouteStore(opened.database) {
            available.get().also { wasAvailable -> if (wasAvailable) opened.database.openHelper.close() }
        }

        val failure = runCatching { store.findEditable(1) }.exceptionOrNull()
        opened.database.close()

        assertTrue("actual=$failure", failure is LocalDataUnavailableException)
        assertEquals("Route storage is unavailable", failure?.message)
    }

    @Test
    fun route_flow_normalizes_real_room_close_race_to_exact_unavailable_error() = runBlocking {
        val available = AtomicBoolean(true)
        val availabilityChecks = AtomicInteger()
        val opened = HerdroidDatabase.open(context) { available.set(false) } as LocalDataReady
        val store = RouteStore(opened.database) {
            available.get().also { wasAvailable ->
                if (wasAvailable && availabilityChecks.incrementAndGet() == 2) opened.database.openHelper.close()
            }
        }

        val failure = runCatching { withTimeout(10_000) { store.routes.first() } }.exceptionOrNull()
        opened.database.close()

        assertTrue("actual=$failure", failure is LocalDataUnavailableException)
        assertEquals("Route storage is unavailable", failure?.message)
    }

    @Test
    fun availability_flip_during_transaction_does_not_replace_cancellation() = runBlocking {
        val available = AtomicBoolean(true)
        val database = openReady()
        val store = RouteStore(
            database,
            afterKeyInsert = {
                available.set(false)
                throw CancellationException("cancelled")
            },
            isAvailable = available::get,
        )

        val failure = runCatching { keyInput("cancelled").use { store.insert(it) } }.exceptionOrNull()

        assertTrue("actual=$failure", failure is CancellationException)
        assertEquals("cancelled", failure?.message)
        database.close()
    }

    @Test
    fun availability_flip_during_transaction_does_not_replace_domain_error() = runBlocking {
        val available = AtomicBoolean(true)
        val database = openReady()
        val store = RouteStore(
            database,
            afterKeyInsert = {
                available.set(false)
                error("domain failure")
            },
            isAvailable = available::get,
        )

        val failure = runCatching { keyInput("domain").use { store.insert(it) } }.exceptionOrNull()

        assertTrue("actual=$failure", failure is IllegalStateException)
        assertFalse(failure is LocalDataUnavailableException)
        assertEquals("domain failure", failure?.message)
        database.close()
    }

    @Test
    fun sqlcipher_wal_opens_late_pool_connections_until_factory_close_wipes_owner() = runBlocking {
        val callerPassphrase = SecretStore(context).loadOrCreate(false)
        val ownedPassphrase = callerPassphrase.copyOf()
        System.loadLibrary("sqlcipher")
        val factory = PassphraseOwningOpenHelperFactory(ownedPassphrase)
        val helper = factory.create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(HerdroidDatabase.DATABASE_NAME)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE late_pool (value TEXT NOT NULL)")
                        db.execSQL("INSERT INTO late_pool VALUES ('committed')")
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                        error("Unexpected late-pool upgrade")
                })
                .build(),
        )
        helper.setWriteAheadLoggingEnabled(true)
        callerPassphrase.fill(0)

        val writer = helper.writableDatabase
        writer.beginTransaction()
        try {
            writer.execSQL("UPDATE late_pool SET value = 'uncommitted'")
            val lateRead = withTimeout(10_000) {
                withContext(Dispatchers.IO) {
                    helper.readableDatabase.query("SELECT value FROM late_pool").use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        cursor.getString(0)
                    }
                }
            }
            assertEquals("committed", lateRead)
        } finally {
            writer.endTransaction()
        }
        withContext(Dispatchers.IO) {
            helper.writableDatabase.execSQL("UPDATE late_pool SET value = 'late-write'")
        }
        assertEquals(
            "late-write",
            helper.readableDatabase.query("SELECT value FROM late_pool").use { cursor ->
                assertTrue(cursor.moveToFirst())
                cursor.getString(0)
            },
        )

        factory.close()

        assertArrayEquals(ByteArray(32), ownedPassphrase)
        assertEquals("Database helper is closed", runCatching { helper.readableDatabase }.exceptionOrNull()?.message)
        assertEquals("Database helper is closed", runCatching { helper.writableDatabase }.exceptionOrNull()?.message)
    }

    @Test
    fun encrypted_database_reopens_version_three_routes_and_key_metadata_without_private_material() = runBlocking {
        val database = openReady()
        val store = RouteStore(database)
        assertEquals(0, database.routeDao().endpointCount())
        val key = keyInput("shared", alias = "herdroid.ssh.test-metadata").use { store.insert(it) }
        val duplicateFailure = runCatching {
            keyInput("shared", alias = "herdroid.ssh.test-duplicate").use { store.insert(it) }
        }.exceptionOrNull()
        assertEquals("A key with this name already exists.", duplicateFailure?.message)

        val passwordRouteId = passwordRoute("password", "password.example", "password-secret").use {
            store.save(it)
        }
        val cache = BridgeLaunchCache(
            "x86_64-pc-windows-msvc",
            "C:\\tools\\herdr.exe",
            "C:\\tools\\bridge.exe",
        )
        store.updateBridgeCache(passwordRouteId, cache)
        store.loadForConnection(passwordRouteId).use { assertEquals(cache, it.target.bridgeCache) }
        val keyRouteId = RouteWriteInput(
            id = 0,
            name = "hardware",
            target = keyEndpoint("target.example", key.id),
            jump = keyEndpoint("jump.example", key.id),
        ).use { store.save(it) }
        assertEquals(listOf("hardware", "password"), store.routes.first { it.isNotEmpty() }.map { it.name })
        store.loadForConnection(keyRouteId).use { persisted ->
            assertTrue(persisted.target.authentication is ConnectionAuthenticationInput.HardwareKey)
            assertTrue(persisted.jump?.authentication is ConnectionAuthenticationInput.HardwareKey)
        }
        assertEquals(1, store.keys.first().single().routeUseCount)

        val firstTrust = KnownHostRecord("target.example", 22, "ssh-ed25519", "AAAAfirst", 10)
        store.updateKnownHost(firstTrust)
        assertEquals(firstTrust, store.knownHosts("target.example", 22).single())
        val replacedTrust = firstTrust.copy(keyBase64 = "AAAAreplacement", acceptedAtEpochMillis = 20)
        store.updateKnownHost(replacedTrust)
        assertEquals(replacedTrust, store.knownHosts("target.example", 22).single())
        database.close()

        databaseArtifacts().filter(File::exists).forEach { artifact ->
            val contents = artifact.readBytes().decodeToString()
            assertFalse(contents.contains("target.example"))
            assertFalse(contents.contains("password-secret"))
        }
        val preferences = context.getSharedPreferences(SecretStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
        assertEquals(setOf(SecretStore.IV_KEY, SecretStore.CIPHERTEXT_KEY), preferences.all.keys)

        val reopened = openReady()
        val reopenedStore = RouteStore(reopened)
        assertEquals(3, reopened.openHelper.readableDatabase.version)
        assertEquals(listOf("hardware", "password"), reopenedStore.routes.first { it.isNotEmpty() }.map { it.name })
        assertEquals(key.id, reopenedStore.findEditable(keyRouteId)?.target?.keyId)
        val endpointColumns = tableColumns(reopened, "endpoints")
        assertFalse(endpointColumns.contains("privateKeyPkcs8"))
        assertFalse(endpointColumns.contains("publicKeyOpenSsh"))
        val keyColumns = tableColumns(reopened, "ssh_keys")
        assertFalse(keyColumns.any { it.contains("private", ignoreCase = true) })
        assertFalse(keyColumns.contains("authorizedKeyLine"))
        val persistedPublicKey = reopened.openHelper.readableDatabase
            .query("SELECT publicKeyOpenSsh FROM ssh_keys WHERE id = ${key.id}")
            .use { cursor ->
                assertTrue(cursor.moveToFirst())
                cursor.getBlob(0)
            }
        assertArrayEquals(PUBLIC_KEY, persistedPublicKey)

        passwordRoute("renamed", "new-target.example", "new-target-secret", passwordRouteId).use {
            reopenedStore.save(it)
        }
        reopenedStore.loadForConnection(passwordRouteId).use { assertNull(it.target.bridgeCache) }
        assertEquals(3, reopened.routeDao().endpointCount())
        reopenedStore.delete(passwordRouteId)
        reopenedStore.delete(keyRouteId)
        assertTrue(reopenedStore.routes.first().isEmpty())
        assertEquals(0, reopened.routeDao().endpointCount())
        reopened.close()
    }

    @Test
    fun unsupported_future_database_version_fails_closed() {
        createEncryptedFixture(4) { database ->
            database.execSQL("CREATE TABLE future_data (id INTEGER PRIMARY KEY NOT NULL)")
        }

        assertEquals(LocalDataUnavailable, HerdroidDatabase.open(context))
        assertTrue(context.getDatabasePath(HerdroidDatabase.DATABASE_NAME).exists())
    }

    @Test
    fun corrupt_local_data_states_fail_closed_without_changing_database_artifacts() {
        for (state in CorruptState.entries) {
            clearTestData()
            val database = openReady()
            runBlocking {
                passwordRoute("kept", "kept.example", "kept-secret").use { RouteStore(database).save(it) }
            }
            database.close()

            corrupt(state)
            val before = snapshotArtifacts()

            assertEquals(state.name, LocalDataUnavailable, HerdroidDatabase.open(context))
            assertArtifactsEqual(state, before, snapshotArtifacts())
        }
    }

    private fun passwordRoute(name: String, hostname: String, password: String, id: Long = 0) =
        RouteWriteInput(id, name, passwordEndpoint(hostname, password), null)

    private fun passwordEndpoint(hostname: String, password: String) = EndpointWriteInput(
        hostname,
        22,
        "developer",
        EndpointAuthenticationInput.Password(password.encodeToByteArray()),
        null,
    )

    private fun keyEndpoint(hostname: String, keyId: Long) = EndpointWriteInput(
        hostname,
        22,
        "developer",
        EndpointAuthenticationInput.HardwareKey(keyId),
        null,
    )

    private fun keyInput(name: String, alias: String = name) = NewKeyMetadata(
        name,
        alias,
        PUBLIC_KEY.copyOf(),
        "SHA256:$name",
        SshKeyOrigin.GENERATED,
        HardwareSecurityLevel.TEE,
        1,
    )

    private fun tableColumns(database: HerdroidDatabase, table: String): List<String> =
        database.openHelper.readableDatabase.query("PRAGMA table_info(`$table`)").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(1)) }
        }

    private fun createEncryptedFixture(version: Int, create: (SupportSQLiteDatabase) -> Unit) {
        val passphrase = SecretStore(context).loadOrCreate(false)
        System.loadLibrary("sqlcipher")
        val factory = PassphraseOwningOpenHelperFactory(passphrase.copyOf())
        passphrase.fill(0)
        factory.create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(HerdroidDatabase.DATABASE_NAME)
                .callback(object : SupportSQLiteOpenHelper.Callback(version) {
                    override fun onCreate(database: SupportSQLiteDatabase) = create(database)
                    override fun onUpgrade(database: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                        error("Unexpected fixture upgrade")
                })
                .build(),
        ).use { it.writableDatabase }
        factory.close()
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
        deleteWrappingKey()
    }

    private fun deleteWrappingKey() {
        androidKeyStore().run { if (containsAlias(SecretStore.KEY_ALIAS)) deleteEntry(SecretStore.KEY_ALIAS) }
    }

    private fun androidKeyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun corrupt(state: CorruptState) {
        val preferences = context.getSharedPreferences(SecretStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
        when (state) {
            CorruptState.MISSING_IV -> preferences.edit().remove(SecretStore.IV_KEY).commit()
            CorruptState.MISSING_CIPHERTEXT -> preferences.edit().remove(SecretStore.CIPHERTEXT_KEY).commit()
            CorruptState.MALFORMED_BASE64 -> preferences.edit().putString(SecretStore.IV_KEY, "%%%invalid%%%").commit()
            CorruptState.FLIPPED_CIPHERTEXT_TAG -> {
                val ciphertext = Base64.decode(preferences.getString(SecretStore.CIPHERTEXT_KEY, null), Base64.NO_WRAP)
                ciphertext[ciphertext.lastIndex] = (ciphertext.last().toInt() xor 1).toByte()
                preferences.edit().putString(
                    SecretStore.CIPHERTEXT_KEY,
                    Base64.encodeToString(ciphertext, Base64.NO_WRAP),
                ).commit()
                ciphertext.fill(0)
            }
            CorruptState.DELETED_ALIAS -> deleteWrappingKey()
            CorruptState.REPLACED_ALIAS -> {
                deleteWrappingKey()
                KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                    init(
                        KeyGenParameterSpec.Builder(
                            SecretStore.KEY_ALIAS,
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                        )
                            .setKeySize(256)
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .build(),
                    )
                    generateKey()
                }
            }
            CorruptState.TAMPERED_DATABASE -> context.getDatabasePath(HerdroidDatabase.DATABASE_NAME).run {
                val bytes = readBytes()
                bytes[0] = (bytes[0].toInt() xor 1).toByte()
                writeBytes(bytes)
                bytes.fill(0)
            }
        }
    }

    private fun databaseArtifacts(): List<File> {
        val database = context.getDatabasePath(HerdroidDatabase.DATABASE_NAME)
        return listOf(
            database,
            File(database.path + "-wal"),
            File(database.path + "-shm"),
            File(database.path + "-journal"),
        )
    }

    private fun snapshotArtifacts(): List<ByteArray?> =
        databaseArtifacts().map { artifact -> artifact.takeIf(File::exists)?.readBytes() }

    private fun assertArtifactsEqual(state: CorruptState, expected: List<ByteArray?>, actual: List<ByteArray?>) {
        expected.zip(actual).forEachIndexed { index, (before, after) ->
            assertEquals("${state.name}: artifact $index existence changed", before != null, after != null)
            if (before != null && after != null) {
                assertArrayEquals("${state.name}: artifact $index changed", before, after)
            }
        }
    }

    private enum class CorruptState {
        MISSING_IV,
        MISSING_CIPHERTEXT,
        MALFORMED_BASE64,
        FLIPPED_CIPHERTEXT_TAG,
        DELETED_ALIAS,
        REPLACED_ALIAS,
        TAMPERED_DATABASE,
    }

    private companion object {
        val PUBLIC_KEY = byteArrayOf(1, 2, 3, 4, 5)
    }
}
