package cz.solvina.options.domain.features.connection

interface ConnectionPort {
    suspend fun connect(): Boolean

    fun disconnect()

    fun isConnected(): Boolean
}
