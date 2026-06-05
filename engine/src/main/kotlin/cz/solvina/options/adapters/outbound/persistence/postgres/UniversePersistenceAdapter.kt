package cz.solvina.options.adapters.outbound.persistence.postgres

import cz.solvina.options.adapters.outbound.ibkr.ExchangeHours
import cz.solvina.options.adapters.outbound.ibkr.IbkrInstrumentsConfig
import cz.solvina.options.adapters.outbound.ibkr.InstrumentDef
import cz.solvina.options.adapters.outbound.persistence.postgres.entity.InstrumentUniverseEntity
import cz.solvina.options.adapters.outbound.persistence.postgres.repository.InstrumentUniverseRepository
import cz.solvina.options.domain.features.universe.InstrumentConfig
import cz.solvina.options.domain.features.universe.UniversePort
import cz.solvina.options.domain.models.Symbol
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Component
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap

@Component
class UniversePersistenceAdapter(
    private val repository: InstrumentUniverseRepository,
    private val instrumentsConfig: IbkrInstrumentsConfig,
) : UniversePort {
    private val cache = ConcurrentHashMap<String, InstrumentConfig>()

    @PostConstruct
    fun loadCache() {
        repository.findAll().forEach { cache[it.symbol] = it.toDomain() }
    }

    override fun getWatchlist(): List<Symbol> = cache.values.filter { it.enabled }.map { it.symbol }

    override fun getActiveSymbols(): List<Symbol> = getWatchlist().filter { isMarketOpen(it) }

    override fun isMarketOpen(symbol: Symbol): Boolean {
        val def = instrumentsConfig.instruments[symbol.value] ?: InstrumentDef()
        val hours = instrumentsConfig.exchanges[def.marketExchange] ?: US_HOURS
        val zone = ZoneId.of(hours.timezone)
        val now = ZonedDateTime.now(zone)
        if (now.dayOfWeek == DayOfWeek.SATURDAY || now.dayOfWeek == DayOfWeek.SUNDAY) return false
        val time = now.toLocalTime()
        val open = LocalTime.parse(hours.open)
        val close = LocalTime.parse(hours.close)
        return !time.isBefore(open) && time.isBefore(close)
    }

    override suspend fun getAll(): List<InstrumentConfig> =
        withContext(Dispatchers.IO) {
            repository.findAll().map { it.toDomain() }
        }

    override suspend fun get(symbol: Symbol): InstrumentConfig? =
        withContext(Dispatchers.IO) {
            repository.findById(symbol.value).orElse(null)?.toDomain()
        }

    override suspend fun save(config: InstrumentConfig): InstrumentConfig =
        withContext(Dispatchers.IO) {
            val saved = repository.save(config.toEntity()).toDomain()
            cache[saved.symbol.value] = saved
            saved
        }

    override suspend fun delete(symbol: Symbol) {
        withContext(Dispatchers.IO) {
            repository.deleteById(symbol.value)
            cache.remove(symbol.value)
        }
    }

    private fun InstrumentUniverseEntity.toDomain() =
        InstrumentConfig(
            symbol = Symbol(symbol),
            enabled = enabled,
            ivRankThreshold = ivRankThreshold?.toDouble(),
            minDte = minDte,
            maxDte = maxDte,
            preferredDte = preferredDte,
            targetDelta = targetDelta?.toDouble(),
            deltaMin = deltaMin?.toDouble(),
            deltaMax = deltaMax?.toDouble(),
            spreadWidthUsd = spreadWidthUsd,
            minCreditPerShare = minCreditPerShare,
            maxRiskPercent = maxRiskPercent?.toDouble(),
            takeProfitPercent = takeProfitPercent?.toDouble(),
            stopLossPercent = stopLossPercent?.toDouble(),
            timeProfitDte = timeProfitDte,
            notes = notes,
        )

    private fun InstrumentConfig.toEntity() =
        InstrumentUniverseEntity(
            symbol = symbol.value,
            enabled = enabled,
            ivRankThreshold = ivRankThreshold?.toBigDecimal(),
            minDte = minDte,
            maxDte = maxDte,
            preferredDte = preferredDte,
            targetDelta = targetDelta?.toBigDecimal(),
            deltaMin = deltaMin?.toBigDecimal(),
            deltaMax = deltaMax?.toBigDecimal(),
            spreadWidthUsd = spreadWidthUsd,
            minCreditPerShare = minCreditPerShare,
            maxRiskPercent = maxRiskPercent?.toBigDecimal(),
            takeProfitPercent = takeProfitPercent?.toBigDecimal(),
            stopLossPercent = stopLossPercent?.toBigDecimal(),
            timeProfitDte = timeProfitDte,
            notes = notes,
        )

    companion object {
        private val US_HOURS =
            ExchangeHours(
                timezone = "America/New_York",
                open = "09:30",
                close = "16:00",
            )
    }
}
