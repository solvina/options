package cz.solvina.options.domain.features.scanner

import cz.solvina.options.domain.features.account.AccountPort
import cz.solvina.options.domain.features.market.MarketDataPort
import cz.solvina.options.domain.features.market.OptionChainPort
import cz.solvina.options.domain.features.order.LegAction
import cz.solvina.options.domain.features.order.OrderPort
import cz.solvina.options.domain.features.order.OrderStatus
import cz.solvina.options.domain.features.spread.SpreadPort
import cz.solvina.options.domain.features.spread.model.BullPutSpread
import cz.solvina.options.domain.features.spread.model.SpreadLeg
import cz.solvina.options.domain.features.spread.model.SpreadStatus
import cz.solvina.options.domain.features.volatility.VolatilityPort
import cz.solvina.options.domain.features.watchlist.WatchlistPort
import cz.solvina.options.domain.models.Money
import cz.solvina.options.domain.models.OptionType
import cz.solvina.options.domain.models.Symbol
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

private val logger = KotlinLogging.logger {}

@Service
class ScannerService(
    private val watchlistPort: WatchlistPort,
    private val volatilityPort: VolatilityPort,
    private val marketDataPort: MarketDataPort,
    private val optionChainPort: OptionChainPort,
    private val accountPort: AccountPort,
    private val orderPort: OrderPort,
    private val spreadPort: SpreadPort,
    private val config: ScannerConfig,
    private val clock: Clock,
) : ScannerPort {
    private val ivRanksSnapshot = ConcurrentHashMap<String, Double>()

    @Volatile private var lastRunAt: Instant? = null

    override suspend fun scan() {
        lastRunAt = Instant.now(clock)
        logger.info { "Scanner run started" }

        val openCount = spreadPort.countByStatus(SpreadStatus.OPEN)
        if (openCount >= config.maxOpenSpreads) {
            logger.info { "Max open spreads reached ($openCount/${config.maxOpenSpreads}), skipping scan" }
            return
        }

        val accountDetail =
            accountPort.accountDetail.value ?: run {
                logger.info { "Account detail not yet received, skipping scan" }
                return
            }
        val totalCapital =
            accountDetail.totalCapital ?: run {
                logger.info { "Net liquidation not yet available, skipping scan" }
                return
            }
        val openSpreads = spreadPort.findOpen()
        val symbolsWithOpenSpread = openSpreads.map { it.symbol }.toSet()

        val watchlist = watchlistPort.getWatchlist()
        for (symbol in watchlist) {
            if (symbolsWithOpenSpread.contains(symbol)) {
                logger.debug { "[$symbol] Already has open spread, skipping" }
                continue
            }
            runCatching { scanSymbol(symbol, totalCapital) }
                .onFailure { e -> logger.error(e) { "[$symbol] Error during scan: ${e.message}" } }
        }

        logger.info { "Scanner run complete" }
    }

    private suspend fun scanSymbol(
        symbol: Symbol,
        totalCapital: Money,
    ) {
        // 1. IV Rank filter
        val ivRank = volatilityPort.getIvRank(symbol)
        ivRanksSnapshot[symbol.value] = ivRank.rank
        if (ivRank.rank < config.ivRankThreshold) {
            logger.info { "[$symbol] IV Rank ${"%.1f".format(ivRank.rank)}% < threshold ${config.ivRankThreshold}%, skipping" }
            return
        }

        val underlyingPrice = marketDataPort.getUnderlyingPrice(symbol)

        // 2. Select expiry
        val today = LocalDate.now(clock)
        val availableExpirations = optionChainPort.getAvailableExpirations(symbol)
        val expiry =
            availableExpirations
                .filter { exp ->
                    val dte = ChronoUnit.DAYS.between(today, exp).toInt()
                    dte in config.minDte..config.maxDte
                }.minByOrNull { exp ->
                    abs(ChronoUnit.DAYS.between(today, exp).toInt() - config.preferredDte)
                }
        if (expiry == null) {
            logger.info { "[$symbol] No valid expiry in [${config.minDte}, ${config.maxDte}] DTE, skipping" }
            return
        }
        val dte = ChronoUnit.DAYS.between(today, expiry).toInt()
        logger.info { "[$symbol] Selected expiry $expiry ($dte DTE)" }

        // 3. Get option chain and find the best put
        val chain = optionChainPort.getOptionChain(symbol, expiry, underlyingPrice)
        val puts = chain.filter { it.contract.type == OptionType.PUT }

        val soldQuote =
            puts
                .filter { quote ->
                    val absDelta = abs(quote.greeks.delta)
                    absDelta in config.deltaMin..config.deltaMax
                }.minByOrNull { quote ->
                    abs(abs(quote.greeks.delta) - config.targetDelta)
                }
        if (soldQuote == null) {
            logger.info { "[$symbol] No put with delta in [${config.deltaMin}, ${config.deltaMax}] found, skipping" }
            return
        }

        logger.info { "[$symbol] Selected sold put strike=${soldQuote.contract.strike} delta=${soldQuote.greeks.delta}" }

        // 4. Find bought strike
        val targetBoughtStrike = soldQuote.contract.strike.subtract(config.spreadWidthUsd)
        val boughtQuote =
            puts
                .filter { it.contract.strike <= targetBoughtStrike }
                .maxByOrNull { it.contract.strike }
        if (boughtQuote == null) {
            logger.info { "[$symbol] No valid bought strike ≤ $targetBoughtStrike found, skipping" }
            return
        }

        // 5. Credit check
        val credit =
            soldQuote.mid.amount
                .subtract(boughtQuote.mid.amount)
                .setScale(4, RoundingMode.HALF_UP)
        if (credit < config.minCreditPerShare) {
            logger.info { "[$symbol] Credit $credit < minimum ${config.minCreditPerShare}, skipping" }
            return
        }

        val actualSpreadWidth = soldQuote.contract.strike.subtract(boughtQuote.contract.strike)
        val maxRiskPerShare = actualSpreadWidth.subtract(credit).setScale(4, RoundingMode.HALF_UP)

        // 6. Money management check
        val maxRiskPerContract = maxRiskPerShare.multiply(BigDecimal("100"))
        val allowedRiskPerTrade = totalCapital.amount.multiply(BigDecimal(config.maxRiskPercent))
        if (maxRiskPerContract > allowedRiskPerTrade) {
            logger.info {
                "[$symbol] Max risk/contract $$maxRiskPerContract > allowed $${
                    "%.2f".format(
                        allowedRiskPerTrade,
                    )
                }, skipping"
            }
            return
        }

        logger.info {
            "[$symbol] Opening spread: SELL ${soldQuote.contract.strike}P / BUY ${boughtQuote.contract.strike}P " +
                "credit=\$$credit maxRisk=\$$maxRiskPerShare"
        }

        // 7. Place sold leg
        val soldLegOrder =
            orderPort.placeAndAwaitFill(
                contract = soldQuote.contract,
                action = LegAction.SELL,
                limitPrice = Money(credit),
                qty = 1,
            )
        if (soldLegOrder.status != OrderStatus.FILLED) {
            logger.warn { "[$symbol] Sold leg did not fill (status=${soldLegOrder.status}), aborting" }
            return
        }

        // 8. Place bought leg
        val boughtLegOrder =
            orderPort.placeAndAwaitFill(
                contract = boughtQuote.contract,
                action = LegAction.BUY,
                limitPrice = boughtQuote.mid,
                qty = 1,
            )
        if (boughtLegOrder.status != OrderStatus.FILLED) {
            logger.warn { "[$symbol] Bought leg did not fill (status=${boughtLegOrder.status}), cancelling sold leg" }
            runCatching { orderPort.cancelOrder(soldLegOrder.orderId) }
            return
        }

        // 9. Persist spread
        val spread =
            BullPutSpread(
                id = null,
                symbol = symbol,
                soldLeg =
                    SpreadLeg(
                        contract = soldQuote.contract,
                        action = LegAction.SELL,
                        premium = Money(soldQuote.mid.amount),
                        orderId = soldLegOrder.orderId,
                    ),
                boughtLeg =
                    SpreadLeg(
                        contract = boughtQuote.contract,
                        action = LegAction.BUY,
                        premium = Money(boughtQuote.mid.amount),
                        orderId = boughtLegOrder.orderId,
                    ),
                creditPerShare = credit,
                maxRiskPerShare = maxRiskPerShare,
                quantity = 1,
                status = SpreadStatus.OPEN,
                ivRankAtEntry = ivRank.rank,
                underlyingPriceAtEntry = underlyingPrice.amount,
                openedAt = Instant.now(clock),
            )

        spreadPort.save(spread)
        logger.info { "[$symbol] Spread saved successfully" }
    }

    fun getLastRunAt(): Instant? = lastRunAt

    fun getIvRanksSnapshot(): Map<String, Double> = ivRanksSnapshot.toMap()
}
