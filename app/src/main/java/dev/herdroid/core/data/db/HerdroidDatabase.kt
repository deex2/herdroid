package dev.herdroid.core.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import dev.herdroid.core.data.RouteDao
import java.io.File
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

internal sealed interface DatabaseOpenResult

internal class LocalDataReady(val database: HerdroidDatabase) : DatabaseOpenResult

internal data object LocalDataUnavailable : DatabaseOpenResult

internal fun closeAfterFailedDatabaseOpen(
    closeDatabase: (() -> Unit)?,
    helperFactory: AutoCloseable?,
) {
    try {
        closeDatabase?.invoke()
    } catch (_: Exception) {
        // Preserve the original open failure.
    } finally {
        try {
            helperFactory?.close()
        } catch (_: Exception) {
            // The helper wipes its passphrase in close's finally block.
        }
    }
}

internal class PassphraseOwningOpenHelperFactory(
    private val passphrase: ByteArray,
    private val delegateFactory: SupportSQLiteOpenHelper.Factory? = null,
    private val onClosed: () -> Unit = {},
) : SupportSQLiteOpenHelper.Factory, AutoCloseable {
    private var helper: CloseAwareOpenHelper? = null
    private var closed = false
    private var ownershipReleased = false

    init {
        require(passphrase.size == 32)
    }

    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper =
        create {
            (delegateFactory ?: SupportOpenHelperFactory(passphrase)).create(configuration)
        }

    @Synchronized
    internal fun create(createDelegate: () -> SupportSQLiteOpenHelper): SupportSQLiteOpenHelper {
        check(!closed) { "Database helper factory is closed" }
        check(helper == null) { "Database helper factory already created a helper" }
        return try {
            CloseAwareOpenHelper(createDelegate(), ::helperClosed).also { helper = it }
        } catch (failure: Throwable) {
            close()
            throw failure
        }
    }

    override fun close() {
        val helperToClose = synchronized(this) {
            if (closed) return
            closed = true
            helper.also { helper = null }
        }
        try {
            helperToClose?.close()
        } finally {
            releaseOwnership()
        }
    }

    private fun helperClosed() {
        synchronized(this) {
            closed = true
            helper = null
        }
        releaseOwnership()
    }

    private fun releaseOwnership() {
        val notify = synchronized(this) {
            if (ownershipReleased) {
                false
            } else {
                ownershipReleased = true
                passphrase.fill(0)
                true
            }
        }
        if (notify) onClosed()
    }

    private class CloseAwareOpenHelper(
        private val delegate: SupportSQLiteOpenHelper,
        private val onClosed: () -> Unit,
    ) : SupportSQLiteOpenHelper {
        private var closed = false

        override val databaseName: String?
            get() = delegate.databaseName
        override val writableDatabase: SupportSQLiteDatabase
            get() = open { delegate.writableDatabase }
        override val readableDatabase: SupportSQLiteDatabase
            get() = open { delegate.readableDatabase }

        override fun setWriteAheadLoggingEnabled(enabled: Boolean) =
            delegate.setWriteAheadLoggingEnabled(enabled)

        @Synchronized
        private fun open(block: () -> SupportSQLiteDatabase): SupportSQLiteDatabase =
            try {
                check(!closed) { "Database helper is closed" }
                block()
            } catch (failure: Throwable) {
                try {
                    close()
                } catch (closeFailure: Throwable) {
                    failure.addSuppressed(closeFailure)
                }
                throw failure
            }

        @Synchronized
        override fun close() {
            if (closed) return
            closed = true
            try {
                delegate.close()
            } finally {
                onClosed()
            }
        }
    }
}

internal fun passphraseOwningOpenHelperFactory(
    passphrase: ByteArray,
    delegateFactory: SupportSQLiteOpenHelper.Factory? = null,
    onClosed: () -> Unit = {},
): PassphraseOwningOpenHelperFactory = try {
    PassphraseOwningOpenHelperFactory(passphrase.copyOf(), delegateFactory, onClosed)
} finally {
    passphrase.fill(0)
}

@Database(
    entities = [SshKeyEntity::class, EndpointEntity::class, RouteEntity::class, KnownHostEntity::class],
    version = 3,
    exportSchema = false,
)
internal abstract class HerdroidDatabase : RoomDatabase() {
    abstract fun routeDao(): RouteDao

    companion object {
        internal const val DATABASE_NAME = "herdroid.db"

        internal fun open(context: Context): DatabaseOpenResult = open(context) {}

        @Synchronized
        internal fun open(
            context: Context,
            onClosed: () -> Unit = {},
        ): DatabaseOpenResult {
            val appContext = context.applicationContext
            val databasePath = appContext.getDatabasePath(DATABASE_NAME)
            var database: HerdroidDatabase? = null
            var passphrase: ByteArray? = null
            var helperFactory: PassphraseOwningOpenHelperFactory? = null
            return try {
                passphrase = SecretStore(appContext).loadOrCreate(databaseArtifactsExist(databasePath))
                System.loadLibrary("sqlcipher")
                helperFactory = passphraseOwningOpenHelperFactory(passphrase, onClosed = onClosed)
                database = Room.databaseBuilder(appContext, HerdroidDatabase::class.java, DATABASE_NAME)
                    .openHelperFactory(helperFactory)
                    .build()
                database.openHelper.writableDatabase
                LocalDataReady(database)
            } catch (_: Exception) {
                closeAfterFailedDatabaseOpen(database?.let { { it.close() } }, helperFactory)
                LocalDataUnavailable
            } catch (_: LinkageError) {
                closeAfterFailedDatabaseOpen(database?.let { { it.close() } }, helperFactory)
                LocalDataUnavailable
            } finally { passphrase?.fill(0) }
        }

        private fun databaseArtifactsExist(database: File): Boolean =
            listOf(database, File(database.path + "-wal"), File(database.path + "-shm"), File(database.path + "-journal"))
                .any(File::exists)

    }
}
