package cz.solvina.options.domain.features.flag.config

interface FlagTradingConfigPort {
    suspend fun get(): FlagTradingConfig
    suspend fun update(config: FlagTradingConfig): FlagTradingConfig
}
