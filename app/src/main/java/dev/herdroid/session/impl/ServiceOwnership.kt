package dev.herdroid.session.impl

internal class ServiceOwnership(
    private val stopStarted: (Int) -> Unit,
    private val disconnectBound: () -> Unit,
    private val stopTerminal: (Int) -> Unit,
) {
    private var bindings = 0
    private var latestStartId: Int? = null

    private var isForeground = false

    val foreground: Boolean
        @Synchronized get() = isForeground

    @Synchronized
    fun bound() {
        bindings += 1
    }

    @Synchronized
    fun unbound() {
        bindings = (bindings - 1).coerceAtLeast(0)
        if (bindings == 0 && !isForeground) disconnectBound()
    }

    @Synchronized
    fun started(startId: Int) {
        latestStartId = startId
    }

    @Synchronized
    fun isCurrent(startId: Int): Boolean = latestStartId == startId

    @Synchronized
    fun promoted(startId: Int, accepted: Boolean): Boolean {
        if (!isCurrent(startId)) return false
        isForeground = accepted
        if (!accepted) stopStarted(startId)
        return true
    }

    @Synchronized
    fun terminal(startId: Int) {
        if (isCurrent(startId)) stopTerminal(startId)
    }

    @Synchronized
    fun stopped() {
        isForeground = false
    }
}
