package dev.herdroid.core.model

sealed interface TerminalState {
    data object Detached : TerminalState
    data object Attaching : TerminalState
    data class Interactive(val cols: Int, val rows: Int, val nextSeq: Long) : TerminalState
    data class AttachFailed(val diagnostic: String?) : TerminalState
    data class Closed(val diagnostic: String?) : TerminalState
}

class TerminalFrame(
    val seq: Long,
    val cols: Int,
    val rows: Int,
    val full: Boolean,
    bytes: ByteArray,
) {
    private val content = bytes.copyOf()
    val bytes: ByteArray get() = content.copyOf()

    override fun equals(other: Any?) = other is TerminalFrame &&
        seq == other.seq && cols == other.cols && rows == other.rows && full == other.full &&
        content.contentEquals(other.content)

    override fun hashCode() = 31 * (31 * (31 * (31 * seq.hashCode() + cols) + rows) + full.hashCode()) +
        content.contentHashCode()

    override fun toString() = "TerminalFrame(seq=$seq, cols=$cols, rows=$rows, full=$full, bytes=${content.size})"
}

enum class TerminalScrollDirection { UP, DOWN }

enum class TerminalScrollSource { WHEEL, PAGE_KEY }

data class TerminalAttachmentKey(
    val sessionId: String,
    val sessionEpoch: String,
    val sessionIncarnation: Long,
    val paneId: String,
    val attachOrdinal: Long,
)
