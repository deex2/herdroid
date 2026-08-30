package dev.herdroid.core.herdr.wire

import dev.herdroid.core.model.RemoteOperatingSystem

internal fun RemoteOperatingSystem.toWire(): String = when (this) {
    RemoteOperatingSystem.LINUX -> "linux"
    RemoteOperatingSystem.WINDOWS -> "windows"
}

internal fun remoteOperatingSystemFromWire(value: String) = when (value) {
    "linux" -> RemoteOperatingSystem.LINUX
    "windows" -> RemoteOperatingSystem.WINDOWS
    else -> throw IllegalArgumentException("Unsupported remote operating system")
}
