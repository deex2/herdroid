package dev.herdroid.core.data

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import dev.herdroid.core.data.db.PassphraseOwningOpenHelperFactory
import dev.herdroid.core.data.db.closeAfterFailedDatabaseOpen
import dev.herdroid.core.data.db.passphraseOwningOpenHelperFactory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class PassphraseOwningOpenHelperFactoryTest {
    @Test
    fun ownership_transfer_clears_caller_copy_and_retains_only_the_pool_copy() {
        val caller = ByteArray(32) { 7 }
        val factory = passphraseOwningOpenHelperFactory(caller)

        assertArrayEquals(ByteArray(32), caller)

        factory.close()
    }

    @Test
    fun late_pool_getters_keep_working_until_explicit_close_wipes_the_pool_key() {
        val poolKey = ByteArray(32) { 7 }
        val delegate = FakeOpenHelper(database = fakeDatabase())
        val helper = PassphraseOwningOpenHelperFactory(poolKey).create { delegate }

        helper.writableDatabase
        helper.readableDatabase

        assertArrayEquals(ByteArray(32) { 7 }, poolKey)
        assertEquals(1, delegate.writableOpenCalls)
        assertEquals(1, delegate.readableOpenCalls)
        helper.close()
        assertArrayEquals(ByteArray(32), poolKey)
    }

    @Test
    fun create_failure_clears_owned_passphrase() {
        val source = ByteArray(32) { 7 }
        val ownedPassphrase = source.copyOf()
        var closeCalls = 0
        val factory = PassphraseOwningOpenHelperFactory(ownedPassphrase, onClosed = { closeCalls++ })

        assertThrows(IllegalStateException::class.java) { factory.create { error("create failed") } }
        val rejected = assertThrows(IllegalStateException::class.java) { factory.create { FakeOpenHelper() } }
        factory.close()

        assertArrayEquals(ByteArray(32) { 7 }, source)
        assertArrayEquals(ByteArray(32), ownedPassphrase)
        assertEquals("Database helper factory is closed", rejected.message)
        assertEquals(1, closeCalls)
    }

    @Test
    fun close_before_create_wipes_once_and_permanently_rejects_create() {
        val passphrase = ByteArray(32) { 7 }
        var closeCalls = 0
        var createCalls = 0
        val factory = PassphraseOwningOpenHelperFactory(passphrase, onClosed = { closeCalls++ })

        factory.close()
        factory.close()
        val rejected = assertThrows(IllegalStateException::class.java) { factory.create { createCalls++; FakeOpenHelper() } }

        assertArrayEquals(ByteArray(32), passphrase)
        assertEquals("Database helper factory is closed", rejected.message)
        assertEquals(0, createCalls)
        assertEquals(1, closeCalls)
    }

    @Test
    fun factory_close_after_create_closes_delegate_and_wipes_once() {
        val passphrase = ByteArray(32) { 7 }
        val delegate = FakeOpenHelper()
        var closeCalls = 0
        val factory = PassphraseOwningOpenHelperFactory(passphrase, onClosed = { closeCalls++ })
        factory.create { delegate }

        factory.close()
        factory.close()
        val rejected = assertThrows(IllegalStateException::class.java) { factory.create { FakeOpenHelper() } }

        assertArrayEquals(ByteArray(32), passphrase)
        assertEquals("Database helper factory is closed", rejected.message)
        assertEquals(1, delegate.closeCalls)
        assertEquals(1, closeCalls)
    }

    @Test
    fun factory_and_helper_close_race_returns_without_lock_inversion_and_releases_once() {
        val passphrase = ByteArray(32) { 7 }
        val delegateCloseEntered = CountDownLatch(1)
        val releaseDelegateClose = CountDownLatch(1)
        val delegate = FakeOpenHelper(
            onClose = {
                delegateCloseEntered.countDown()
                check(releaseDelegateClose.await(5, TimeUnit.SECONDS))
            },
        )
        val notifications = AtomicInteger()
        val factory = PassphraseOwningOpenHelperFactory(passphrase, onClosed = notifications::incrementAndGet)
        val helper = factory.create { delegate }
        val helperReturned = CountDownLatch(1)
        val factoryReturned = CountDownLatch(1)
        val createReturned = CountDownLatch(1)
        val helperFailure = AtomicReference<Throwable?>()
        val factoryFailure = AtomicReference<Throwable?>()
        val createFailure = AtomicReference<Throwable?>()

        daemonThread {
            helperFailure.set(runCatching { helper.close() }.exceptionOrNull())
            helperReturned.countDown()
        }.start()
        assertTrue(delegateCloseEntered.await(5, TimeUnit.SECONDS))
        val factoryThread = daemonThread {
            factoryFailure.set(runCatching { factory.close() }.exceptionOrNull())
            factoryReturned.countDown()
        }
        factoryThread.start()
        assertTrue(awaitBlocked(factoryThread))
        daemonThread {
            createFailure.set(runCatching { factory.create { FakeOpenHelper() } }.exceptionOrNull())
            createReturned.countDown()
        }.start()

        val createReturnedPromptly = createReturned.await(1, TimeUnit.SECONDS)
        releaseDelegateClose.countDown()

        assertTrue("post-close create blocked on the factory lock", createReturnedPromptly)
        assertTrue("direct helper close did not return", helperReturned.await(1, TimeUnit.SECONDS))
        assertTrue("factory close did not return", factoryReturned.await(1, TimeUnit.SECONDS))
        assertEquals(null, helperFailure.get())
        assertEquals(null, factoryFailure.get())
        assertEquals("Database helper factory is closed", createFailure.get()?.message)
        assertEquals(1, delegate.closeCalls)
        assertArrayEquals(ByteArray(32), passphrase)
        assertEquals(1, notifications.get())
    }

    @Test
    fun close_clears_owned_passphrase_even_when_delegate_close_fails_and_is_idempotent() {
        val passphrase = ByteArray(32) { 7 }
        val delegate = FakeOpenHelper(closeFailure = IllegalStateException("close failed"))
        val helper = PassphraseOwningOpenHelperFactory(passphrase).create { delegate }

        assertThrows(IllegalStateException::class.java) { helper.close() }
        helper.close()

        assertArrayEquals(ByteArray(32), passphrase)
        assertEquals(1, delegate.closeCalls)
    }

    @Test
    fun getters_after_explicit_close_never_reopen_delegate_with_cleared_passphrase() {
        val passphrase = ByteArray(32) { 7 }
        val delegate = FakeOpenHelper()
        val helper = PassphraseOwningOpenHelperFactory(passphrase).create { delegate }

        helper.close()
        assertThrows(IllegalStateException::class.java) { helper.writableDatabase }
        assertThrows(IllegalStateException::class.java) { helper.readableDatabase }

        assertArrayEquals(ByteArray(32), passphrase)
        assertEquals(0, delegate.writableOpenCalls)
        assertEquals(0, delegate.readableOpenCalls)
    }

    @Test
    fun open_failure_closes_once_and_permanently_rejects_getters() {
        val passphrase = ByteArray(32) { 7 }
        val delegate = FakeOpenHelper(openFailure = IllegalStateException("open failed"))
        val helper = PassphraseOwningOpenHelperFactory(passphrase).create { delegate }

        assertThrows(IllegalStateException::class.java) { helper.writableDatabase }
        assertThrows(IllegalStateException::class.java) { helper.writableDatabase }
        assertThrows(IllegalStateException::class.java) { helper.readableDatabase }

        assertArrayEquals(ByteArray(32), passphrase)
        assertEquals(1, delegate.closeCalls)
        assertEquals(1, delegate.writableOpenCalls)
        assertEquals(0, delegate.readableOpenCalls)
    }

    @Test
    fun failed_database_close_still_closes_factory() {
        val passphrase = ByteArray(32) { 7 }
        val factory = PassphraseOwningOpenHelperFactory(passphrase)

        closeAfterFailedDatabaseOpen(
            closeDatabase = { error("database close failed") },
            helperFactory = factory,
        )

        assertArrayEquals(ByteArray(32), passphrase)
    }

    private fun daemonThread(block: () -> Unit) = Thread(block).apply { isDaemon = true }

    private fun awaitBlocked(thread: Thread): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (thread.state == Thread.State.BLOCKED) return true
            Thread.yield()
        }
        return false
    }

    private class FakeOpenHelper(
        private val openFailure: RuntimeException? = null,
        private val closeFailure: RuntimeException? = null,
        private val database: SupportSQLiteDatabase? = null,
        private val onClose: () -> Unit = {},
    ) : SupportSQLiteOpenHelper {
        var closeCalls = 0
        var writableOpenCalls = 0
        var readableOpenCalls = 0

        override val databaseName = "fake"
        override val writableDatabase: SupportSQLiteDatabase
            get() {
                writableOpenCalls++
                openFailure?.let { throw it }
                return requireNotNull(database)
            }
        override val readableDatabase: SupportSQLiteDatabase
            get() {
                readableOpenCalls++
                openFailure?.let { throw it }
                return requireNotNull(database)
            }

        override fun setWriteAheadLoggingEnabled(enabled: Boolean) = Unit

        override fun close() {
            closeCalls++
            onClose()
            closeFailure?.let { throw it }
        }
    }

    private fun fakeDatabase() = Proxy.newProxyInstance(
        SupportSQLiteDatabase::class.java.classLoader,
        arrayOf(SupportSQLiteDatabase::class.java),
    ) { _, method, _ ->
        when (method.returnType) {
            Boolean::class.javaPrimitiveType -> false
            Int::class.javaPrimitiveType -> 0
            Long::class.javaPrimitiveType -> 0L
            else -> null
        }
    } as SupportSQLiteDatabase
}
