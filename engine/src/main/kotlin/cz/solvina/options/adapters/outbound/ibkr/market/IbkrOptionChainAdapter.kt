package cz.solvina.options.adapters.outbound.ibkr.market

import com.ib.client.EClientSocket
import cz.solvina.options.adapters.outbound.ibkr.IbkrAdmissionConfig
import cz.solvina.options.adapters.outbound.ibkr.IbkrAdmissionController
import cz.solvina.options.adapters.outbound.ibkr.IbkrContractFactory
import cz.solvina.options.adapters.outbound.ibkr.cache.IbkrContractCache
import cz.solvina.options.adapters.outbound.ibkr.cache.IbkrOptionParamsCache
import cz.solvina.options.adapters.outbound.ibkr.cache.OptionContractKey
import cz.solvina.options.adapters.outbound.ibkr.cache.OptionParams
import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrMarketDataRegistry
import cz.solvina.options.domain.features.market.OptionChainPort
import cz.solvina.options.domain.features.market.model.ChainCoverage
import cz.solvina.options.domain.features.market.model.OptionQuote
import cz.solvina.options.domain.features.scanner.StrategyParamsRegistry
import cz.solvina.options.domain.features.spread.model.StrategyId
import cz.solvina.options.domain.models.Money
import cz.solvina.options.domain.models.OptionContract
import cz.solvina.options.domain.models.OptionGreeks
import cz.solvina.options.domain.models.OptionType
import cz.solvina.options.domain.models.Symbol
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

private val logger = KotlinLogging.logger {}

@Component
class IbkrOptionChainAdapter(
    private val registry: IbkrMarketDataRegistry,
    private val client: EClientSocket,
    private val admission: IbkrAdmissionController,
    private val admissionConfig: IbkrAdmissionConfig,
    private val strategyParams: StrategyParamsRegistry,
    private val optionParamsCache: IbkrOptionParamsCache,
    private val contractCache: IbkrContractCache,
    private val contractFactory: IbkrContractFactory,
) : OptionChainPort {
    // Greek-delivery coverage of the most recent chain fetch per symbol. Observational only; read by
    // the scan-status table to expose market-data starvation (many strikes requested, few greeks back).
    private val coverageBySymbol = ConcurrentHashMap<String, ChainCoverage>()

    override fun lastCoverage(symbol: Symbol): ChainCoverage? = coverageBySymbol[symbol.value]

    override suspend fun getAvailableExpirations(symbol: Symbol): Set<LocalDate> =
        optionParamsCache
            .getOrFetch(symbol)
            .expirations

    override suspend fun getOptionChain(
        symbol: Symbol,
        expiry: LocalDate,
        underlyingPrice: Money,
        strategyId: StrategyId,
    ): List<OptionQuote> {
        val params = optionParamsCache.getOrFetch(symbol)
        val strat = strategyParams.forStrategy(strategyId)
        val optionType = strat.optionType
        val isPut = optionType == OptionType.PUT
        val spot = underlyingPrice.amount
        val bandPct = BigDecimal(strat.strikeBandPercent)
        val width = strat.spreadWidthUsd

        // The OTM sold-leg band, plus a one-width extension for bought-leg candidates. Puts sit
        // BELOW the underlying (bought leg one width lower); calls sit ABOVE (bought leg one width
        // higher). [lowBound, highBound] bounds every candidate strike on the correct side.
        val lowBound: BigDecimal
        val highBound: BigDecimal
        val targetStrike: BigDecimal
        if (isPut) {
            lowBound = spot.multiply(BigDecimal.ONE.subtract(bandPct)).subtract(width)
            highBound = spot.multiply(BigDecimal("0.999"))
            targetStrike = spot.multiply(BigDecimal.ONE.subtract(BigDecimal(strat.targetDelta)))
        } else {
            lowBound = spot.multiply(BigDecimal("1.001"))
            highBound = spot.multiply(BigDecimal.ONE.add(bandPct)).add(width)
            targetStrike = spot.multiply(BigDecimal.ONE.add(BigDecimal(strat.targetDelta)))
        }

        // Prefer authoritative strikes from a prior reqContractDetails response for this expiry.
        // reqSecDefOptParams returns a flat union of all strikes across all expirations, so
        // strikesByExpiry[expiry] contains fine-grained near-term increments ($2.50/$5) that
        // don't exist for far-out monthly expiries ($10 increments). The verified set eliminates
        // those phantoms so candidate selection only ever picks real, tradeable strikes.
        // Authoritative strikes only. Resolve the verified set (one reqContractDetails, cached +
        // conIds warmed) rather than falling back to the reqSecDefOptParams union, which contains
        // phantom near-term strikes that don't exist for far-out monthlies — the confirmed cause of
        // "Strike not found" → no tick → no fill. If we can't verify, skip the symbol this cycle.
        val expiryStrikes =
            contractCache.getVerifiedStrikes(symbol, expiry, optionType)
                ?: contractCache.getOrFetchVerifiedStrikes(symbol, expiry, optionType)
                ?: run {
                    logger.warn { "[$symbol] Verified strikes unavailable for $expiry — skipping to avoid phantom strikes" }
                    return emptyList()
                }
        val validStrikes =
            expiryStrikes.filter { strike ->
                strike >= lowBound && strike <= highBound
            }

        if (validStrikes.isEmpty()) {
            logger.warn { "[$symbol] No valid OTM $optionType strikes found in [$lowBound, $highBound]" }
            return emptyList()
        }

        // Strikes near the moneyness-based target (high-delta region)
        val nearTarget =
            validStrikes
                .sortedBy { abs((it - targetStrike).toDouble()) }
                .take(strat.candidateStrikeCount)

        // Strikes from the far-OTM end of the valid band (covers high-IV underlyings where the
        // actual delta-matching strikes sit beyond the moneyness-based targetStrike) — the LOW end
        // for puts, the HIGH end for calls.
        val extremeBand =
            if (isPut) {
                validStrikes.sortedDescending().takeLast(strat.candidateStrikeCount)
            } else {
                validStrikes.sortedDescending().take(strat.candidateStrikeCount)
            }

        val nearest = (nearTarget + extremeBand).distinct()

        // Bought-leg candidates: one spread-width further OTM than each sold candidate (lower for
        // puts, higher for calls).
        val boughtLegs =
            nearest
                .flatMap { soldStrike ->
                    if (isPut) {
                        val boughtTarget = soldStrike.subtract(width)
                        validStrikes.filter { it <= boughtTarget }.sortedDescending().take(2)
                    } else {
                        val boughtTarget = soldStrike.add(width)
                        validStrikes.filter { it >= boughtTarget }.sorted().take(2)
                    }
                }.toSet()

        val allCandidates = (nearest + boughtLegs).distinct().sortedDescending()
        val candidateStrikes =
            allCandidates.filter { strike ->
                !contractCache.isMissing(OptionContractKey(symbol, expiry, strike, optionType))
            }
        val filteredCount = allCandidates.size - candidateStrikes.size
        if (filteredCount > 0) {
            logger.debug { "[$symbol] Filtered $filteredCount known-missing strike(s) from candidate list" }
        }

        logger.debug {
            "[$symbol] Fetching $optionType greeks for ${candidateStrikes.size} candidate strikes " +
                "(${nearest.size} sold + ${boughtLegs.size} bought-leg)"
        }

        // Bounded-parallel greeks fetch: candidates fan out as async snapshots, but actual
        // concurrency is bounded by the SCANNER admission gate (line sub-cap + message-token
        // floor) inside reqMktDataSnapshot — safe by construction, no separate semaphore. This
        // is the scan-time win: ~candidateCount × snapshot-timeout serial worst case collapses
        // to ~ceil(count / scanner-line-concurrency).
        return coroutineScope {
            candidateStrikes
                .map { strike -> async { fetchQuote(symbol, expiry, strike, optionType, params) } }
                .awaitAll()
                .filterNotNull()
        }.also { quotes ->
            coverageBySymbol[symbol.value] =
                ChainCoverage(
                    strikesRequested = candidateStrikes.size,
                    strikesWithGreeks = quotes.size,
                    fetchedAt = Instant.now(),
                )
        }
    }

    private suspend fun fetchQuote(
        symbol: Symbol,
        expiry: LocalDate,
        strike: BigDecimal,
        optionType: OptionType,
        params: OptionParams,
    ): OptionQuote? =
        runCatching {
            val contract = OptionContract(symbol, expiry, strike, optionType)
            // Prefer the conId warmed by the verified-strikes lookup — it identifies the exact
            // series unambiguously. Re-specifying by symbol/exchange/tradingClass/multiplier from
            // reqSecDefOptParams can disagree with the series the strikes were verified against
            // (seen on EUREX: verified strikes exist, yet every spec-based reqMktData gets error
            // 200 "no security definition"). Same approach the execution path already uses.
            val contractKey = OptionContractKey(symbol, expiry, strike, optionType)
            val conId = contractCache.getCachedOptionConId(contractKey)
            // Route with the venue the conId's series actually lists on (recorded when the strikes
            // were verified) — the configured/params exchange can disagree with the real listing on
            // EU venues. Fall back to the params venue, then the configured option exchange.
            val routingExchange =
                contractCache.getCachedOptionConIdExchange(contractKey)
                    ?: params.exchange.takeIf { it != "SMART" }
                    ?: contractFactory.defFor(symbol).optionExchange
            val mdContract =
                if (conId != null) {
                    contractFactory.conIdContract(conId, routingExchange)
                } else {
                    contractFactory.optionContract(contract, params.exchange, params.tradingClass, params.multiplier)
                }
            val requestDesc =
                if (conId != null) {
                    "conId=$conId route=$routingExchange"
                } else {
                    "spec exchange=${params.exchange} tradingClass=${params.tradingClass} multiplier=${params.multiplier}"
                }
            val snapshot =
                reqMktDataSnapshot(
                    registry,
                    client,
                    admission,
                    mdContract,
                    "",
                    SnapshotReady.OPTION_QUOTE,
                    timeoutMs = admissionConfig.greeksSnapshotTimeoutMs,
                    quiescenceMs = admissionConfig.scannerGreeksQuiescenceMs,
                )

            // Live market data only — never fabricate prices/greeks. A NaN delta means no live
            // option-computation tick arrived. Skip the strike and log it for investigation
            // instead of falling back to Black-Scholes.
            if (snapshot.delta.isNaN()) {
                logger.warn {
                    "[$symbol] No live greeks for strike $strike $expiry " +
                        "(bid=${snapshot.bid} ask=${snapshot.ask}, via $requestDesc) — skipping, no BS fallback"
                }
                return@runCatching null
            }

            val bid = snapshot.bid.takeIf { !it.isNaN() } ?: 0.0
            val ask = snapshot.ask.takeIf { !it.isNaN() } ?: 0.0
            OptionQuote(
                contract = contract,
                bid = Money(BigDecimal(bid).setScale(4, RoundingMode.HALF_UP)),
                ask = Money(BigDecimal(ask).setScale(4, RoundingMode.HALF_UP)),
                mid = Money(midPrice(bid, ask)),
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
