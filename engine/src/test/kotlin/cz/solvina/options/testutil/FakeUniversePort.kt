package cz.solvina.options.testutil

import cz.solvina.options.domain.features.universe.ExchangeSession
import cz.solvina.options.domain.features.universe.InstrumentConfig
import cz.solvina.options.domain.features.universe.InstrumentRouting
import cz.solvina.options.domain.features.universe.MarketSchedule
import cz.solvina.options.domain.features.universe.UniversePort
import cz.solvina.options.domain.models.Symbol

/**
 * Minimal [UniversePort] for tests that only care about broker routing — the piece the IBKR
 * contract factory reads. Symbols not listed resolve to the US default, matching production
 * behaviour for a NULL-routing row.
 */
class FakeUniversePort(
    private val routing: Map<String, InstrumentRouting> = emptyMap(),
    private val sessions: List<ExchangeSession> = listOf(ExchangeSession.US),
) : UniversePort {
    constructor(vararg routing: Pair<String, InstrumentRouting>) : this(routing.toMap())

    override fun getWatchlist(): List<Symbol> = routing.keys.map { Symbol(it) }

    override fun getFlagWatchlist(): List<Symbol> = emptyList()

    override fun getActiveSymbols(): List<Symbol> = getWatchlist()

    override fun routingFor(symbol: Symbol): InstrumentRouting = routing[symbol.value] ?: InstrumentRouting()

    override fun getExchangeSessions(): List<ExchangeSession> = sessions

    override fun isMarketOpen(symbol: Symbol): Boolean = true

    override fun getMarketSchedule(symbol: Symbol): MarketSchedule {
        val session = sessions.firstOrNull { it.name == routingFor(symbol).marketExchange } ?: ExchangeSession.US
        return MarketSchedule(
            zone = session.zone,
            open = session.open,
            close = session.close,
            session = session.name,
        )
    }

    override suspend fun getAll(): List<InstrumentConfig> = emptyList()

    override suspend fun get(symbol: Symbol): InstrumentConfig? = null

    override suspend fun save(config: InstrumentConfig): InstrumentConfig = config

    override suspend fun delete(symbol: Symbol) = Unit
}
