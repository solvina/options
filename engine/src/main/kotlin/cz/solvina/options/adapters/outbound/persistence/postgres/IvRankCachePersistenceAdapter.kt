package cz.solvina.options.adapters.outbound.persistence.postgres

import cz.solvina.options.adapters.outbound.persistence.postgres.entity.IvRankCacheEntity
import cz.solvina.options.adapters.outbound.persistence.postgres.repository.IvRankCacheRepository
import cz.solvina.options.domain.features.volatility.IvRankStorePort
import cz.solvina.options.domain.features.volatility.IvSummary
import cz.solvina.options.domain.features.volatility.StoredIvRank
import cz.solvina.options.domain.models.Symbol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Component
import java.util.Locale

@Component
class IvRankCachePersistenceAdapter(
    private val repository: IvRankCacheRepository,
) : IvRankStorePort {
    override fun loadAll(): Map<Symbol, StoredIvRank> = repository.findAll().associate { Symbol(it.symbol) to it.toDomain() }

    override fun find(symbol: Symbol): StoredIvRank? = repository.findById(symbol.value).orElse(null)?.toDomain()

    private fun IvRankCacheEntity.toDomain(): StoredIvRank {
        val min = ivMin
        val max = ivMax
        val percentile = ivPercentile
        val days = sampleDays
        val summary =
            if (min != null && max != null && percentile != null && days != null) {
                IvSummary(
                    min = min,
                    max = max,
                    percentile = percentile,
                    sampleDays = days,
                    recent = recentIvs?.split(",")?.mapNotNull { it.toDoubleOrNull() }.orEmpty(),
                )
            } else {
                null
            }
        return StoredIvRank(rank = rank, currentIv = currentIv, calculatedAt = calculatedAt, summary = summary)
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
                    ivMin = value.summary?.min,
                    ivMax = value.summary?.max,
                    ivPercentile = value.summary?.percentile,
                    sampleDays = value.summary?.sampleDays,
                    recentIvs = value.summary?.recent?.joinToString(",") { "%.5f".format(Locale.ROOT, it) },
                ),
            )
        }
    }
}
