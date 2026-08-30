package dev.herdroid.core.ssh

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import net.schmizz.sshj.connection.ConnectionException
import net.schmizz.sshj.connection.channel.direct.Session

data class RemoteExit(
    val status: Int?,
    val diagnosticStderr: ByteArray,
)

class SshCommandTimeoutException(
    val timeoutMillis: Long,
) : IOException("Remote command did not finish within $timeoutMillis ms")

class RemoteProcess(
    val stdin: OutputStream,
    private val stdout: InputStream,
    private val stderr: InputStream,
    private val waitFor: (Long) -> Boolean,
    private val exitStatus: () -> Int?,
    private val closeCommand: () -> Unit,
    private val closeSession: () -> Unit,
    private val onClose: () -> Unit,
    private val ioDispatcher: CoroutineDispatcher,
    private val defaultTimeoutMillis: Long = SshConnector.COMMAND_TIMEOUT_MILLIS,
) : Closeable {
    private val lock = Any()
    private val closeFinished = CountDownLatch(1)
    private var closeState = CloseState.OPEN
    private var closingThread: Thread? = null
    private var awaitStarted = false
    private var stdoutTaken = false
    private val stdoutFinished = CompletableDeferred<Unit>()

    internal constructor(
        session: Session,
        command: Session.Command,
        ioDispatcher: CoroutineDispatcher,
        defaultTimeoutMillis: Long = SshConnector.COMMAND_TIMEOUT_MILLIS,
        onClose: () -> Unit,
    ) : this(
        stdin = command.outputStream,
        stdout = command.inputStream,
        stderr = command.errorStream,
        waitFor = { timeoutMillis ->
            command.join(timeoutMillis, TimeUnit.MILLISECONDS)
            !command.isOpen
        },
        exitStatus = { command.exitStatus },
        closeCommand = { command.close() },
        closeSession = { session.close() },
        onClose = onClose,
        ioDispatcher = ioDispatcher,
        defaultTimeoutMillis = defaultTimeoutMillis,
    )

    /**
     * Transfers stdout to one caller. That caller must consume it concurrently until EOF;
     * [awaitExit] will neither install a competing reader nor close stdout before owner completion.
     */
    fun takeStdout(): InputStream = synchronized(lock) {
        check(closeState == CloseState.OPEN && !awaitStarted) {
            "Stdout can only be taken before awaiting or closing"
        }
        check(!stdoutTaken) { "Stdout has already been taken" }
        stdoutTaken = true
        OwnedStdout(stdout)
    }

    suspend fun awaitExit(timeoutMillis: Long = defaultTimeoutMillis): RemoteExit = supervisorScope {
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        val drainStdout = synchronized(lock) {
            check(closeState == CloseState.OPEN) { "Remote process is closed" }
            check(!awaitStarted) { "Remote process is already being awaited" }
            awaitStarted = true
            !stdoutTaken
        }
        val stdoutDrain = if (drainStdout) {
            async(ioDispatcher) { drain(stdout) }
        } else {
            null
        }
        val diagnostic = async(ioDispatcher) { readDiagnosticStderr() }
        try {
            val completed = waitForExit(timeoutMillis)
            if (!completed) {
                close()
                stdoutDrain?.let { runCatching { it.await() } }
                runCatching { diagnostic.await() }
                throw SshCommandTimeoutException(timeoutMillis)
            }
            stdoutDrain?.await()
            val result = RemoteExit(exitStatus(), diagnostic.await())
            if (!drainStdout) stdoutFinished.await()
            close()
            result
        } catch (failure: Throwable) {
            close()
            throw failure
        }
    }

    override fun close() {
        val cleanup = synchronized(lock) {
            when (closeState) {
                CloseState.OPEN -> {
                    closeState = CloseState.CLOSING
                    closingThread = Thread.currentThread()
                    true
                }
                CloseState.CLOSING -> {
                    if (closingThread === Thread.currentThread()) return
                    false
                }
                CloseState.CLOSED -> return
            }
        }
        if (!cleanup) {
            closeFinished.awaitPreservingInterrupt()
            return
        }
        try {
            stdoutFinished.complete(Unit)
            ignoreCloseFailure(stdin::close)
            ignoreCloseFailure(closeCommand)
            ignoreCloseFailure(stdout::close)
            ignoreCloseFailure(stderr::close)
            ignoreCloseFailure(closeSession)
            ignoreCloseFailure(onClose)
        } finally {
            synchronized(lock) {
                closeState = CloseState.CLOSED
                closingThread = null
            }
            closeFinished.countDown()
        }
    }

    private fun readDiagnosticStderr(): ByteArray {
        val captured = ByteArrayOutputStream(STDERR_LIMIT_BYTES)
        val buffer = ByteArray(8192)
        var remaining = STDERR_LIMIT_BYTES
        while (true) {
            val read = stderr.read(buffer)
            if (read < 0) break
            if (remaining > 0) {
                val retained = minOf(read, remaining)
                captured.write(buffer, 0, retained)
                remaining -= retained
            }
        }
        return captured.toByteArray()
    }

    private fun drain(stream: InputStream) {
        val buffer = ByteArray(8192)
        while (stream.read(buffer) >= 0) {
            // Deliberately discard stdout without accumulating it.
        }
    }

    private suspend fun waitForExit(timeoutMillis: Long): Boolean = withContext(ioDispatcher) {
        var remaining = timeoutMillis
        while (remaining > 0) {
            currentCoroutineContext().ensureActive()
            val slice = minOf(remaining, WAIT_SLICE_MILLIS)
            val completed = try {
                waitFor(slice)
            } catch (failure: ConnectionException) {
                if (failure.cause is TimeoutException) false else throw failure
            }
            if (completed) return@withContext true
            remaining -= slice
        }
        false
    }

    private inner class OwnedStdout(input: InputStream) : FilterInputStream(input) {
        override fun read(): Int = completeAtEof(super.read())

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            completeAtEof(super.read(buffer, offset, length))

        override fun close() {
            try {
                super.close()
            } finally {
                stdoutFinished.complete(Unit)
            }
        }

        private fun completeAtEof(read: Int) = read.also {
            if (it < 0) stdoutFinished.complete(Unit)
        }
    }

    private companion object {
        const val STDERR_LIMIT_BYTES = 64 * 1024
        const val WAIT_SLICE_MILLIS = 100L
    }

    private enum class CloseState {
        OPEN,
        CLOSING,
        CLOSED,
    }
}
