package dev.herdroid.core.ssh

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import net.schmizz.sshj.connection.ConnectionException

class RemoteProcessTest {
    @Test
    fun `awaitExit concurrently drains large stdout and stderr without unbounded capture`() = runBlocking {
        val events = mutableListOf<String>()
        val stdoutBytes = ByteArray(256 * 1024) { (it % 239).toByte() }
        val stderrBytes = ByteArray(70 * 1024) { (it % 251).toByte() }
        val stdout = CountingInputStream("stdout", stdoutBytes, events)
        val stderr = CountingInputStream("stderr", stderrBytes, events)
        val stdin = RecordingOutputStream("stdin", events)
        val waitSlices = mutableListOf<Long>()
        val process = RemoteProcess(
            stdin = stdin,
            stdout = stdout,
            stderr = stderr,
            waitFor = { timeout ->
                waitSlices += timeout
                val stdoutDrained = stdout.drained.await(200, TimeUnit.MILLISECONDS)
                val stderrDrained = stderr.drained.await(200, TimeUnit.MILLISECONDS)
                stdoutDrained && stderrDrained
            },
            exitStatus = { 7 },
            closeCommand = { events += "command.close" },
            closeSession = { events += "session.close" },
            ioDispatcher = Dispatchers.Unconfined,
            onClose = { events += "unregister" },
        )

        assertSame(stdin, process.stdin)
        val exit = process.awaitExit()

        assertTrue(waitSlices.isNotEmpty())
        assertTrue(waitSlices.all { it in 1..100 })
        assertEquals(7, exit.status)
        assertEquals(64 * 1024, exit.diagnosticStderr.size)
        assertArrayEquals(stderrBytes.copyOf(64 * 1024), exit.diagnosticStderr)
        assertEquals(stdoutBytes.size, stdout.bytesRead)
        assertEquals(stderrBytes.size, stderr.bytesRead)
        assertEquals(
            listOf(
                "stdin.close",
                "command.close",
                "stdout.close",
                "stderr.close",
                "session.close",
                "unregister",
            ),
            events.takeLast(6),
        )
    }

    @Test
    fun `takeStdout transfers ownership once and awaitExit does not add a competing reader`() = runBlocking {
        val events = mutableListOf<String>()
        val stdout = CountingInputStream("stdout", ByteArray(1024) { it.toByte() }, events)
        val process = RemoteProcess(
            stdin = RecordingOutputStream("stdin", events),
            stdout = stdout,
            stderr = RecordingInputStream("stderr", byteArrayOf(), events),
            waitFor = { true },
            exitStatus = { 0 },
            closeCommand = { events += "command.close" },
            closeSession = { events += "session.close" },
            ioDispatcher = Dispatchers.Unconfined,
            onClose = { events += "unregister" },
        )

        val ownedStdout = process.takeStdout()
        assertThrows(IllegalStateException::class.java) { process.takeStdout() }
        assertEquals(1024, ownedStdout.readBytes().size)
        val readsAfterCallerFinished = stdout.readCalls

        process.awaitExit()

        assertEquals(readsAfterCallerFinished, stdout.readCalls)
    }

    @Test
    fun `every stdout owner read path completes successful await and closes once`() = runBlocking {
        val cases = listOf<Pair<String, (InputStream) -> ByteArray>>(
            "single-byte" to { input ->
                val value = input.read()
                check(input.read() == -1)
                byteArrayOf(value.toByte())
            },
            "bulk" to { input ->
                val buffer = ByteArray(2)
                val count = input.read(buffer)
                check(input.read(buffer) == -1)
                buffer.copyOf(count)
            },
            "readAllBytes" to InputStream::readAllBytes,
            "readNBytes" to { it.readNBytes(2) },
            "transferTo" to { input ->
                ByteArrayOutputStream().also { input.transferTo(it) }.toByteArray()
            },
            "explicit close" to { input -> input.close(); byteArrayOf() },
        )
        val failures = mutableListOf<String>()

        cases.forEach { (name, consume) ->
            val closes = AtomicInteger()
            val process = RemoteProcess(
                stdin = ByteArrayOutputStream(),
                stdout = ByteArrayInputStream(byteArrayOf(0x78)),
                stderr = ByteArrayInputStream(byteArrayOf()),
                waitFor = { true },
                exitStatus = { 0 },
                closeCommand = { closes.incrementAndGet() },
                closeSession = {},
                ioDispatcher = Dispatchers.Unconfined,
                onClose = {},
            )
            val failure = runCatching {
                val output = consume(process.takeStdout())
                val expected = if (name == "explicit close") byteArrayOf() else byteArrayOf(0x78)
                assertArrayEquals(expected, output)
                assertEquals(0, withTimeout(500) { process.awaitExit() }.status)
            }.exceptionOrNull()
            process.close()

            if (failure != null) failures += "$name: ${failure::class.java.simpleName}"
            if (closes.get() != 1) failures += "$name: ${closes.get()} closes"
        }

        assertEquals(emptyList<String>(), failures)
    }

    @Test
    fun `timeout closes the process and reports the bounded timeout`() = runBlocking {
        val events = mutableListOf<String>()
        val process = RemoteProcess(
            stdin = RecordingOutputStream("stdin", events),
            stdout = RecordingInputStream("stdout", byteArrayOf(), events),
            stderr = RecordingInputStream("stderr", byteArrayOf(), events),
            waitFor = { false },
            exitStatus = { null },
            closeCommand = { events += "command.close" },
            closeSession = { events += "session.close" },
            ioDispatcher = Dispatchers.Unconfined,
            onClose = { events += "unregister" },
        )

        val failure = assertThrows(SshCommandTimeoutException::class.java) {
            runBlocking { process.awaitExit(123L) }
        }

        assertEquals(123L, failure.timeoutMillis)
        assertEquals(
            listOf(
                "stdin.close",
                "command.close",
                "stdout.close",
                "stderr.close",
                "session.close",
                "unregister",
            ),
            events,
        )
    }

    @Test
    fun `wrapped join timeout keeps polling until the command closes`() = runBlocking {
        val attempts = AtomicInteger()
        val process = RemoteProcess(
            stdin = ByteArrayOutputStream(),
            stdout = ByteArrayInputStream(byteArrayOf()),
            stderr = ByteArrayInputStream(byteArrayOf()),
            waitFor = {
                if (attempts.incrementAndGet() == 1) {
                    throw ConnectionException(TimeoutException("slice elapsed"))
                }
                true
            },
            exitStatus = { 0 },
            closeCommand = {},
            closeSession = {},
            ioDispatcher = Dispatchers.Unconfined,
            onClose = {},
        )

        assertEquals(0, process.awaitExit(200).status)
        assertEquals(2, attempts.get())
    }

    @Test
    fun `non-timeout connection failure still propagates unchanged`() {
        val expected = ConnectionException("connection failed")
        val process = RemoteProcess(
            stdin = ByteArrayOutputStream(),
            stdout = ByteArrayInputStream(byteArrayOf()),
            stderr = ByteArrayInputStream(byteArrayOf()),
            waitFor = { throw expected },
            exitStatus = { 0 },
            closeCommand = {},
            closeSession = {},
            ioDispatcher = Dispatchers.Unconfined,
            onClose = {},
        )

        val actual = assertThrows(ConnectionException::class.java) {
            runBlocking { process.awaitExit(200) }
        }

        assertSame(expected, actual)
    }

    @Test
    fun `cancellation closes the process and ends the wait worker promptly`() = runBlocking {
        val events = mutableListOf<String>()
        val waitStarted = CountDownLatch(1)
        val waitEnded = CountDownLatch(1)
        val waitSlices = mutableListOf<Long>()
        val process = RemoteProcess(
            stdin = RecordingOutputStream("stdin", events),
            stdout = RecordingInputStream("stdout", byteArrayOf(), events),
            stderr = RecordingInputStream("stderr", byteArrayOf(), events),
            waitFor = { slice ->
                synchronized(waitSlices) { waitSlices += slice }
                waitStarted.countDown()
                try {
                    Thread.sleep(slice)
                    false
                } finally {
                    waitEnded.countDown()
                }
            },
            exitStatus = { null },
            closeCommand = { events += "command.close" },
            closeSession = { events += "session.close" },
            ioDispatcher = Dispatchers.Unconfined,
            onClose = { events += "unregister" },
        )

        val startedAt = System.nanoTime()
        val waiter = launch(Dispatchers.Default) { process.awaitExit(timeoutMillis = 500) }
        assertTrue(waitStarted.await(1, TimeUnit.SECONDS))
        waiter.cancel()
        withTimeout(1_000) { waiter.join() }
        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

        assertTrue("Cancellation took $elapsedMillis ms", elapsedMillis < 300)
        assertTrue(waitEnded.await(100, TimeUnit.MILLISECONDS))
        assertTrue(synchronized(waitSlices) { waitSlices.all { it in 1..100 } })
        assertTrue("command.close" in events)
        assertTrue("session.close" in events)
    }

    @Test
    fun `close is idempotent and still attempts later resources after close failure`() {
        val events = mutableListOf<String>()
        val process = RemoteProcess(
            stdin = object : OutputStream() {
                override fun write(value: Int) = Unit
                override fun close() {
                    events += "stdin.close"
                    throw IllegalStateException("broken stream")
                }
            },
            stdout = RecordingInputStream("stdout", byteArrayOf(), events),
            stderr = RecordingInputStream("stderr", byteArrayOf(), events),
            waitFor = { true },
            exitStatus = { 0 },
            closeCommand = { events += "command.close" },
            closeSession = { events += "session.close" },
            ioDispatcher = Dispatchers.Unconfined,
            onClose = { events += "unregister" },
        )

        process.close()
        process.close()

        assertEquals(
            listOf(
                "stdin.close",
                "command.close",
                "stdout.close",
                "stderr.close",
                "session.close",
                "unregister",
            ),
            events,
        )
    }

    @Test
    fun `concurrent close waits for winning cleanup and reentrant winner does not deadlock`() {
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        val commandCloseEntered = CountDownLatch(1)
        val releaseCommandClose = CountDownLatch(1)
        val firstReturned = CountDownLatch(1)
        val secondReturned = CountDownLatch(1)
        val secondInterrupted = AtomicBoolean()
        lateinit var process: RemoteProcess
        process = RemoteProcess(
            stdin = RecordingOutputStream("stdin", events),
            stdout = RecordingInputStream("stdout", byteArrayOf(), events),
            stderr = RecordingInputStream("stderr", byteArrayOf(), events),
            waitFor = { true },
            exitStatus = { 0 },
            closeCommand = {
                events += "command.close"
                process.close()
                events += "reentrant.returned"
                commandCloseEntered.countDown()
                releaseCommandClose.await()
                throw IllegalStateException("command close failed")
            },
            closeSession = { events += "session.close" },
            ioDispatcher = Dispatchers.Unconfined,
            onClose = { events += "unregister" },
        )

        Thread {
            process.close()
            firstReturned.countDown()
        }.start()
        assertTrue(commandCloseEntered.await(1, TimeUnit.SECONDS))
        val second = Thread {
            process.close()
            secondInterrupted.set(Thread.currentThread().isInterrupted)
            secondReturned.countDown()
        }.also(Thread::start)
        val secondReturnedBeforeCleanup = secondReturned.await(100, TimeUnit.MILLISECONDS)
        second.interrupt()
        releaseCommandClose.countDown()

        assertFalse("second close returned before cleanup", secondReturnedBeforeCleanup)
        assertTrue(firstReturned.await(1, TimeUnit.SECONDS))
        assertTrue(secondReturned.await(1, TimeUnit.SECONDS))
        assertTrue("waiting close did not restore interruption", secondInterrupted.get())
        assertEquals(1, events.count { it == "command.close" })
        assertEquals(1, events.count { it == "session.close" })
        assertEquals(1, events.count { it == "unregister" })
        assertTrue(events.indexOf("command.close") < events.indexOf("session.close"))
    }

    private open class RecordingInputStream(
        private val label: String,
        bytes: ByteArray,
        private val events: MutableList<String>,
    ) : ByteArrayInputStream(bytes) {
        override fun close() {
            events += "$label.close"
            super.close()
        }
    }

    private class CountingInputStream(
        label: String,
        bytes: ByteArray,
        events: MutableList<String>,
    ) : RecordingInputStream(label, bytes, events) {
        var bytesRead = 0
        var readCalls = 0
        val drained = CountDownLatch(1)

        override fun read(): Int = super.read().also {
            readCalls++
            if (it >= 0) bytesRead++ else drained.countDown()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            super.read(buffer, offset, length).also {
                readCalls++
                if (it > 0) bytesRead += it else if (it < 0) drained.countDown()
            }
    }

    private class RecordingOutputStream(
        private val label: String,
        private val events: MutableList<String>,
    ) : ByteArrayOutputStream() {
        override fun close() {
            events += "$label.close"
            super.close()
        }
    }
}
