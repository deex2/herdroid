package dev.herdroid.session.api

interface ConnectionCommands {
    fun connect(routeId: Long)
    fun disconnect()
    fun approveTrust(accept: Boolean)
    fun approveHostKeyReset(accept: Boolean)
    fun approveInstall(accept: Boolean)
}
