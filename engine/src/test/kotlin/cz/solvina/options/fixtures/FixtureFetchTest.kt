package cz.solvina.options.fixtures

import com.fasterxml.jackson.databind.ObjectMapper
import cz.solvina.options.adapters.outbound.ibkr.IbkrConnection
import cz.solvina.options.adapters.outbound.ibkr.IbkrConnectionConfig
import cz.solvina.options.adapters.outbound.ibkr.account.IbkrAccountAdapter
import cz.solvina.options.adapters.outbound.ibkr.market.IbkrHistoricalDataAdapter
import cz.solvina.options.adapters.outbound.ibkr.market.IbkrMarketDataAdapter
import cz.solvina.options.adapters.outbound.ibkr.market.IbkrOptionChainAdapter
import cz.solvina.options.domain.features.scanner.ScannerConfig
import cz.solvina.options.domain.models.Symbol
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.test.context.ActiveProfiles
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.io.File
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

private val logger = KotlinLogging.logger {}

// ---------------------------------------------------------------------------
// Fixture data classes — plain, Jackson-serializable
// ---------------------------------------------------------------------------

data class IvHistoryFixture(
    val symbol: String,
    val fetchedAt: String,
    val bars: List<IvBarFixture>,
)

data class IvBarFixture(
    val date: String,
    val iv: Double?,
)

data class OptionChainFixture(
    val symbol: String,
    val fetchedAt: String,
    val underlyingPrice: Double,
    val expirations: List<String>,
    val selectedExpiry: String,
    val dteDays: Long,
    val chain: List<OptionQuoteFixture>,
)

data class OptionQuoteFixture(
    val strike: Double,
    val bid: Double,
    val ask: Double,
    val mid: Double,
    val delta: Double,
    val gamma: Double,
    val theta: Double,
    val vega: Double,
    val iv: Double,
)

data class AccountFixture(
    val netLiquidation: Double,
)

// ---------------------------------------------------------------------------
// Test
// ---------------------------------------------------------------------------

/**
 * Connects to a live TWS paper-trading session and dumps IBKR data to fixture
 * files under src/test/resources/fixtures/.  Run once with TWS open:
 *
 *   ./gradlew test -Dtests.tags=tws --rerun-tasks
 *
 * The resulting CSV/JSON files are committed and used by unit/integration tests
 * that don't need a live IBKR connection.
 *
 * Prerequisites:
 *   - TWS (or IB Gateway) running on localhost:7497, paper-trading account
 *   - API access enabled in TWS: Configure → API → Settings → Enable ActiveX and Socket Clients
 *   - "Read-Only API" must be OFF (we only read data, but TWS rejects the connection otherwise)
 */
@Tag("tws")
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest
@ActiveProfiles("tws")
class FixtureFetchTest {
    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16")
    }

    @Autowired
    private lateinit var connection: IbkrConnection

    @Autowired
    private lateinit var connectionConfig: IbkrConnectionConfig

    @Autowired
    private lateinit var historicalAdapter: IbkrHistoricalDataAdapter

    @Autowired
    private lateinit var marketAdapter: IbkrMarketDataAdapter

    @Autowired
    private lateinit var optionChainAdapter: IbkrOptionChainAdapter

    @Autowired
    private lateinit var accountAdapter: IbkrAccountAdapter

    @Autowired
    private lateinit var scannerConfig: ScannerConfig

    @Autowired
    private lateinit var mapper: ObjectMapper

    // ---------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------

    @BeforeAll
    fun connect() {
        connection.connect()
        check(connection.isConnected()) {
            "TWS is not running or not accepting connections on " +
                "${connectionConfig.host}:${connectionConfig.port}. " +
                "Start a paper-trading TWS session and enable socket API access."
        }
        // Allow nextValidId / connection handshake to arrive before any request.
        Thread.sleep(1_500)
        logger.info { "Connected to IBKR TWS (clientId=${connectionConfig.clientId})" }
    }

    @AfterAll
    fun disconnect() {
        if (connection.isConnected()) connection.disconnect()
    }

    // ---------------------------------------------------------------------------
    // IV history  →  src/test/resources/fixtures/iv/{SYMBOL}.csv
    // ---------------------------------------------------------------------------

    /**
     * Fetches 365 days of OPTION_IMPLIED_VOLATILITY daily bars for every symbol
     * in the watchlist and writes them as CSV files.
     *
     * CSV columns: date,iv
     *   date  — ISO-8601 (yyyy-MM-dd)
     *   iv    — dimensionless float, e.g. 0.1823 means 18.23 % IV
     */
    @Test
    fun `fetch IV history and save to CSV`() =
        runBlocking {
            val dir = File("src/test/resources/fixtures/iv").also { it.mkdirs() }

            for (symbolStr in scannerConfig.watchlist) {
                val symbol = Symbol(symbolStr)
                logger.info { "[$symbol] Requesting ${scannerConfig.ivHistoryDays} days of IV history…" }

                val bars = historicalAdapter.fetchDailyBars(symbol, scannerConfig.ivHistoryDays).toList()

                val file = File(dir, "$symbolStr.csv")
                file.bufferedWriter().use { w ->
                    w.appendLine("date,iv")
                    bars.forEach { bar -> w.appendLine("${bar.date},${bar.iv ?: ""}") }
                }
                logger.info { "[$symbol] Wrote ${bars.size} bars → ${file.path}" }

                Thread.sleep(500) // stay within IBKR pacing limits (60 hist requests / 10 min)
            }
        }

    // ---------------------------------------------------------------------------
    // Price history  →  src/test/resources/fixtures/prices/{SYMBOL}.csv
    // ---------------------------------------------------------------------------

    /**
     * Fetches 365 days of daily closing prices (TRADES) for every symbol
     * in the watchlist and writes them as CSV files.
     *
     * CSV columns: date,close
     *   date  — ISO-8601 (yyyy-MM-dd)
     *   close — closing price in USD, e.g. 476.58
     */
    @Test
    fun `fetch price history and save to CSV`() =
        runBlocking {
            val dir = File("src/test/resources/fixtures/prices").also { it.mkdirs() }

            for (symbolStr in scannerConfig.watchlist) {
                val symbol = Symbol(symbolStr)
                logger.info { "[$symbol] Requesting ${scannerConfig.ivHistoryDays} days of price history…" }

                val bars = historicalAdapter.fetchDailyPriceBars(symbol, scannerConfig.ivHistoryDays).toList()

                val file = File(dir, "$symbolStr.csv")
                file.bufferedWriter().use { w ->
                    w.appendLine("date,close")
                    bars.forEach { bar -> w.appendLine("${bar.date},${bar.close}") }
                }
                logger.info { "[$symbol] Wrote ${bars.size} price bars → ${file.path}" }

                Thread.sleep(500)
            }
        }

    // ---------------------------------------------------------------------------
    // Option chains  →  src/test/resources/fixtures/chain/{SYMBOL}.json
    // ---------------------------------------------------------------------------

    /**
     * For each watchlist symbol:
     *   1. Fetch available expirations via reqSecDefOptParams.
     *   2. Select the expiry closest to preferredDte within [minDte, maxDte].
     *   3. Fetch OTM put greeks (reqMktData snapshots) for up to candidateStrikeCount strikes.
     *   4. Write a JSON fixture that includes the underlying price, full expiration list,
     *      selected expiry, and per-strike bid/ask/mid/greeks.
     *
     * The wider candidateStrikeCount=20 in application-tws.yml gives richer fixture data
     * compared with the production default of 7.
     */
    @Test
    fun `fetch option chains and save to JSON`() =
        runBlocking {
            val dir = File("src/test/resources/fixtures/chain").also { it.mkdirs() }
            val today = LocalDate.now()

            for (symbolStr in scannerConfig.watchlist) {
                val symbol = Symbol(symbolStr)
                logger.info { "[$symbol] Fetching option chain…" }

                val underlying = marketAdapter.getUnderlyingPrice(symbol)
                val expirations = optionChainAdapter.getAvailableExpirations(symbol)

                val expiry =
                    expirations
                        .filter { exp ->
                            val dte = ChronoUnit.DAYS.between(today, exp)
                            dte in scannerConfig.minDte..scannerConfig.maxDte
                        }.minByOrNull { exp ->
                            abs(ChronoUnit.DAYS.between(today, exp) - scannerConfig.preferredDte)
                        }

                if (expiry == null) {
                    logger.warn {
                        "[$symbol] No expiry in [${scannerConfig.minDte}, ${scannerConfig.maxDte}] DTE " +
                            "(available: ${expirations.take(5)}…). Skipping."
                    }
                    continue
                }

                val dte = ChronoUnit.DAYS.between(today, expiry)
                logger.info { "[$symbol] underlying=\$${underlying.amount}  expiry=$expiry ($dte DTE)" }

                val chain = optionChainAdapter.getOptionChain(symbol, expiry, underlying)
                logger.info { "[$symbol] Got ${chain.size} quotes" }

                val fixture =
                    OptionChainFixture(
                        symbol = symbolStr,
                        fetchedAt = today.toString(),
                        underlyingPrice = underlying.amount.toDouble(),
                        expirations = expirations.sorted().map { it.toString() },
                        selectedExpiry = expiry.toString(),
                        dteDays = dte,
                        chain =
                            chain
                                .map { q ->
                                    OptionQuoteFixture(
                                        strike = q.contract.strike.toDouble(),
                                        bid = q.bid.amount.toDouble(),
                                        ask = q.ask.amount.toDouble(),
                                        mid = q.mid.amount.toDouble(),
                                        delta = q.greeks.delta,
                                        gamma = q.greeks.gamma,
                                        theta = q.greeks.theta,
                                        vega = q.greeks.vega,
                                        iv = q.greeks.iv,
                                    )
                                }.sortedBy { it.strike },
                    )

                mapper.writeValue(File(dir, "$symbolStr.json"), fixture)
                logger.info { "[$symbol] Saved ${chain.size} quotes → ${File(dir, "$symbolStr.json").path}" }

                Thread.sleep(1_000) // pacing between symbols
            }
        }

    // ---------------------------------------------------------------------------
    // Account summary  →  src/test/resources/fixtures/account.json
    // ---------------------------------------------------------------------------

    /**
     * Fetches NetLiquidation from IBKR and writes it to account.json.
     * Used by ScannerService tests to exercise the maxRiskPercent capital guard.
     */
    @Test
    fun `fetch account summary and save to JSON`() =
        runBlocking {
            val dir = File("src/test/resources/fixtures").also { it.mkdirs() }
            logger.info { "Requesting account summary…" }

            val detail = withTimeout(30_000L) { accountAdapter.accountDetail.filterNotNull().first() }
            val fixture = AccountFixture(netLiquidation = detail.totalCapital?.amount?.toDouble() ?: 0.0)

            val file = File(dir, "account.json")
            mapper.writeValue(file, fixture)
            logger.info { "Saved account fixture: $fixture → ${file.path}" }
        }
}
