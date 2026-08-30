package dev.herdroid.core.herdr.wire

import dev.herdroid.core.model.TerminalScrollDirection
import dev.herdroid.core.model.TerminalScrollSource
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal sealed interface TerminalServerRecord {
    @Serializable
    @SerialName("terminal.frame")
    data class Frame(
        val seq: Long,
        val encoding: String,
        val width: Int,
        val height: Int,
        val full: Boolean,
        val bytes: String,
    ) : TerminalServerRecord

    @Serializable
    @SerialName("terminal.closed")
    data class Closed(val reason: String? = null) : TerminalServerRecord
}

@Serializable
internal sealed interface TerminalClientRecord {
    @Serializable
    @SerialName("terminal.input")
    data class Input(
        val text: String? = null,
        val bytes: String? = null,
    ) : TerminalClientRecord

    @Serializable
    @SerialName("terminal.resize")
    data class Resize(
        val cols: Int,
        val rows: Int,
        @SerialName("cell_width_px") val cellWidthPx: Int,
        @SerialName("cell_height_px") val cellHeightPx: Int,
    ) : TerminalClientRecord

    @Serializable
    @SerialName("terminal.scroll")
    data class Scroll(
        val direction: TerminalScrollDirectionWire,
        val lines: Int,
        val source: TerminalScrollSourceWire,
        val column: Int? = null,
        val row: Int? = null,
        val modifiers: Int = 0,
    ) : TerminalClientRecord

    @Serializable
    @SerialName("terminal.release")
    data object Release : TerminalClientRecord
}

@Serializable
internal enum class TerminalScrollDirectionWire {
    @SerialName("up") UP,
    @SerialName("down") DOWN,
}

@Serializable
internal enum class TerminalScrollSourceWire {
    @SerialName("wheel") WHEEL,
    @SerialName("page_key") PAGE_KEY,
}

internal fun TerminalScrollDirection.toWire() = when (this) {
    TerminalScrollDirection.UP -> TerminalScrollDirectionWire.UP
    TerminalScrollDirection.DOWN -> TerminalScrollDirectionWire.DOWN
}

internal fun TerminalScrollSource.toWire() = when (this) {
    TerminalScrollSource.WHEEL -> TerminalScrollSourceWire.WHEEL
    TerminalScrollSource.PAGE_KEY -> TerminalScrollSourceWire.PAGE_KEY
}
