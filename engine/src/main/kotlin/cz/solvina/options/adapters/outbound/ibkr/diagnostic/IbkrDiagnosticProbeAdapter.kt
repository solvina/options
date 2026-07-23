package cz.solvina.options.adapters.outbound.ibkr.diagnostic

import com.ib.client.EClientSocket
import cz.solvina.options.adapters.outbound.ibkr.IbkrAdmissionController
import cz.solvina.options.adapters.outbound.ibkr.IbkrContractFactory
import cz.solvina.options.adapters.outbound.ibkr.account.IbkrOpenOrdersAdapter
import cz.solvina.options.adapters.outbound.ibkr.account.IbkrPositionsAdapter
import cz.solvina.options.adapters.outbound.ibkr.cache.IbkrContractCache
import cz.solvina.options.adapters.outbound.ibkr.cache.IbkrOptionParamsCache
import cz.solvina.options.adapters.outbound.ibkr.market.IbkrHistoricalDataAdapter
import cz.solvina.options.adapters.outbound.ibkr.market.SnapshotReady
import cz.solvina.options.adapters.outbound.ibkr.market.midPrice
import cz.solvina.options.adapters.outbound.ibkr.market.reqMktDataSnapshot
import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrMarketDataRegistry
import cz.solvina.options.adapters.outbound.ibkr.registry.PendingContinuousMarketDataRequest
import cz.solvina.options.adapters.outbound.ibkr.registry.PendingTickByTickRequest
import cz.solvina.options.domain.features.account.AccountPort
import cz.solvina.options.domain.features.diagnostic.AccountHealthReport
import cz.solvina.options.domain.features.diagnostic.DataSource
import cz.solvina.options.domain.features.diagnostic.DiagnosticProbePort
import cz.solvina.options.domain.features.diagnostic.SymbolHealthReport
import cz.solvina.options.domain.features.market.BlackScholes
import cz.solvina.options.domain.features.volatility.VolatilityPort
import cz.solvina.options.domain.models.OptionContract
import cz.solvina.options.domain.models.OptionType
import cz.solvina.options.domain.models.Symbol
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

private val logger = KotlinLogging.logger {}
private const val RISK_FREE_RATE = 0.05

@Component
class IbkrDiagnosticProbeAdapter(
    private val contractCache: IbkrContractCache,
    private val optionParamsCache: IbkrOptionParamsCache,
    private val historicalDataAdapter: IbkrHistoricalDataAdapter,
    private val volatilityPort: VolatilityPort,
    private val registry: IbkrMarketDataRegistry,
    private val client: EClientSocket,
    private val admission: IbkrAdmissionController,
    private val contractFactory: IbkrContractFactory,
    private val positionsAdapter: IbkrPositionsAdapter,
    private val openOrdersAdapter: IbkrOpenOrdersAdapter,
    private val accountPort: AccountPort,
    private val clock: Clock,
) : DiagnosticProbePort {
    override suspend fun probeContractResolution(symbol: Symbol): SymbolHealthReport.ContractResolutionResult {
        val start = System.currentTimeMillis()
        return runCatching {
            logger.info { "[$symbol] DIAG reqContractDetails (stock conId lookup)" }
            val conId = contractCache.getOrFetchUnderlyingConId(symbol)
            val ms = System.currentTimeMillis() - start
            logger.info { "[$symbol] DIAG reqContractDetails → conId=$conId in ${ms}ms" }
            SymbolHealthReport.ContractResolutionResult(conId, ms, null)
        }.getOrElse { e ->
            val ms = System.currentTimeMillis() - start
            logger.info { "[$symbol] DIAG reqContractDetails FAILED in ${ms}ms: ${e.message}" }
            SymbolHealthReport.ContractResolutionResult(null, ms, e.message)
        }
    }

    override suspend fun probeOptionParams(
        symbol: Symbol,
        stockConId: Int,
    ): SymbolHealthReport.OptionParamsResult =
        runCatching {
            logger.info { "[$symbol] DIAG reqSecDefOptParams (conId=$stockConId)" }
            val params = optionParamsCache.getOrFetch(symbol)
            val expiries = params.expirations.sorted()
            logger.info {
                "[$symbol] DIAG reqSecDefOptParams → strikes=${params.strikes.size} expiries=${expiries.size} range=${expiries.firstOrNull()}..${expiries.lastOrNull()}"
            }
            SymbolHealthReport.OptionParamsResult(params.strikes.size, expiries, null)
        }.getOrElse { e ->
            logger.info { "[$symbol] DIAG reqSecDefOptParams FAILED: ${e.message}" }
            SymbolHealthReport.OptionParamsResult(0, emptyList(), e.message)
        }

    override suspend fun probeHistoricalData(symbol: Symbol): SymbolHealthReport.HistoricalDataResult =
        runCatching {
            logger.info { "[$symbol] DIAG reqHistoricalData (IV bars, 365 days)" }
            val bars = historicalDataAdapter.fetchDailyBars(symbol, 365).toList()
            val ivBars = bars.filter { it.iv != null }
            logger.info {
                "[$symbol] DIAG reqHistoricalData → bars=${bars.size} ivPopulated=${ivBars.size} dateRange=${bars.firstOrNull()?.date}..${bars.lastOrNull()?.date}"
            }

            val ivRankResult = runCatching { volatilityPort.getIvRank(symbol) }.getOrNull()
            logger.info {
                "[$symbol] DIAG IV rank=${ivRankResult?.rank?.let {
                    "%.1f".format(
                        it,
                    )
                }} currentIv=${ivRankResult?.currentIv?.let { "%.4f".format(it) }}"
            }

            SymbolHealthReport.HistoricalDataResult(
                barCount = bars.size,
                ivPopulated = ivBars.isNotEmpty(),
                currentIv = ivRankResult?.currentIv,
                ivRank = ivRankResult?.rank,
                error = null,
            )
        }.getOrElse { e ->
            logger.info { "[$symbol] DIAG reqHistoricalData FAILED: ${e.message}" }
            SymbolHealthReport.HistoricalDataResult(0, false, null, null, e.message)
        }

    override suspend fun probeSpot(symbol: Symbol): SymbolHealthReport.SpotResult {
        val start = System.currentTimeMillis()
        return runCatching {
            logger.info { "[$symbol] DIAG reqMktData snapshot (stock spot)" }
            val snapshot =
                reqMktDataSnapshot(registry, client, contractFactory.stockContract(symbol), "", SnapshotReady.STOCK_PRICE)
            val ms = System.currentTimeMillis() - start
            logger.info {
                "[$symbol] DIAG reqMktData stock → bid=${snapshot.bid} ask=${snapshot.ask} " +
                    "last=${snapshot.last} close=${snapshot.close} in ${ms}ms"
            }

            val price = snapshot.last.takeIf { !it.isNaN() } ?: snapshot.close.takeIf { !it.isNaN() }
            if (price != null) {
                logger.info { "[$symbol] DIAG spot source=LIVE price=$price" }
                return@runCatching SymbolHealthReport.SpotResult(
                    BigDecimal(price).setScale(2, RoundingMode.HALF_UP),
                    DataSource.LIVE,
                    ms,
                    null,
                )
            }

            logger.info { "[$symbol] DIAG spot no live data, attempting historical fallback" }
            val bar = historicalDataAdapter.fetchDailyPriceBars(symbol, 5).toList().lastOrNull()
            logger.info { "[$symbol] DIAG spot historical fallback → close=${bar?.close}" }
            val source = if (bar != null) DataSource.HISTORICAL_FALLBACK else DataSource.UNAVAILABLE
            SymbolHealthReport.SpotResult(bar?.close, source, ms, if (bar == null) "no historical data" else null)
        }.getOrElse { e ->
            val ms = System.currentTimeMillis() - start
            logger.info { "[$symbol] DIAG spot probe FAILED in ${ms}ms: ${e.message}" }
            SymbolHealthReport.SpotResult(null, DataSource.UNAVAILABLE, ms, e.message)
        }
    }

    override suspend fun probeOptionSnapshot(contract: OptionContract): SymbolHealthReport.OptionMidSample {
        val start = System.currentTimeMillis()
        logger.info { "[${contract.symbol}] DIAG reqMktData snapshot (option ${contract.strike}P exp=${contract.expiry})" }
        val snapshot =
            reqMktDataSnapshot(registry, client, contractFactory.optionContract(contract), "", SnapshotReady.OPTION_QUOTE)
        val ms = System.currentTimeMillis() - start
        logger.info {
            "[${contract.symbol}] DIAG reqMktData option ${contract.strike}P → " +
                "bid=${snapshot.bid} ask=${snapshot.ask} delta=${snapshot.delta} " +
                "impliedVol=${snapshot.impliedVol} gamma=${snapshot.gamma} theta=${snapshot.theta} in ${ms}ms"
        }

        val mid = midPrice(snapshot.bid, snapshot.ask)
        val source: DataSource
        val finalMid: BigDecimal

        if (mid > BigDecimal.ZERO) {
            source = DataSource.LIVE
            finalMid = mid
            logger.info { "[${contract.symbol}] DIAG option ${contract.strike}P source=LIVE mid=$mid" }
        } else {
            // Attempt BS fallback and report it
            val spot =
                runCatching {
                    val stock = contractFactory.stockContract(contract.symbol)
                    val snap = reqMktDataSnapshot(registry, client, stock, "", SnapshotReady.STOCK_PRICE)
                    snap.last.takeIf { !it.isNaN() } ?: snap.close.takeIf { !it.isNaN() }
                }.getOrNull()
            val sigma = runCatching { volatilityPort.getIvRank(contract.symbol).currentIv }.getOrNull()
            val tte = ChronoUnit.DAYS.between(LocalDate.now(), contract.expiry).toInt() / 365.0

            if (spot != null && sigma != null && sigma > 0.0 && tte > 0.0 && contract.type == OptionType.PUT) {
                val bs = BlackScholes.putPrice(spot, contract.strike.toDouble(), tte, RISK_FREE_RATE, sigma)
                finalMid = BigDecimal(bs).setScale(4, RoundingMode.HALF_UP)
                source = DataSource.BS_FALLBACK
                logger.info {
                    "[${contract.symbol}] DIAG option ${contract.strike}P source=BS_FALLBACK bs=${"%.4f".format(
                        bs,
                    )} (spot=$spot sigma=${"%.4f".format(sigma)})"
                }
            } else {
                finalMid = BigDecimal.ZERO
                source = DataSource.UNAVAILABLE
                logger.info { "[${contract.symbol}] DIAG option ${contract.strike}P source=UNAVAILABLE (spot=$spot sigma=$sigma tte=$tte)" }
            }
        }

        return SymbolHealthReport.OptionMidSample(
            strike = contract.strike,
            expiry = contract.expiry,
            bid = snapshot.bid.takeIf { !it.isNaN() } ?: Double.NaN,
            ask = snapshot.ask.takeIf { !it.isNaN() } ?: Double.NaN,
            mid = finalMid,
            delta = snapshot.delta.takeIf { !it.isNaN() } ?: Double.NaN,
            impliedVol = snapshot.impliedVol.takeIf { !it.isNaN() } ?: Double.NaN,
            source = source,
            durationMs = ms,
        )
    }

    override suspend fun probeTickStream(
        symbol: Symbol,
        soldContract: OptionContract,
        boughtContract: OptionContract,
        windowMs: Long,
    ): SymbolHealthReport.TickStreamResult {
        logger.info {
            "[$symbol] DIAG tick stream probe: subscribe reqTickByTickData + reqMktData(Greeks) " +
                "for ${soldContract.strike}P and ${boughtContract.strike}P — window=${windowMs}ms"
        }

        val tickCount = AtomicInteger(0)
        val lastBid = AtomicReference(Double.NaN)
        val lastAsk = AtomicReference(Double.NaN)
        val lastDelta = AtomicReference(Double.NaN)

        val soldTickReqId = registry.nextReqId()
        val boughtTickReqId = registry.nextReqId()
        val soldGreeksReqId = registry.nextReqId()
        val boughtGreeksReqId = registry.nextReqId()
        val underlyingReqId = registry.nextReqId()

        registry.pendingTickByTick[soldTickReqId] =
            PendingTickByTickRequest { tick ->
                tickCount.incrementAndGet()
                lastBid.set(tick.bidPrice)
                lastAsk.set(tick.askPrice)
                logger.info { "[$symbol] DIAG tick BidAsk sold: bid=${tick.bidPrice} ask=${tick.askPrice}" }
                true
            }
        registry.pendingTickByTick[boughtTickReqId] =
            PendingTickByTickRequest { tick ->
                tickCount.incrementAndGet()
                logger.info { "[$symbol] DIAG tick BidAsk bought: bid=${tick.bidPrice} ask=${tick.askPrice}" }
                true
            }
        registry.pendingContinuousMarketData[soldGreeksReqId] =
            PendingContinuousMarketDataRequest(
                onUpdate = { snap ->
                    if (!snap.delta.isNaN()) lastDelta.set(snap.delta)
                    logger.info { "[$symbol] DIAG tick Greeks sold: delta=${snap.delta} impliedVol=${snap.impliedVol}" }
                },
            )
        registry.pendingContinuousMarketData[boughtGreeksReqId] =
            PendingContinuousMarketDataRequest(
                onUpdate = { snap ->
                    logger.info { "[$symbol] DIAG tick Greeks bought: delta=${snap.delta} impliedVol=${snap.impliedVol}" }
                },
            )
        registry.pendingContinuousMarketData[underlyingReqId] =
            PendingContinuousMarketDataRequest(
                onUpdate = { snap ->
                    val price = snap.last.takeIf { !it.isNaN() } ?: snap.close.takeIf { !it.isNaN() }
                    if (price != null) logger.info { "[$symbol] DIAG tick underlying price: $price" }
                },
            )

        client.reqMktData(underlyingReqId, contractFactory.stockContract(symbol), "", false, false, null)
        client.reqTickByTickData(soldTickReqId, contractFactory.optionContract(soldContract), "BidAsk", 0, true)
        client.reqTickByTickData(boughtTickReqId, contractFactory.optionContract(boughtContract), "BidAsk", 0, true)
        client.reqMktData(soldGreeksReqId, contractFactory.optionContract(soldContract), "100", false, false, null)
        client.reqMktData(boughtGreeksReqId, contractFactory.optionContract(boughtContract), "100", false, false, null)

        logger.info { "[$symbol] DIAG tick stream active — waiting ${windowMs}ms" }
        delay(windowMs)

        // Cancel all subscriptions
        registry.pendingTickByTick.remove(soldTickReqId)
        client.cancelTickByTickData(soldTickReqId)
        registry.pendingTickByTick.remove(boughtTickReqId)
        client.cancelTickByTickData(boughtTickReqId)
        registry.pendingContinuousMarketData.remove(soldGreeksReqId)
        client.cancelMktData(soldGreeksReqId)
        registry.pendingContinuousMarketData.remove(boughtGreeksReqId)
        client.cancelMktData(boughtGreeksReqId)
        registry.pendingContinuousMarketData.remove(underlyingReqId)
        client.cancelMktData(underlyingReqId)

        logger.info {
            "[$symbol] DIAG tick stream done — ticksReceived=${tickCount.get()} lastBid=${lastBid.get()} lastAsk=${lastAsk.get()} lastDelta=${lastDelta.get()}"
        }

        return SymbolHealthReport.TickStreamResult(
            ticksReceived = tickCount.get(),
            lastBid = lastBid.get(),
            lastAsk = lastAsk.get(),
            lastDelta = lastDelta.get(),
            windowMs = windowMs,
            error = null,
        )
    }

    override suspend fun probeAccount(): AccountHealthReport {
        logger.info { "DIAG account probe start" }
        val now = Instant.now(clock)

        val accountDetail = accountPort.accountDetail.value
        logger.info {
            "DIAG account: netLiq=${accountDetail?.totalCapital?.amount} availableFunds=${accountDetail?.availableFunds?.amount}"
        }

        val positionResult = runCatching { positionsAdapter.getPositions() }
        positionResult.onSuccess { logger.info { "DIAG positions: count=${it.size}" } }
        positionResult.onFailure { logger.info { "DIAG positions FAILED: ${it.message}" } }

        val ordersResult = runCatching { openOrdersAdapter.getOpenOrders() }
        ordersResult.onSuccess { logger.info { "DIAG open orders: count=${it.size} ids=${it.map { o -> o.orderId }}" } }
        ordersResult.onFailure { logger.info { "DIAG open orders FAILED: ${it.message}" } }

        return AccountHealthReport(
            probedAt = now,
            netLiquidation = accountDetail?.totalCapital?.amount,
            availableFunds = accountDetail?.availableFunds?.amount,
            accountError = if (accountDetail == null) "no account data received yet" else null,
            positionCount = positionResult.getOrElse { emptyList() }.size,
            positionsError = positionResult.exceptionOrNull()?.message,
            openOrderCount = ordersResult.getOrElse { emptyList() }.size,
            openOrdersError = ordersResult.exceptionOrNull()?.message,
        )
    }
}
