package dev.herdroid.core.model

enum class RemoteOperatingSystem { LINUX, WINDOWS }

data class BridgeApproval(
    val routeName: String,
    val os: RemoteOperatingSystem,
    val architecture: String,
    val target: String,
    val root: String,
    val bridgeVersion: String,
    val minimumHerdrVersion: String,
    val sha256: String,
    val osLabel: String = os.name.lowercase(),
)
