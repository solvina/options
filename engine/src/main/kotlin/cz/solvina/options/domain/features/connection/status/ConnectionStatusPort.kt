package cz.solvina.options.domain.features.connection.status

import cz.solvina.options.domain.models.ConnectionStatus

interface ConnectionStatusPort {
    fun isConnected(): Boolean

    fun getConnectionStatus(): ConnectionStatus
}
