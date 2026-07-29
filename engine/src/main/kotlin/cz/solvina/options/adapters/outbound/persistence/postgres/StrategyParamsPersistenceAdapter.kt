package cz.solvina.options.adapters.outbound.persistence.postgres

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import cz.solvina.options.adapters.outbound.persistence.postgres.entity.StrategyDefaultParamsEntity
import cz.solvina.options.adapters.outbound.persistence.postgres.entity.StrategySymbolParamsEntity
import cz.solvina.options.adapters.outbound.persistence.postgres.repository.StrategyDefaultParamsRepository
import cz.solvina.options.adapters.outbound.persistence.postgres.repository.StrategySymbolParamsRepository
import cz.solvina.options.domain.features.strategy.tuning.StrategyDefaultParamsPort
import cz.solvina.options.domain.features.strategy.tuning.StrategySymbolParamsPort
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Component
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * Both tuning stores in one adapter — they are the same shape over the same blob, and splitting them
 * would duplicate the JSON handling and the unreadable-row fallback.
 *
 * Not cached. Parameters are edited from the UI while the engine runs and the Apply button must take
 * effect on the next resolve; a cache would reintroduce exactly the "saved but nothing happened"
 * confusion this feature exists to remove. Both tables are tiny and read on (re)subscribe, not per bar.
 */
@Component
class StrategyParamsPersistenceAdapter(
    private val defaultsRepository: StrategyDefaultParamsRepository,
    private val symbolRepository: StrategySymbolParamsRepository,
    private val mapper: ObjectMapper,
) : StrategyDefaultParamsPort,
    StrategySymbolParamsPort {
    // ---- Global baseline ----

    override suspend fun get(strategyId: String): Map<String, Any?>? =
        withContext(Dispatchers.IO) {
            defaultsRepository.findById(strategyId).orElse(null)?.let { parse(it.paramsJson, "default params for $strategyId") }
        }

    override suspend fun upsert(
        strategyId: String,
        params: Map<String, Any?>,
    ) {
        withContext(Dispatchers.IO) {
            val entity =
                defaultsRepository.findById(strategyId).orElseGet { StrategyDefaultParamsEntity(strategyId = strategyId) }.apply {
                    paramsJson = mapper.writeValueAsString(params)
                    updatedAt = Instant.now()
                }
            defaultsRepository.save(entity)
            logger.info { "[$strategyId] Default params saved: ${params.keys.sorted()}" }
        }
    }

    override suspend fun delete(strategyId: String) {
        withContext(Dispatchers.IO) {
            if (defaultsRepository.existsById(strategyId)) {
                defaultsRepository.deleteById(strategyId)
                logger.info { "[$strategyId] Default params reset to descriptor defaults" }
            }
        }
    }

    // ---- Per-symbol override ----

    override suspend fun get(
        strategyId: String,
        symbol: String,
        timeframe: String,
    ): Map<String, Any?>? =
        withContext(Dispatchers.IO) {
            symbolRepository
                .findByStrategyIdAndSymbolAndTimeframe(strategyId, symbol, timeframe)
                ?.let { parse(it.paramsJson, "$strategyId params for $symbol") }
        }

    override suspend fun allForStrategy(strategyId: String): Map<String, Map<String, Any?>> =
        withContext(Dispatchers.IO) {
            symbolRepository
                .findByStrategyId(strategyId)
                .mapNotNull { row -> parse(row.paramsJson, "$strategyId params for ${row.symbol}")?.let { row.symbol to it } }
                .toMap()
        }

    override suspend fun upsert(
        strategyId: String,
        symbol: String,
        params: Map<String, Any?>,
        timeframe: String,
    ) {
        withContext(Dispatchers.IO) {
            val now = Instant.now()
            val entity =
                (
                    symbolRepository.findByStrategyIdAndSymbolAndTimeframe(strategyId, symbol, timeframe)
                        ?: StrategySymbolParamsEntity(
                            strategyId = strategyId,
                            symbol = symbol,
                            timeframe = timeframe,
                            createdAt = now,
                        )
                ).apply {
                    paramsJson = mapper.writeValueAsString(params)
                    updatedAt = now
                }
            symbolRepository.save(entity)
            logger.info { "[$strategyId/$symbol] Symbol params saved: ${params.keys.sorted()}" }
        }
    }

    override suspend fun delete(
        strategyId: String,
        symbol: String,
        timeframe: String,
    ) {
        withContext(Dispatchers.IO) {
            symbolRepository.findByStrategyIdAndSymbolAndTimeframe(strategyId, symbol, timeframe)?.let {
                symbolRepository.delete(it)
                logger.info { "[$strategyId/$symbol] Symbol params cleared — inheriting defaults" }
            }
        }
    }

    /**
     * A blob that no longer parses (hand-edited row, format change) must not take the scanner down.
     * Fall back to the layer above and say so, the same way the assignment adapter does.
     */
    private fun parse(
        json: String,
        what: String,
    ): Map<String, Any?>? =
        runCatching { mapper.readValue<Map<String, Any?>>(json) }
            .onFailure { e -> logger.warn { "Unreadable $what, falling back to inherited values: ${e.message}" } }
            .getOrNull()
}
