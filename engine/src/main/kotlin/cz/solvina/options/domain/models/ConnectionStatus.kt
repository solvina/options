package cz.solvina.options.domain.models

data class ConnectionStatus(
    val connected: Boolean,
    val autoReconnectEnabled: Boolean,
    val autoReconnectThreadActive: Boolean,
    val connectionInitialized: Boolean,
)
