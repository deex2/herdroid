package dev.herdroid.core.ssh

import dev.herdroid.core.model.HostKeyCandidate
import dev.herdroid.core.model.HostKeyDecision
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.CoroutineDispatcher
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.DirectConnection
import net.schmizz.sshj.sftp.SFTPClient

class HostKeyApprovalRequired(
    val approval: HostKeyDecision.Ask,
    cause: Throwable,
) : IOException("Host key approval is required for ${approval.candidate.hop.name.lowercase()}", cause) {
    val candidate: HostKeyCandidate = approval.candidate
}

class HostKeyChangedException(
    val rejection: HostKeyDecision.RejectChanged,
    cause: Throwable,
) : IOException("Saved host key does not match the presented host key", cause)

internal inline fun ignoreCloseFailure(block: () -> Unit) {
    try {
        block()
    } catch (_: Exception) {
    }
}

internal fun CountDownLatch.awaitPreservingInterrupt() {
    var interrupted = false
    while (true) {
        try {
            await()
            break
        } catch (_: InterruptedException) {
            interrupted = true
        }
    }
    if (interrupted) Thread.currentThread().interrupt()
}

class ConnectedRoute internal constructor(
    val target: SSHClient,
    val jump: SSHClient?,
    private val direct: DirectConnection?,
    private val ioDispatcher: CoroutineDispatcher,
) : Closeable {
    private val lock = Any()
    private val closeFinished = CountDownLatch(1)
    private val processes = linkedSetOf<RemoteProcess>()
    private val sftpClients = linkedSetOf<Closeable>()
    private var closeState = CloseState.OPEN
    private var closingThread: Thread? = null

    fun exec(command: String): RemoteProcess = synchronized(lock) {
        check(closeState == CloseState.OPEN) { "SSH route is closed" }
        val session = target.startSession()
        try {
            val remoteCommand = session.exec(command)
            lateinit var process: RemoteProcess
            process = RemoteProcess(
                session = session,
                command = remoteCommand,
                ioDispatcher = ioDispatcher,
                onClose = { unregister(process) },
            )
            processes += process
            process
        } catch (failure: Throwable) {
            ignoreCloseFailure(session::close)
            throw failure
        }
    }

    fun sftp(): SFTPClient = synchronized(lock) {
        check(closeState == CloseState.OPEN) { "SSH route is closed" }
        target.newSFTPClient().also(::registerSftpLocked)
    }

    internal fun registerSftp(sftp: Closeable) = synchronized(lock) {
        check(closeState == CloseState.OPEN) { "SSH route is closed" }
        registerSftpLocked(sftp)
    }

    override fun close() {
        val owned = synchronized(lock) {
            when (closeState) {
                CloseState.OPEN -> {
                    closeState = CloseState.CLOSING
                    closingThread = Thread.currentThread()
                    Pair(processes.toList().asReversed(), sftpClients.toList().asReversed()).also {
                        processes.clear()
                        sftpClients.clear()
                    }
                }
                CloseState.CLOSING -> {
                    if (closingThread === Thread.currentThread()) return
                    null
                }
                CloseState.CLOSED -> return
            }
        }
        if (owned == null) {
            closeFinished.awaitPreservingInterrupt()
            return
        }
        try {
            owned.first.forEach { ignoreCloseFailure(it::close) }
            owned.second.forEach { ignoreCloseFailure(it::close) }
            ignoreCloseFailure(target::close)
            ignoreCloseFailure { direct?.close() }
            ignoreCloseFailure { jump?.close() }
        } finally {
            synchronized(lock) {
                closeState = CloseState.CLOSED
                closingThread = null
            }
            closeFinished.countDown()
        }
    }

    private fun registerSftpLocked(sftp: Closeable) {
        sftpClients += sftp
    }

    private fun unregister(process: RemoteProcess) = synchronized(lock) {
        processes -= process
        Unit
    }

    private enum class CloseState {
        OPEN,
        CLOSING,
        CLOSED,
    }
}
