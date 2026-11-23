package cz.solvina.options.adapters.outbound.ibkr.market

import cz.solvina.options.adapters.outbound.ibkr.IbkrContractFactory
import cz.solvina.options.adapters.outbound.ibkr.cache.IbkrContractCache
import cz.solvina.options.adapters.outbound.ibkr.cache.IbkrOptionParamsCache
import cz.solvina.options.adapters.outbound.ibkr.cache.OptionContractKey
import cz.solvina.options.adapters.outbound.ibkr.cache.OptionParams
import cz.solvina.options.domain.features.market.GreeksEstimator
import cz.solvina.options.domain.features.market.OptionChainPort
import cz.solvina.options.domain.features.market.model.ChainCoverage
import cz.solvina.options.domain.features.market.model.OptionQuote
import cz.solvina.options.domain.features.scanner.StrategyParams
import cz.solvina.options.domain.features.scanner.StrategyParamsRegistry
import cz.solvina.options.domain.features.spread.model.StrategyId
import cz.solvina.options.domain.features.volatility.VolatilityPort
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
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

private val logger = KotlinLogging.logger {}

// A cached aim IV older than this falls back to the ATM figure (vol regimes move within days).
private val AIM_IV_MAX_AGE: java.time.Duration = java.time.Duration.ofDays(3)

@Component
class IbkrOptionChainAdapter(
    private val marketSnapshotHelper: MarketSnapshotHelper,
    private val strategyParams: StrategyParamsRegistry,
    private val optionParamsCache: IbkrOptionParamsCache,
    private val contractCache: IbkrContractCache,
    private val contractFactory: IbkrContractFactory,
    private val clock: Clock,
    // Source of the at-the-money IV used to AIM the bull-put strike search when no recent IBKR
    // option IV for the symbol is cached. Never used to price or select anything.
    private val volatilityPort: VolatilityPort,
) : OptionChainPort {
    // IBKR's own IV near the target delta from the last bull-put chain, per symbol. Includes the
    // OTM skew, so it aims better than the ATM figure. In-memory: rebuilt by the first scan.
    private val aimIvCache = ConcurrentHashMap<String, Pair<Double, Instant>>()

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
        if (isPut) {
            lowBound = spot.multiply(BigDecimal.ONE.subtract(bandPct)).subtract(width)
            highBound = spot.multiply(BigDecimal("0.999"))
        } else {
            lowBound = spot.multiply(BigDecimal("1.001"))
            highBound = spot.multiply(BigDecimal.ONE.add(bandPct)).add(width)
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

        // The short strike is located from live deltas. The old moneyness guess read targetDelta as a
        // %-OTM distance (0.25 → 25% from spot), so only far-OTM strikes were ever priced and low-vol
        // names never reached the delta band (puts fixed 2026-11-20, calls 2026-11-23).
        return fetchChainByProbe(symbol, expiry, spot, width, validStrikes, strat, params, optionType)
    }

    /**
     * Spread chain (bull put or bear call). The short strike is always chosen on IBKR's live deltas;
     * only the search differs:
     *  1. aim — when an IV is known, Black-Scholes points at the target-delta strike and ONE round
     *     prices the nearest strikes plus their bought legs. Done when the live deltas cover the target.
     *  2. otherwise probe — [StrategyParams.candidateStrikeCount] strikes spread across the OTM band,
     *     then refine the strikes between the two probes whose live |delta| brackets the target.
     * Falls back to the probes alone when none of them returned a live delta. [PutStrikeProbe] assumes
     * |delta| rises with the strike (puts); calls are mirrored into that shape (strike → −strike).
     */
    private suspend fun fetchChainByProbe(
        symbol: Symbol,
        expiry: LocalDate,
        spot: BigDecimal,
        width: BigDecimal,
        validStrikes: List<BigDecimal>,
        strat: StrategyParams,
        params: OptionParams,
        type: OptionType,
    ): List<OptionQuote> {
        val isPut = type == OptionType.PUT
        val key = { k: BigDecimal -> if (isPut) k else k.negate() }
        val notMissing = { strike: BigDecimal ->
            !contractCache.isMissing(OptionContractKey(symbol, expiry, strike, type))
        }
        val count = strat.candidateStrikeCount
        val years = yearsTo(expiry)
        // Bought legs sit one width further OTM: below the short put, above the short call.
        val boughtLegsFor = { sold: List<BigDecimal> ->
            sold
                .flatMap { k ->
                    if (isPut) {
                        validStrikes.filter { it <= k.subtract(width) }.sortedDescending().take(2)
                    } else {
                        validStrikes.filter { it >= k.add(width) }.sorted().take(2)
                    }
                }.toSet()
        }
        val live = validStrikes.filter(notMissing)

        // 1. Black-Scholes aim: one round around the strike the model puts at the target delta.
        val aimed = mutableListOf<OptionQuote>()
        val aimIv = aimVolatility(symbol, type)
        if (aimIv != null) {
            val aim = PutStrikeAim.strikeForDelta(type, spot.toDouble(), years, aimIv.first, strat.targetDelta)
            val sold = PutStrikeAim.nearest(live, aim, count)
            val round = (sold + boughtLegsFor(sold)).distinct().filter(notMissing)
            aimed += fetchQuotes(symbol, expiry, round, type, params)
            val soldDeltas =
                aimed.filter { it.contract.strike in sold && !it.greeks.delta.isNaN() }.map { abs(it.greeks.delta) }
            val hit = PutStrikeAim.covers(soldDeltas, strat.targetDelta)
            logger.debug {
                "[$symbol] $type aim iv=${"%.3f".format(aimIv.first)} (${aimIv.second}) → strike ${"%.2f".format(aim)}, " +
                    "priced ${round.size}, live sold |delta| ${soldDeltas.joinToString { "%.3f".format(it) }} → " +
                    if (hit) "hit" else "miss, probing"
            }
            if (hit) {
                rememberAimIv(symbol, type, aimed, strat.targetDelta)
                return finishChain(symbol, expiry, spot, round.size, aimed)
            }
        }

        // 2. Model-free fallback: probe the whole band, then refine inside the live-delta bracket.
        val priced = aimed.map { it.contract.strike }.toSet()
        val probes = PutStrikeProbe.probeStrikes(live.map(key), count).map(key)
        val probed =
            fetchQuotes(symbol, expiry, probes.filter { it !in priced }, type, params) +
                aimed.filter { it.contract.strike in probes }

        // The aimed round's live deltas are real data too — they sharpen the bracket for free.
        val probeDeltas =
            (probed + aimed).distinctBy { it.contract.strike }.map { q ->
                key(q.contract.strike) to
                    q.greeks.delta
                        .takeIf { !it.isNaN() }
                        ?.let { abs(it) }
            }
        val bracket = PutStrikeProbe.bracket(probeDeltas, strat.targetDelta)
        val soldCandidates =
            if (bracket == null) {
                probes
            } else {
                (
                    PutStrikeProbe.refineStrikes(live.map(key), bracket, count, strat.targetDelta) +
                        listOfNotNull(bracket.upper, bracket.lower)
                ).map(key)
            }
        val boughtLegs = boughtLegsFor(soldCandidates)
        val alreadyPriced = probed.map { it.contract.strike }.toSet() + probes + priced
        val round2 = (soldCandidates + boughtLegs).distinct().filter { it !in alreadyPriced && notMissing(it) }

        logger.debug {
            "[$symbol] $type probe ${probes.size} strike(s) ${probeDeltas.joinToString {
                "${key(it.first)}:${it.second?.let { d ->
                    "%.3f".format(d)
                } ?: "NaN"}"
            }}" +
                " → bracket ${bracket?.let { "${it.lower?.let(key) ?: "open"}..${it.upper?.let(key) ?: "open"}" } ?: "none"}, " +
                "refining ${round2.size} strike(s)"
        }
        val refined = fetchQuotes(symbol, expiry, round2, type, params)
        val all = (aimed + probed + refined).distinctBy { it.contract.strike }
        rememberAimIv(symbol, type, all, strat.targetDelta)
        return finishChain(symbol, expiry, spot, aimed.size + probes.size + round2.size, all)
    }

    /** Cached IBKR IV for [symbol]/[type] while fresh, else the ATM IV behind its IV rank; null = no aim. */
    private suspend fun aimVolatility(
        symbol: Symbol,
        type: OptionType,
    ): Pair<Double, String>? {
        val now = Instant.now(clock)
        aimIvCache["${symbol.value}:$type"]
            ?.takeIf { (iv, at) -> iv > 0.0 && at.isAfter(now.minus(AIM_IV_MAX_AGE)) }
            ?.let { return it.first to "last chain" }
        val rank = runCatching { volatilityPort.getIvRank(symbol) }.getOrNull() ?: return null
        return rank.currentIv.takeIf { it > 0.0 && it < 5.0 }?.let { it to "ATM" }
    }

    /** Puts and calls are cached apart: the skew gives them different IVs at the same |delta|. */
    private fun rememberAimIv(
        symbol: Symbol,
        type: OptionType,
        quotes: List<OptionQuote>,
        targetDelta: Double,
    ) {
        quotes
            .filter { GreeksEstimator.hasLiveGreeks(it) && it.greeks.iv > 0.0 }
            .minByOrNull { abs(abs(it.greeks.delta) - targetDelta) }
            ?.let { aimIvCache["${symbol.value}:$type"] = it.greeks.iv to Instant.now(clock) }
    }

    private fun yearsTo(expiry: LocalDate): Double = ChronoUnit.DAYS.between(LocalDate.now(clock), expiry).coerceAtLeast(1) / 365.0

    private suspend fun fetchQuotes(
        symbol: Symbol,
        expiry: LocalDate,
        strikes: List<BigDecimal>,
        optionType: OptionType,
        params: OptionParams,
    ): List<OptionQuote> =
        coroutineScope {
            strikes
                .map { strike -> async { fetchQuote(symbol, expiry, strike, optionType, params) } }
                .awaitAll()
                .filterNotNull()
        }

    private fun finishChain(
        symbol: Symbol,
        expiry: LocalDate,
        spot: BigDecimal,
        strikesRequested: Int,
        fetched: List<OptionQuote>,
    ): List<OptionQuote> {
        coverageBySymbol[symbol.value] =
            ChainCoverage(
                strikesRequested = strikesRequested,
                strikesWithGreeks = fetched.count { GreeksEstimator.hasLiveGreeks(it) },
                fetchedAt = Instant.now(),
            )
        // Strikes that arrived priced but without greeks get them estimated from live neighbours;
        // any left NaN remain long-leg candidates only (the short-leg delta band never matches NaN).
        val years = yearsTo(expiry)
        return GreeksEstimator.fillMissing(fetched, spot.toDouble(), years).also { quotes ->
            quotes.filter { it.greeksEstimated }.forEach { q ->
                logger.info {
                    "[$symbol] Estimated greeks for strike ${q.contract.strike} $expiry from live neighbours " +
                        "(delta=${"%.3f".format(q.greeks.delta)} iv=${"%.3f".format(q.greeks.iv)})"
                }
            }
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
                marketSnapshotHelper.reqMktDataSnapshot(
                    symbol,
                    mdContract,
                    "scanner candidate-strike greeks ($requestDesc)",
                    SnapshotReady.OPTION_QUOTE,
                )

            // Prices are never fabricated. A NaN delta means no option-computation tick arrived: a
            // strike with a real two-sided quote is kept (greeks NaN, estimated from live neighbours
            // by the caller); one without a usable quote is skipped as before.
            val bid = snapshot.bid.takeIf { !it.isNaN() } ?: 0.0
            val ask = snapshot.ask.takeIf { !it.isNaN() } ?: 0.0
            if (snapshot.delta.isNaN()) {
                if (bid <= 0.0 || ask < bid) {
                    logger.warn {
                        "[$symbol] No live greeks and no two-sided quote for strike $strike $expiry " +
                            "(bid=${snapshot.bid} ask=${snapshot.ask}, via $requestDesc) — skipping"
                    }
                    return@runCatching null
                }
                logger.info {
                    "[$symbol] No live greeks for strike $strike $expiry (bid=$bid ask=$ask, via $requestDesc) — " +
                        "kept for estimation / long leg"
                }
            }
            OptionQuote(
                contract = contract,
                bid = Money(BigDecimal(bid).setScale(4, RoundingMode.HALF_UP)),
                ask = Money(BigDecimal(ask).setScale(4, RoundingMode.HALF_UP)),
                mid = Money(midPrice(bid, ask)),
                greeks =
                    if (snapshot.delta.isNaN()) {
                        OptionGreeks(delta = Double.NaN, gamma = Double.NaN, theta = Double.NaN, vega = Double.NaN, iv = Double.NaN)
                    } else {
                        OptionGreeks(
                            delta = snapshot.delta,
                            gamma = snapshot.gamma.takeIf { !it.isNaN() } ?: 0.0,
                            theta = snapshot.theta.takeIf { !it.isNaN() } ?: 0.0,
                            vega = snapshot.vega.takeIf { !it.isNaN() } ?: 0.0,
                            iv = snapshot.impliedVol.takeIf { !it.isNaN() } ?: 0.0,
                        )
                    },
            )
        }.getOrElse { e ->
            logger.warn { "[$symbol] Failed to get greeks for strike $strike: ${e.message}" }
            null
        }
}
