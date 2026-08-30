package dev.herdroid.core.herdr

internal object BridgeIdentifiers {
    private val session = Regex("[A-Za-z0-9._-]{1,64}")
    private val pane = Regex("[A-Za-z0-9._:-]{1,128}")

    fun validSession(value: String) = session.matches(value)
    fun validPane(value: String) = pane.matches(value)
}
