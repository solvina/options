package cz.solvina.options.scanner

import cz.solvina.options.domain.features.market.MarketDataPort
import cz.solvina.options.domain.features.market.OptionChainPort
import cz.solvina.options.domain.features.market.model.OptionQuote
import cz.solvina.options.domain.features.scanner.ScanCandidateSelector
import cz.solvina.options.domain.features.scanner.ScannerConfig
import cz.solvina.options.domain.features.universe.InstrumentConfig
import cz.solvina.options.domain.features.universe.UniversePort
import cz.solvina.options.domain.features.volatility.VolatilityPort
import cz.solvina.options.domain.models.IvRank
import cz.solvina.options.domain.models.Money
import cz.solvina.options.domain.models.OptionContract
import cz.solvina.options.domain.models.OptionGreeks
import cz.solvina.options.domain.models.OptionType
import cz.solvina.options.domain.models.Symbol
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Entry filter pipeline for bull put spreads. Selection runs in order — the first failing filter
 * ends the scan for that symbol with no trade.
 *
 *   1. IV Rank ≥ threshold (default 30 %)
 *   2. Expiry within [minDte, maxDte], select closest to preferredDte
 *   3. Put delta within [deltaMin, deltaMax], select closest to targetDelta
 *   4. Bought strike = highest strike ≤ (soldStrike − spreadWidthUsd)
 *   5. Net credit (mid) ≥ minCreditPerShare
 *   6. maxRiskPerShare = spreadWidth − credit  must be positive (sanity check)
 *   7. maxRiskPerContract ≤ totalCapital × maxRiskPercent  (money management)
 */
class ScanCandidateSelectorTest {
    // -------------------------------------------------------------------------
    // Shared fixtures
    // -------------------------------------------------------------------------

    private val symbol = Symbol("SPY")
    private val today = LocalDate.of(2025, 1, 15)
    private val clock = Clock.fixed(today.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC)

    // Expiry at 38 DTE — inside the default [30, 50] window and close to preferredDte(45).
    private val expiry38d = today.plusDays(38)

    // Put with delta −0.15 (target) around strike $480; underlying at $500.
    private val soldStrike = BigDecimal("480")
    private val boughtStrike = BigDecimal("475") // soldStrike − $5 spread width

    private val soldContract = OptionContract(symbol, expiry38d, soldStrike, OptionType.PUT)
    private val boughtContract = OptionContract(symbol, expiry38d, boughtStrike, OptionType.PUT)

    // A passing option chain: two puts where sold has 0.15 delta and a $0.80 mid credit.
    private val validChain =
        listOf(
            put(strike = soldStrike, delta = -0.15, bid = 1.40, ask = 1.60), // mid=$1.50
            put(strike = boughtStrike, delta = -0.08, bid = 0.65, ask = 0.75), // mid=$0.70 → credit=$0.80
        )

    private val capitalOf50k = Money(BigDecimal("50000"))

    private val defaultConfig = ScannerConfig(watchlist = listOf("SPY"))

    // -------------------------------------------------------------------------
    // Filter 1: IV Rank
    // -------------------------------------------------------------------------

    @Test
    fun `no candidate when IV rank is below the threshold`() =
        runTest {
            // IV Rank 25 % is below the default threshold of 30 % — premium is insufficient.
            val result = buildSelector(ivRank = 25.0).select(symbol, capitalOf50k)

            assertNull(result, "Low IV Rank must prevent entry")
        }

    @Test
    fun `candidate is considered when IV rank meets the threshold`() =
        runTest {
            // IV Rank exactly at the threshold (30 %) is allowed.
            val result = buildSelector(ivRank = 30.0).select(symbol, capitalOf50k)

            assertNotNull(result)
        }

    // -------------------------------------------------------------------------
    // Filter 2: Expiry selection
    // -------------------------------------------------------------------------

    @Test
    fun `no candidate when no expiry falls within the DTE window`() =
        runTest {
            // Available expirations: 10 DTE (too soon) and 70 DTE (too far).
            // Default window: [30, 50]. Neither qualifies.
            val result =
                buildSelector(
                    expirations = setOf(today.plusDays(10), today.plusDays(70)),
                ).select(symbol, capitalOf50k)

            assertNull(result, "No valid expiry must prevent entry")
        }

    @Test
    fun `selects expiry closest to preferredDte when multiple options qualify`() =
        runTest {
            // Two valid expirations: 35 DTE and 48 DTE. preferredDte = 45 → pick 48.
            val expiry35d = today.plusDays(35)
            val expiry48d = today.plusDays(48)

            val chain48 =
                listOf(
                    put(strike = soldStrike, delta = -0.15, bid = 1.40, ask = 1.60, expiry = expiry48d),
                    put(strike = boughtStrike, delta = -0.08, bid = 0.65, ask = 0.75, expiry = expiry48d),
                )

            val result =
                buildSelector(
                    expirations = setOf(expiry35d, expiry38d, expiry48d),
                    chain = chain48,
                ).select(symbol, capitalOf50k)

            assertNotNull(result)
            assertEquals(expiry48d, result.soldContract.expiry)
        }

    // -------------------------------------------------------------------------
    // Filter 3: Delta selection
    // -------------------------------------------------------------------------

    @Test
    fun `no candidate when no put has delta within the tolerance band`() =
        runTest {
            // All available puts are either too high or too low delta.
            // Default band: [−0.20, −0.10]; here both are outside it.
            val chain =
                listOf(
                    put(strike = soldStrike, delta = -0.05, bid = 0.20, ask = 0.30), // too small
                    put(strike = boughtStrike, delta = -0.25, bid = 0.80, ask = 1.00), // too large
                )

            val result = buildSelector(chain = chain).select(symbol, capitalOf50k)

            assertNull(result, "No qualifying delta must prevent entry")
        }

    @Test
    fun `selects the put whose delta is closest to targetDelta among multiple qualifying strikes`() =
        runTest {
            // Three puts all within [−0.10, −0.20]; the one at −0.14 is closest to target −0.15.
            // strikeC is at 490 (not 475) to avoid a strike collision with the bought leg of strikeB.
            val strikeA = BigDecimal("485") // delta −0.12 — farther from target
            val strikeB = BigDecimal("480") // delta −0.14 — closest to target −0.15
            val strikeC = BigDecimal("490") // delta −0.18 — farther from target

            val chain =
                listOf(
                    put(strike = strikeA, delta = -0.12, bid = 1.00, ask = 1.20),
                    put(strike = strikeB, delta = -0.14, bid = 1.35, ask = 1.55),
                    put(strike = strikeC, delta = -0.18, bid = 1.60, ask = 1.80),
                    // bought leg for strikeB (strikeB − $5 = $475)
                    put(strike = BigDecimal("475"), delta = -0.08, bid = 0.65, ask = 0.75),
                )

            val result = buildSelector(chain = chain).select(symbol, capitalOf50k)

            assertNotNull(result)
            assertEquals(strikeB, result.soldContract.strike)
        }

    // -------------------------------------------------------------------------
    // Filter 5: Minimum credit
    // -------------------------------------------------------------------------

    @Test
    fun `no candidate when mid credit is below minCreditPerShare`() =
        runTest {
            // sold mid=$0.25, bought mid=$0.10 → net credit=$0.15 < minCredit=$0.30.
            val cheapChain =
                listOf(
                    put(strike = soldStrike, delta = -0.15, bid = 0.20, ask = 0.30),
                    put(strike = boughtStrike, delta = -0.08, bid = 0.05, ask = 0.15),
                )

            val result = buildSelector(chain = cheapChain).select(symbol, capitalOf50k)

            assertNull(result, "Insufficient credit must prevent entry")
        }

    // -------------------------------------------------------------------------
    // Filter 7: Money management
    // -------------------------------------------------------------------------

    @Test
    fun `no candidate when position risk exceeds the capital allocation limit`() =
        runTest {
            // Spread width=$5, credit=$0.80 → maxRiskPerShare=$4.20 → maxRiskPerContract=$420.
            // allowedRisk = $1000 × 2.5 % = $25  →  $420 >> $25.
            val tinyCapital = Money(BigDecimal("1000"))

            val result = buildSelector().select(symbol, tinyCapital)

            assertNull(result, "Position risk exceeding capital limit must prevent entry")
        }

    // -------------------------------------------------------------------------
    // Happy path — full pipeline
    // -------------------------------------------------------------------------

    @Test
    fun `happy path returns a well-formed execution request when all filters pass`() =
        runTest {
            val result = buildSelector().select(symbol, capitalOf50k)

            assertNotNull(result)
            assertEquals(soldContract, result.soldContract)
            assertEquals(boughtContract, result.boughtContract)
            assertEquals(symbol, result.underlyingSymbol)
            // mid credit = soldMid($1.50) − boughtMid($0.70) = $0.80
            assertEquals(0, BigDecimal("0.8000").compareTo(result.targetCredit))
            // maxRiskPerShare = spreadWidth($5.00) − credit($0.80) = $4.20
            assertEquals(0, BigDecimal("4.2000").compareTo(result.maxRiskPerShare))
        }

    @Test
    fun `per-symbol InstrumentConfig overrides take precedence over global ScannerConfig defaults`() =
        runTest {
            // SPY is configured with a higher IV rank threshold (50 %) than the global default (30 %).
            // IV Rank = 40 % passes globally but must be rejected by the symbol-level override.
            val symbolConfig =
                InstrumentConfig(
                    symbol = symbol,
                    ivRankThreshold = 50.0,
                )

            val result =
                buildSelector(
                    ivRank = 40.0,
                    instrumentConfig = symbolConfig,
                ).select(symbol, capitalOf50k)

            assertNull(result, "Symbol-level IV Rank threshold must override the global default")
        }

    // -------------------------------------------------------------------------
    // Test infrastructure
    // -------------------------------------------------------------------------

    /**
     * Builds a [ScanCandidateSelector] with controllable fake ports.
     * All parameters default to a scenario where a valid candidate is found.
     */
    private fun buildSelector(
        ivRank: Double = 35.0,
        expirations: Set<LocalDate> = setOf(expiry38d),
        chain: List<OptionQuote> = validChain,
        instrumentConfig: InstrumentConfig? = null,
        config: ScannerConfig = defaultConfig,
    ) = ScanCandidateSelector(
        volatilityPort =
            object : VolatilityPort {
                override suspend fun getIvRank(symbol: Symbol) = IvRank(rank = ivRank, currentIv = 0.25, calculatedAt = Instant.now())
            },
        marketDataPort =
            object : MarketDataPort {
                override suspend fun getUnderlyingPrice(symbol: Symbol) = Money(BigDecimal("500"))

                override suspend fun getOptionMid(contract: OptionContract) = Money(BigDecimal.ZERO)
            },
        optionChainPort =
            object : OptionChainPort {
                override suspend fun getAvailableExpirations(symbol: Symbol) = expirations

                override suspend fun getOptionChain(
                    symbol: Symbol,
                    expiry: LocalDate,
                    underlyingPrice: Money,
                ) = chain.filter { it.contract.expiry == expiry }
            },
        universePort =
            object : UniversePort {
                override fun getWatchlist() = emptyList<Symbol>()

                override fun getFlagWatchlist() = emptyList<Symbol>()

                override fun getActiveSymbols() = emptyList<Symbol>()

                override fun isMarketOpen(symbol: Symbol) = true

                override fun getMarketSchedule(symbol: Symbol) =
                    cz.solvina.options.domain.features.universe.MarketSchedule(
                        zone = java.time.ZoneId.of("America/New_York"),
                        open = java.time.LocalTime.of(9, 30),
                        close = java.time.LocalTime.of(16, 0),
                        session = "US",
                    )

                override suspend fun getAll() = emptyList<InstrumentConfig>()

                override suspend fun get(symbol: Symbol) = instrumentConfig

                override suspend fun save(config: InstrumentConfig) = config

                override suspend fun delete(symbol: Symbol) {}
            },
        config = config,
        clock = clock,
    )

    private fun put(
        strike: BigDecimal,
        delta: Double,
        bid: Double,
        ask: Double,
        expiry: LocalDate = expiry38d,
    ): OptionQuote {
        val mid = (bid + ask) / 2
        return OptionQuote(
            contract = OptionContract(symbol, expiry, strike, OptionType.PUT),
            bid = Money(BigDecimal.valueOf(bid)),
            ask = Money(BigDecimal.valueOf(ask)),
            mid = Money(BigDecimal.valueOf(mid)),
            greeks = OptionGreeks(delta = delta, gamma = 0.01, theta = -0.02, vega = 0.10, iv = 0.25),
        )
    }
}
