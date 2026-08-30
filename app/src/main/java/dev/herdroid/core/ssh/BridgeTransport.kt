package dev.herdroid.core.ssh

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

const val MAX_BRIDGE_OUTPUT_BYTES = 1_048_576

data class RemoteCommandResult(val exitStatus: Int?, val stdout: String, val stderr: String = "")

interface BridgeTransport {
    suspend fun exec(command: String): RemoteCommandResult
    suspend fun upload(path: String, bytes: ByteArray)
    suspend fun chmod(path: String, mode: Int)
    suspend fun read(path: String, maxBytes: Int): ByteArray
}

class ConnectedRouteBridgeTransport(
    private val route: ConnectedRoute,
    private val ioDispatcher: CoroutineDispatcher,
) : BridgeTransport {
    override suspend fun exec(command: String): RemoteCommandResult = coroutineScope {
        val process = route.exec(command)
        val stdout = process.takeStdout()
        val output = async(ioDispatcher) { readBounded(stdout) }
        val exit = process.awaitExit()
        RemoteCommandResult(exit.status, output.await().decodeToString(), exit.diagnosticStderr.decodeToString())
    }

    override suspend fun upload(path: String, bytes: ByteArray) = withContext(ioDispatcher) {
        val local = File.createTempFile("herdroid-bridge", ".upload")
        try {
            local.writeBytes(bytes)
            route.sftp().use { it.put(local.absolutePath, path) }
        } finally {
            local.delete()
        }
    }

    override suspend fun chmod(path: String, mode: Int) = withContext(ioDispatcher) {
        route.sftp().use { it.chmod(path, mode) }
    }

    override suspend fun read(path: String, maxBytes: Int): ByteArray = withContext(ioDispatcher) {
        route.sftp().use { sftp ->
            sftp.open(path).use { remote ->
                require(remote.length() <= maxBytes.toLong()) { "Remote file exceeds limit" }
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var offset = 0L
                while (true) {
                    val remaining = maxBytes + 1 - output.size()
                    val count = remote.read(offset, buffer, 0, minOf(buffer.size, remaining))
                    if (count < 0) break
                    require(count > 0) { "Remote file read made no progress" }
                    output.write(buffer, 0, count)
                    require(output.size() <= maxBytes) { "Remote file exceeds limit" }
                    offset += count
                }
                output.toByteArray()
            }
        }
    }

    private fun readBounded(stream: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) return output.toByteArray()
            require(output.size() + count <= MAX_BRIDGE_OUTPUT_BYTES) { "Remote command output exceeds limit" }
            output.write(buffer, 0, count)
        }
    }
}
