package cz.solvina.options.adapters.outbound.persistence.postgres

import com.fasterxml.jackson.databind.ObjectMapper
import cz.solvina.options.adapters.outbound.ibkr.cache.OptionParams
import cz.solvina.options.adapters.outbound.ibkr.cache.OptionParamsStorePort
import cz.solvina.options.adapters.outbound.persistence.postgres.entity.OptionParamsCacheEntity
import cz.solvina.options.adapters.outbound.persistence.postgres.repository.OptionParamsCacheRepository
import cz.solvina.options.domain.models.Symbol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.LocalDate

/**
 * JPA-backed [OptionParamsStorePort]. Collections are (de)serialized as JSON of plain strings
 * (ISO dates, plain BigDecimal) so no Jackson time module is required and the round-trip is exact.
 */
@Component
class OptionParamsCachePersistenceAdapter(
    private val repository: OptionParamsCacheRepository,
) : OptionParamsStorePort {
    private val mapper = ObjectMapper()

    override fun loadAll(): Map<Symbol, OptionParams> =
        repository.findAll().associate { e ->
            val expirations = readList(e.expirationsJson).map { LocalDate.parse(it) }.toSet()
            val strikes = readList(e.strikesJson).map { BigDecimal(it) }.toSet()
            val strikesByExpiry =
                readMap(e.strikesByExpiryJson).entries.associate { (k, v) ->
                    LocalDate.parse(k) to v.map { BigDecimal(it) }.toSet()
                }
            Symbol(e.symbol) to
                OptionParams(
                    expirations = expirations,
                    strikes = strikes,
                    strikesByExpiry = strikesByExpiry,
                    fetchedAt = e.fetchedAt,
                    exchange = e.exchange,
                    tradingClass = e.tradingClass,
                    multiplier = e.multiplier,
                )
        }

    override suspend fun save(
        symbol: Symbol,
        params: OptionParams,
    ) {
        withContext(Dispatchers.IO) {
            repository.save(
                OptionParamsCacheEntity(
                    symbol = symbol.value,
                    expirationsJson = mapper.writeValueAsString(params.expirations.map { it.toString() }),
                    strikesJson = mapper.writeValueAsString(params.strikes.map { it.toPlainString() }),
                    strikesByExpiryJson =
                        mapper.writeValueAsString(
                            params.strikesByExpiry.entries.associate { (k, v) ->
                                k.toString() to v.map { it.toPlainString() }
                            },
                        ),
                    exchange = params.exchange,
                    tradingClass = params.tradingClass,
                    multiplier = params.multiplier,
                    fetchedAt = params.fetchedAt,
                ),
            )
        }
    }

    private fun readList(json: String): List<String> = mapper.readValue(json, Array<String>::class.java).toList()

    @Suppress("UNCHECKED_CAST")
    private fun readMap(json: String): Map<String, List<String>> = mapper.readValue(json, Map::class.java) as Map<String, List<String>>
}
