package cz.solvina.options.persistence

import cz.solvina.options.adapters.outbound.ibkr.IbkrContractFactory
import cz.solvina.options.adapters.outbound.persistence.postgres.UniversePersistenceAdapter
import cz.solvina.options.adapters.outbound.persistence.postgres.entity.ExchangeSessionEntity
import cz.solvina.options.adapters.outbound.persistence.postgres.entity.InstrumentUniverseEntity
import cz.solvina.options.adapters.outbound.persistence.postgres.repository.ExchangeSessionRepository
import cz.solvina.options.adapters.outbound.persistence.postgres.repository.InstrumentUniverseRepository
import cz.solvina.options.domain.features.universe.MarketCalendarPort
import cz.solvina.options.domain.models.OptionContract
import cz.solvina.options.domain.models.OptionType
import cz.solvina.options.domain.models.Symbol
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.assertEquals

/**
 * Parity guard for the yaml→DB move of `ibkr.instruments` / `ibkr.exchanges` (v35): contracts and
 * market sessions must resolve exactly as the deleted yaml blocks did — EU names to EUR/EUREX (ASML
 * direct-routed to AEB, the Xetra ETFs to IBIS), US names to the USD/SMART default.
 */
class UniverseRoutingAndSessionTest {
    private val instrumentRepository = mockk<InstrumentUniverseRepository>()
    private val sessionRepository = mockk<ExchangeSessionRepository>()
    private val marketCalendar = mockk<MarketCalendarPort>()

    private val adapter =
        UniversePersistenceAdapter(instrumentRepository, sessionRepository, marketCalendar).apply {
            every { instrumentRepository.findAll() } returns
                listOf(
                    // No routing columns — a plain US row, as the whole US universe is stored.
                    InstrumentUniverseEntity(symbol = "SPY"),
                    routed("ASML", currency = "EUR", stock = "AEB", option = "EUREX", market = "EU"),
                    routed("SAP", currency = "EUR", option = "EUREX", market = "EU"),
                    routed("EXV1", currency = "EUR", stock = "IBIS", market = "EU"),
                )
            every { sessionRepository.findAll() } returns
                listOf(
                    ExchangeSessionEntity("US", "America/New_York", "09:30", "16:00"),
                    ExchangeSessionEntity("EU", "Europe/Berlin", "09:00", "17:30"),
                )
            loadCache()
        }

    private val contractFactory = IbkrContractFactory(adapter)

    @Test
    fun `US symbol falls back to the USD SMART defaults`() {
        val stock = contractFactory.stockContract(Symbol("SPY"))
        assertEquals("USD", stock.currency())
        assertEquals("SMART", stock.exchange())

        val option = contractFactory.optionContract(putOn("SPY", BigDecimal("500")))
        assertEquals("USD", option.currency())
        assertEquals("SMART", option.exchange())
        assertEquals("100", option.multiplier())
    }

    @Test
    fun `symbol absent from the universe still routes to the US defaults`() {
        val stock = contractFactory.stockContract(Symbol("UNKNOWN"))
        assertEquals("USD", stock.currency())
        assertEquals("SMART", stock.exchange())
    }

    @Test
    fun `EU option name routes stock and option legs to its own venues`() {
        val stock = contractFactory.stockContract(Symbol("ASML"))
        assertEquals("EUR", stock.currency())
        assertEquals("AEB", stock.exchange())

        val option = contractFactory.optionContract(putOn("ASML", BigDecimal("600")))
        assertEquals("EUR", option.currency())
        assertEquals("EUREX", option.exchange())

        // SAP has no stock_exchange row — the stock leg falls back to SMART, options still EUREX.
        assertEquals("SMART", contractFactory.stockContract(Symbol("SAP")).exchange())
        assertEquals("EUREX", contractFactory.optionContract(putOn("SAP", BigDecimal("200"))).exchange())
    }

    @Test
    fun `EU combo legs carry the EUREX exchange and EUR currency`() {
        val bag = contractFactory.bagContract(putOn("ASML", BigDecimal("600")), soldConId = 1, boughtConId = 2)
        assertEquals("EUR", bag.currency())
        assertEquals(listOf("EUREX", "EUREX"), bag.comboLegs().map { it.exchange() })
    }

    @Test
    fun `Xetra ETF routes the stock leg to IBIS`() {
        val stock = contractFactory.stockContract(Symbol("EXV1"))
        assertEquals("EUR", stock.currency())
        assertEquals("IBIS", stock.exchange())
    }

    @Test
    fun `market schedule resolves from the seeded exchange sessions`() {
        val us = adapter.getMarketSchedule(Symbol("SPY"))
        assertEquals(ZoneId.of("America/New_York"), us.zone)
        assertEquals(LocalTime.of(9, 30), us.open)
        assertEquals(LocalTime.of(16, 0), us.close)
        assertEquals("US", us.session)

        val eu = adapter.getMarketSchedule(Symbol("ASML"))
        assertEquals(ZoneId.of("Europe/Berlin"), eu.zone)
        assertEquals(LocalTime.of(9, 0), eu.open)
        assertEquals(LocalTime.of(17, 30), eu.close)
        assertEquals("EU", eu.session)
    }

    @Test
    fun `both seeded sessions are exposed for the any-exchange-open check`() {
        assertEquals(setOf("US", "EU"), adapter.getExchangeSessions().map { it.name }.toSet())
    }

    private fun routed(
        symbol: String,
        currency: String? = null,
        stock: String? = null,
        option: String? = null,
        market: String? = null,
    ) = InstrumentUniverseEntity(
        symbol = symbol,
        currency = currency,
        stockExchange = stock,
        optionExchange = option,
        marketExchange = market,
    )

    private fun putOn(
        symbol: String,
        strike: BigDecimal,
    ) = OptionContract(Symbol(symbol), LocalDate.of(2026, 9, 18), strike, OptionType.PUT)
}
