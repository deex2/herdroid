package dev.herdroid.core.data

sealed interface LocalDataAvailability {
    data object Initializing : LocalDataAvailability
    data object Available : LocalDataAvailability
    data object Unavailable : LocalDataAvailability
}

class LocalDataUnavailableException : IllegalStateException("Route storage is unavailable")
