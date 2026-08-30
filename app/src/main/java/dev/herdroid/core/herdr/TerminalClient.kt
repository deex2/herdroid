package dev.herdroid.core.herdr

import dev.herdroid.core.herdr.wire.TerminalClientRecord
import dev.herdroid.core.herdr.wire.TerminalServerRecord
import dev.herdroid.core.herdr.wire.toWire
import dev.herdroid.core.model.RemoteOperatingSystem
import dev.herdroid.core.model.TerminalFrame
import dev.herdroid.core.model.TerminalScrollDirection
import dev.herdroid.core.model.TerminalScrollSource
import dev.herdroid.core.model.TerminalState
import dev.herdroid.core.ssh.ConnectedRoute
import dev.herdroid.core.ssh.RemoteProcess
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

private class TerminalProtocolException(message: String) : IOException(message)

class TerminalClient private constructor(
    private val process: RemoteProcess,
    private val input: InputStream,
    ioDispatcher: CoroutineDispatcher,
    defaultDispatcher: CoroutineDispatcher,
) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val writer = Any()
    private val released = AtomicBoolean()
    private val ended = AtomicBoolean()
    private val mutableState = MutableStateFlow<TerminalState>(TerminalState.Attaching)
    private val frameChannel = Channel<TerminalFrame>(capacity = 1)
    val state: StateFlow<TerminalState> = mutableState.asStateFlow()
    val frames: Flow<TerminalFrame> = frameChannel.receiveAsFlow()

    init {
        scope.launch(defaultDispatcher, start = CoroutineStart.UNDISPATCHED) {
            try {
                process.awaitExit(Long.MAX_VALUE)
            } catch (_: Exception) {
                // The stdout reader owns terminal state; awaitExit owns stderr and process exit.
            }
        }
        scope.launch { readRecords() }
    }

    fun sendText(text: String) = write(TerminalClientRecord.Input(text = text))

    fun sendBytes(bytes: ByteArray) = write(
        TerminalClientRecord.Input(bytes = Base64.getEncoder().encodeToString(bytes)),
    )

    fun resize(cols: Int, rows: Int, cellWidthPx: Int = 0, cellHeightPx: Int = 0) {
        require(cellWidthPx >= 0 && cellHeightPx >= 0) { "Cell dimensions must not be negative" }
        write(
            TerminalClientRecord.Resize(
                cols.coerceIn(MIN_DIMENSION, MAX_DIMENSION),
                rows.coerceIn(MIN_DIMENSION, MAX_DIMENSION),
                cellWidthPx,
                cellHeightPx,
            ),
        )
    }

    fun scroll(
        direction: TerminalScrollDirection,
        lines: Int,
        source: TerminalScrollSource = TerminalScrollSource.WHEEL,
        column: Int? = null,
        row: Int? = null,
        modifiers: Int = 0,
    ) {
        require(lines in 1..U16_MAX && column?.let { it in 0..U16_MAX } != false &&
            row?.let { it in 0..U16_MAX } != false && modifiers in 0..U8_MAX
        ) { "Invalid terminal scroll" }
        write(TerminalClientRecord.Scroll(direction.toWire(), lines, source.toWire(), column, row, modifiers))
    }

    fun release() {
        if (!released.compareAndSet(false, true)) return
        synchronized(writer) {
            if (ended.get()) return
            try {
                writeRecord(TerminalClientRecord.Release)
            } catch (failure: Exception) {
                complete(TerminalState.Closed(ABRUPT_DIAGNOSTIC))
                return
            }
        }
        complete(TerminalState.Detached)
    }

    override fun close() = release()

    private fun write(record: TerminalClientRecord) {
        check(!released.get() && !ended.get()) { "Terminal is closed" }
        synchronized(writer) {
            check(!released.get() && !ended.get()) { "Terminal is closed" }
            try {
                writeRecord(record)
            } catch (failure: Exception) {
                complete(TerminalState.Closed(ABRUPT_DIAGNOSTIC))
                throw failure
            }
        }
    }

    private fun writeRecord(record: TerminalClientRecord) {
        val bytes = (json.encodeToString(TerminalClientRecord.serializer(), record) + "\n").encodeToByteArray()
        process.stdin.write(bytes)
        process.stdin.flush()
    }

    private suspend fun readRecords() {
        var nextSeq: Long? = null
        try {
            while (!released.get()) {
                val line = readLine() ?: break
                when (val record = decode(line)) {
                    is TerminalServerRecord.Closed -> {
                        terminalState(nextSeq, record.reason)
                        return
                    }
                    is TerminalServerRecord.Frame -> {
                        require(record.encoding == "ansi") { "Unsupported terminal encoding" }
                        require(record.width in 1..U16_MAX && record.height in 1..U16_MAX) {
                            "Invalid terminal frame size"
                        }
                        require(record.seq >= 0 && record.seq < Long.MAX_VALUE) { "Invalid terminal sequence" }
                        if (nextSeq == null) {
                            require(record.full) { "First terminal frame must be full" }
                        } else {
                            require(record.seq == nextSeq) { "Terminal frame sequence gap" }
                        }
                        val bytes = try {
                            Base64.getDecoder().decode(record.bytes)
                        } catch (_: IllegalArgumentException) {
                            throw TerminalProtocolException("Invalid terminal frame bytes")
                        }
                        nextSeq = record.seq + 1
                        frameChannel.send(
                            TerminalFrame(record.seq, record.width, record.height, record.full, bytes),
                        )
                        mutableState.value = TerminalState.Interactive(record.width, record.height, nextSeq)
                    }
                }
            }
            if (!released.get()) terminalState(nextSeq, null)
        } catch (_: Throwable) {
            if (!released.get()) terminalState(nextSeq, PROTOCOL_DIAGNOSTIC)
        }
    }

    private fun terminalState(nextSeq: Long?, diagnostic: String?) {
        if (released.get()) return
        complete(if (nextSeq == null) {
            TerminalState.AttachFailed(diagnostic)
        } else {
            TerminalState.Closed(diagnostic ?: ABRUPT_DIAGNOSTIC)
        })
    }

    private fun complete(state: TerminalState) {
        if (!ended.compareAndSet(false, true)) return
        frameChannel.close()
        process.close()
        scope.cancel()
        mutableState.value = state
    }

    private fun decode(line: String): TerminalServerRecord = try {
        json.decodeFromString(TerminalServerRecord.serializer(), line)
    } catch (_: Throwable) {
        throw TerminalProtocolException("Invalid terminal record")
    }

    private fun readLine(): String? {
        val bytes = ByteArrayOutputStream(256)
        while (true) {
            val next = input.read()
            if (next < 0) break
            if (next == '\n'.code) return decodeUtf8(bytes.toByteArray())
            if (bytes.size() == MAX_LINE_BYTES) throw TerminalProtocolException("Terminal record exceeds limit")
            bytes.write(next)
        }
        if (bytes.size() == 0) return null
        throw TerminalProtocolException("Terminal output ended mid-record")
    }

    private fun decodeUtf8(bytes: ByteArray): String = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: Exception) {
        throw TerminalProtocolException("Invalid terminal UTF-8")
    }

    companion object {
        // Stock Herdr permits 32 MiB frames; Base64 plus the JSON envelope must still be bounded.
        const val MAX_LINE_BYTES = 45 * 1_048_576
        private const val MIN_DIMENSION = 1
        private const val MAX_DIMENSION = 1_000
        private const val U16_MAX = 65_535
        private const val U8_MAX = 255
        const val ABRUPT_DIAGNOSTIC =
            "Terminal connection ended unexpectedly; remote geometry may remain at the phone size."
        private const val PROTOCOL_DIAGNOSTIC = "Invalid terminal protocol record."
        private val json = Json {
            classDiscriminator = "type"
            ignoreUnknownKeys = true
        }

        fun attach(
            route: ConnectedRoute,
            remoteOs: RemoteOperatingSystem,
            herdrPath: String,
            session: String,
            pane: String,
            cols: Int,
            rows: Int,
            ioDispatcher: CoroutineDispatcher,
            defaultDispatcher: CoroutineDispatcher,
            takeover: Boolean = false,
        ): TerminalClient = start(
            route.exec(command(remoteOs, herdrPath, session, pane, cols, rows, takeover)),
            ioDispatcher,
            defaultDispatcher,
        )

        fun start(
            process: RemoteProcess,
            ioDispatcher: CoroutineDispatcher,
            defaultDispatcher: CoroutineDispatcher,
        ) = TerminalClient(process, process.takeStdout().buffered(), ioDispatcher, defaultDispatcher)

        internal fun command(
            remoteOs: RemoteOperatingSystem,
            herdrPath: String,
            session: String,
            pane: String,
            cols: Int,
            rows: Int,
            takeover: Boolean,
        ): String {
            require(herdrPath.isNotBlank()) { "Invalid Herdr path" }
            require(BridgeIdentifiers.validSession(session)) { "Invalid session id" }
            require(BridgeIdentifiers.validPane(pane)) { "Invalid pane id" }
            val arguments = mutableListOf(
                "--session",
                session,
                "terminal",
                "session",
                "control",
                pane,
                "--cols",
                cols.coerceIn(MIN_DIMENSION, MAX_DIMENSION).toString(),
                "--rows",
                rows.coerceIn(MIN_DIMENSION, MAX_DIMENSION).toString(),
            )
            if (takeover) arguments += "--takeover"
            return RemoteCommands.herdr(remoteOs, herdrPath, *arguments.toTypedArray())
        }
    }
}
