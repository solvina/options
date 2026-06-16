package cz.solvina.options.adapters.outbound.persistence.postgres

import cz.solvina.options.adapters.outbound.persistence.postgres.entity.IvRankCacheEntity
import cz.solvina.options.adapters.outbound.persistence.postgres.repository.IvRankCacheRepository
import cz.solvina.options.domain.features.volatility.IvRankStorePort
import cz.solvina.options.domain.features.volatility.StoredIvRank
import cz.solvina.options.domain.models.Symbol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Component

@Component
class IvRankCachePersistenceAdapter(
    private val repository: IvRankCacheRepository,
) : IvRankStorePort {
    override fun loadAll(): Map<Symbol, StoredIvRank> =
        repository.findAll().associate {
            Symbol(it.symbol) to StoredIvRank(rank = it.rank, currentIv = it.currentIv, calculatedAt = it.calculatedAt)
        }

    override suspend fun save(
        symbol: Symbol,
        value: StoredIvRank,
    ) {
        withContext(Dispatchers.IO) {
            repository.save(
                IvRankCacheEntity(
                    symbol = symbol.value,
                    rank = value.rank,
                    currentIv = value.currentIv,
                    calculatedAt = value.calculatedAt,
                ),
            )
        }
    }
}
