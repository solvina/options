package cz.solvina.options.adapters.outbound.ibkr.market

import com.ib.client.Contract
import com.ib.client.EClientSocket
import cz.solvina.options.adapters.outbound.ibkr.cache.IbkrOptionParamsCache
import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrRequestRegistry
import cz.solvina.options.adapters.outbound.ibkr.registry.MarketDataSnapshot
import cz.solvina.options.adapters.outbound.ibkr.registry.PendingMarketDataRequest
import cz.solvina.options.domain.features.market.MarketDataPort
import cz.solvina.options.domain.features.market.OptionChainPort
import cz.solvina.options.domain.features.market.model.OptionQuote
import cz.solvina.options.domain.features.scanner.ScannerConfig
import cz.solvina.options.domain.models.Money
import cz.solvina.options.domain.models.OptionContract
import cz.solvina.options.domain.models.OptionGreeks
import cz.solvina.options.domain.models.OptionType
import cz.solvina.options.domain.models.Symbol
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CompletableDeferred
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs

private val logger = KotlinLogging.logger {}

@Component
class IbkrMarketDataAdapter(
    private val registry: IbkrRequestRegistry,
    private val client: EClientSocket,
    private val scannerConfig: ScannerConfig,
    private val optionParamsCache: IbkrOptionParamsCache,
) : MarketDataPort,
    OptionChainPort {
    override suspend fun getUnderlyingPrice(symbol: Symbol): Money {
        val snapshot = reqMktDataSnapshot(buildStockContract(symbol), "")
        val price =
            snapshot.last
                .takeIf { !it.isNaN() }
                ?: snapshot.close.takeIf { !it.isNaN() }
                ?: error("No price data available for $symbol")
        return Money(BigDecimal(price).setScale(2, RoundingMode.HALF_UP))
    }

    override suspend fun getOptionMid(contract: OptionContract): Money {
        val snapshot = reqMktDataSnapshot(buildOptionContract(contract), "100")
        return Money(midPrice(snapshot.bid, snapshot.ask))
    }

    override suspend fun getAvailableExpirations(symbol: Symbol): Set<LocalDate> {
        val params = optionParamsCache.getOrFetch(symbol)
        return params.expirations
    }

    override suspend fun getOptionChain(
        symbol: Symbol,
        expiry: LocalDate,
        underlyingPrice: Money,
    ): List<OptionQuote> {
        val params = optionParamsCache.getOrFetch(symbol)

        // Filter OTM puts only: strike < underlying, within strikeBandPercent below
        val lowerBound = underlyingPrice.amount.multiply(BigDecimal.ONE.subtract(BigDecimal(scannerConfig.strikeBandPercent)))
        val upperBound = underlyingPrice.amount.multiply(BigDecimal("0.999"))

        val validStrikes =
            params.strikes.filter { strike ->
                strike in lowerBound..upperBound && params.expirations.contains(expiry)
            }

        if (validStrikes.isEmpty()) {
            logger.warn { "[$symbol] No valid OTM put strikes found in [$lowerBound, $upperBound]" }
            return emptyList()
        }

        // Sort by distance from target delta region and take top N candidates
        val targetStrike = underlyingPrice.amount.multiply(BigDecimal.ONE.subtract(BigDecimal(scannerConfig.targetDelta)))
        val candidateStrikes =
            validStrikes
                .sortedBy { abs((it - targetStrike).toDouble()) }
                .take(scannerConfig.candidateStrikeCount)

        logger.debug { "[$symbol] Fetching greeks for ${candidateStrikes.size} candidate strikes near $targetStrike" }

        return candidateStrikes.mapNotNull { strike ->
            runCatching {
                val contract = OptionContract(symbol, expiry, strike, OptionType.PUT)
                val snapshot = reqMktDataSnapshot(buildOptionContract(contract), "100")
                val bid = snapshot.bid.takeIf { !it.isNaN() } ?: 0.0
                val ask = snapshot.ask.takeIf { !it.isNaN() } ?: 0.0
                val mid = midPrice(bid, ask)

                if (snapshot.delta.isNaN()) {
                    logger.debug { "[$symbol] Strike $strike has no delta data, skipping" }
                    return@runCatching null
                }

                OptionQuote(
                    contract = contract,
                    bid = Money(BigDecimal(bid).setScale(4, RoundingMode.HALF_UP)),
                    ask = Money(BigDecimal(ask).setScale(4, RoundingMode.HALF_UP)),
                    mid = Money(mid),
                    greeks =
                        OptionGreeks(
                            delta = snapshot.delta,
                            gamma = snapshot.gamma.takeIf { !it.isNaN() } ?: 0.0,
                            theta = snapshot.theta.takeIf { !it.isNaN() } ?: 0.0,
                            vega = snapshot.vega.takeIf { !it.isNaN() } ?: 0.0,
                            iv = snapshot.impliedVol.takeIf { !it.isNaN() } ?: 0.0,
                        ),
                )
            }.getOrElse { e ->
                logger.warn { "[$symbol] Failed to get greeks for strike $strike: ${e.message}" }
                null
            }
        }
    }

    private suspend fun reqMktDataSnapshot(
        contract: Contract,
        genericTickList: String,
    ): MarketDataSnapshot {
        val reqId = registry.nextDataReqId()
        val deferred = CompletableDeferred<MarketDataSnapshot>()
        registry.pendingMarketData[reqId] = PendingMarketDataRequest(deferred)
        client.reqMktData(reqId, contract, genericTickList, true, false, null)
        return deferred.await()
    }

    private fun buildStockContract(symbol: Symbol): Contract =
        Contract().apply {
            symbol(symbol.value)
            secType("STK")
            currency("USD")
            exchange("SMART")
        }

    private fun buildOptionContract(contract: OptionContract): Contract =
        Contract().apply {
            symbol(contract.symbol.value)
            secType("OPT")
            currency("USD")
            exchange("SMART")
            lastTradeDateOrContractMonth(contract.expiry.format(DateTimeFormatter.ofPattern("yyyyMMdd")))
            strike(contract.strike.toDouble())
            right(contract.type.ibkrCode)
            multiplier("100")
        }

    private fun midPrice(
        bid: Double,
        ask: Double,
    ): BigDecimal {
        val b = bid.takeIf { !it.isNaN() && it > 0 }
        val a = ask.takeIf { !it.isNaN() && it > 0 }
        return when {
            b != null && a != null -> BigDecimal((b + a) / 2).setScale(4, RoundingMode.HALF_UP)
            b != null -> BigDecimal(b).setScale(4, RoundingMode.HALF_UP)
            a != null -> BigDecimal(a).setScale(4, RoundingMode.HALF_UP)
            else -> BigDecimal.ZERO
        }
    }
}
