package dev.herdroid.core.herdr

import dev.herdroid.core.model.SessionState

data class RouteState(
    val epoch: String? = null,
    val sessions: Map<String, SessionState> = emptyMap(),
    val diagnostics: List<String> = emptyList(),
)
