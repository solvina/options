package cz.solvina.options.adapters.outbound.persistence.postgres

import cz.solvina.options.adapters.outbound.persistence.postgres.entity.InstrumentUniverseEntity
import cz.solvina.options.adapters.outbound.persistence.postgres.repository.ExchangeSessionRepository
import cz.solvina.options.adapters.outbound.persistence.postgres.repository.InstrumentUniverseRepository
import cz.solvina.options.domain.features.universe.DaySession
import cz.solvina.options.domain.features.universe.ExchangeSession
import cz.solvina.options.domain.features.universe.InstrumentConfig
import cz.solvina.options.domain.features.universe.InstrumentRouting
import cz.solvina.options.domain.features.universe.MarketCalendarPort
import cz.solvina.options.domain.features.universe.MarketSchedule
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
    private val exchangeSessionRepository: ExchangeSessionRepository,
    private val marketCalendar: MarketCalendarPort,
) : UniversePort {
    private val cache = ConcurrentHashMap<String, InstrumentConfig>()

    // Market sessions ("US", "EU") from `exchange_session`. Seeded by migration and effectively
    // static, so it is loaded once alongside the instrument cache.
    private val sessions = ConcurrentHashMap<String, ExchangeSession>()

    @PostConstruct
    fun loadCache() {
        repository.findAll().forEach { cache[it.symbol] = it.toDomain() }
        exchangeSessionRepository.findAll().forEach {
            sessions[it.name] =
                ExchangeSession(
                    name = it.name,
                    zone = ZoneId.of(it.timezone),
                    open = LocalTime.parse(it.openTime),
                    close = LocalTime.parse(it.closeTime),
                )
        }
    }

    override fun getWatchlist(): List<Symbol> = cache.values.filter { it.enabled }.map { it.symbol }

    override fun getFlagWatchlist(): List<Symbol> = cache.values.filter { it.flagEnabled }.map { it.symbol }

    override fun getActiveSymbols(): List<Symbol> = getWatchlist().filter { isMarketOpen(it) }

    override fun sectorOf(symbol: Symbol): String? = cache[symbol.value]?.sector

    override fun routingFor(symbol: Symbol): InstrumentRouting {
        val config = cache[symbol.value] ?: return InstrumentRouting()
        val default = InstrumentRouting()
        return InstrumentRouting(
            currency = config.currency ?: default.currency,
            stockExchange = config.stockExchange ?: default.stockExchange,
            optionExchange = config.optionExchange ?: default.optionExchange,
            multiplier = config.multiplier ?: default.multiplier,
            marketExchange = config.marketExchange ?: default.marketExchange,
        )
    }

    override fun getExchangeSessions(): List<ExchangeSession> = sessions.values.toList().ifEmpty { listOf(ExchangeSession.US) }

    override fun isMarketOpen(symbol: Symbol): Boolean {
        val exchangeSession = sessionFor(symbol)
        val now = ZonedDateTime.now(exchangeSession.zone)
        if (now.dayOfWeek == DayOfWeek.SATURDAY || now.dayOfWeek == DayOfWeek.SUNDAY) return false
        val time = now.toLocalTime()
        // Prefer the broker's liquid-hours calendar when known — it captures holidays and half-days
        // that the fixed weekday window cannot. Fall back to the fixed window only when the calendar
        // has not been warmed yet (cold start / IBKR unavailable), preserving prior behaviour.
        when (val session = marketCalendar.sessionFor(symbol, now.toLocalDate())) {
            DaySession.Closed -> return false
            is DaySession.Open -> return !time.isBefore(session.open) && time.isBefore(session.close)
            null -> Unit // no calendar yet — fall through to the fixed-window default below
        }
        return !time.isBefore(exchangeSession.open) && time.isBefore(exchangeSession.close)
    }

    override fun getMarketSchedule(symbol: Symbol): MarketSchedule {
        val exchangeSession = sessionFor(symbol)
        return MarketSchedule(
            zone = exchangeSession.zone,
            open = exchangeSession.open,
            close = exchangeSession.close,
            session = exchangeSession.name,
        )
    }

    /**
     * Session governing [symbol]: its `market_exchange` routing (default "US") looked up in the
     * `exchange_session` cache, falling back to [ExchangeSession.US] when the row is missing (e.g. a
     * symbol routed to a market nobody seeded yet).
     */
    private fun sessionFor(symbol: Symbol): ExchangeSession {
        val marketExchange = routingFor(symbol).marketExchange
        return sessions[marketExchange] ?: ExchangeSession.US
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
            flagEnabled = flagEnabled,
            sector = sector,
            currency = currency,
            stockExchange = stockExchange,
            optionExchange = optionExchange,
            multiplier = multiplier,
            marketExchange = marketExchange,
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
            exDividendDate = exDividendDate,
            nextDividendAmount = nextDividendAmount,
            nextEarningsDate = nextEarningsDate,
            notes = notes,
        )

    private fun InstrumentConfig.toEntity() =
        InstrumentUniverseEntity(
            symbol = symbol.value,
            enabled = enabled,
            flagEnabled = flagEnabled,
            sector = sector,
            currency = currency,
            stockExchange = stockExchange,
            optionExchange = optionExchange,
            multiplier = multiplier,
            marketExchange = marketExchange,
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
            exDividendDate = exDividendDate,
            nextDividendAmount = nextDividendAmount,
            nextEarningsDate = nextEarningsDate,
            notes = notes,
        )
}
