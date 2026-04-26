package cz.solvina.options.domain.features.volatility

import cz.solvina.options.domain.models.IvRank
import cz.solvina.options.domain.models.Symbol

interface VolatilityPort {
    suspend fun getIvRank(symbol: Symbol): IvRank
}
