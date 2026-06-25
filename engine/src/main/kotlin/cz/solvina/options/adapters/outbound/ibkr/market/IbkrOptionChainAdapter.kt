package cz.solvina.options.adapters.outbound.ibkr.market

import com.ib.client.EClientSocket
import cz.solvina.options.adapters.outbound.ibkr.IbkrContractFactory
import cz.solvina.options.adapters.outbound.ibkr.cache.IbkrContractCache
import cz.solvina.options.adapters.outbound.ibkr.cache.IbkrOptionParamsCache
import cz.solvina.options.adapters.outbound.ibkr.cache.OptionContractKey
import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrMarketDataRegistry
import cz.solvina.options.domain.features.market.BlackScholes
import cz.solvina.options.domain.features.market.OptionChainPort
import cz.solvina.options.domain.features.market.model.OptionQuote
import cz.solvina.options.domain.features.scanner.ScannerConfig
import cz.solvina.options.domain.features.volatility.VolatilityPort
import cz.solvina.options.domain.models.Money
import cz.solvina.options.domain.models.OptionContract
import cz.solvina.options.domain.models.OptionGreeks
import cz.solvina.options.domain.models.OptionType
import cz.solvina.options.domain.models.Symbol
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

private val logger = KotlinLogging.logger {}

private const val RISK_FREE_RATE = 0.05

@Component
class IbkrOptionChainAdapter(
    private val registry: IbkrMarketDataRegistry,
    private val client: EClientSocket,
    private val scannerConfig: ScannerConfig,
    private val optionParamsCache: IbkrOptionParamsCache,
    private val contractCache: IbkrContractCache,
    private val volatilityPort: VolatilityPort,
    private val contractFactory: IbkrContractFactory,
) : OptionChainPort {
    override suspend fun getAvailableExpirations(symbol: Symbol): Set<LocalDate> =
        optionParamsCache
            .getOrFetch(symbol)
            .expirations

    override suspend fun getOptionChain(
        symbol: Symbol,
        expiry: LocalDate,
        underlyingPrice: Money,
    ): List<OptionQuote> {
        val params = optionParamsCache.getOrFetch(symbol)

        val lowerBound = underlyingPrice.amount.multiply(BigDecimal.ONE.subtract(BigDecimal(scannerConfig.strikeBandPercent)))
        val upperBound = underlyingPrice.amount.multiply(BigDecimal("0.999"))
        // Allow bought-leg candidates to sit one spread-width below the sold-leg band
        val boughtLegFloor = lowerBound.subtract(scannerConfig.spreadWidthUsd)

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
            contractCache.getVerifiedStrikes(symbol, expiry, OptionType.PUT)
                ?: contractCache.getOrFetchVerifiedStrikes(symbol, expiry, OptionType.PUT)
                ?: run {
                    logger.warn { "[$symbol] Verified strikes unavailable for $expiry — skipping to avoid phantom strikes" }
                    return emptyList()
                }
        val validStrikes =
            expiryStrikes.filter { strike ->
                strike >= boughtLegFloor && strike <= upperBound
            }

        if (validStrikes.isEmpty()) {
            logger.warn { "[$symbol] No valid OTM put strikes found in [$boughtLegFloor, $upperBound]" }
            return emptyList()
        }

        val targetStrike = underlyingPrice.amount.multiply(BigDecimal.ONE.subtract(BigDecimal(scannerConfig.targetDelta)))

        // Strikes near the moneyness-based target (high-delta region)
        val nearTarget =
            validStrikes
                .sortedBy { abs((it - targetStrike).toDouble()) }
                .take(scannerConfig.candidateStrikeCount)

        // Strikes from the lower end of the valid band (covers high-IV underlyings where the
        // actual delta-matching strikes sit well below the moneyness-based targetStrike)
        val lowerBand =
            validStrikes
                .sortedDescending()
                .takeLast(scannerConfig.candidateStrikeCount)

        val nearest = (nearTarget + lowerBand).distinct()

        // Bought-leg candidates: one spread-width below each sold candidate
        val boughtLegs =
            nearest
                .flatMap { soldStrike ->
                    val boughtTarget = soldStrike.subtract(scannerConfig.spreadWidthUsd)
                    validStrikes.filter { it <= boughtTarget }.sortedDescending().take(2)
                }.toSet()

        val allCandidates = (nearest + boughtLegs).distinct().sortedDescending()
        val candidateStrikes =
            allCandidates.filter { strike ->
                !contractCache.isMissing(OptionContractKey(symbol, expiry, strike, OptionType.PUT))
            }
        val filteredCount = allCandidates.size - candidateStrikes.size
        if (filteredCount > 0) {
            logger.debug { "[$symbol] Filtered $filteredCount known-missing strike(s) from candidate list" }
        }

        logger.debug {
            "[$symbol] Fetching greeks for ${candidateStrikes.size} candidate strikes (${nearest.size} sold + ${boughtLegs.size} bought-leg)"
        }

        val tte = ChronoUnit.DAYS.between(LocalDate.now(), expiry) / 365.0
        val spot = underlyingPrice.amount.toDouble()

        return candidateStrikes.mapNotNull { strike ->
            runCatching {
                val contract = OptionContract(symbol, expiry, strike, OptionType.PUT)
                val snapshot =
                    reqMktDataSnapshot(
                        registry,
                        client,
                        contractFactory.optionContract(contract, params.exchange, params.tradingClass, params.multiplier),
                        "",
                        SnapshotReady.OPTION_QUOTE,
                    )
                val bid = snapshot.bid.takeIf { !it.isNaN() } ?: 0.0
                val ask = snapshot.ask.takeIf { !it.isNaN() } ?: 0.0
                val mid = midPrice(bid, ask)

                if (!snapshot.delta.isNaN()) {
                    return@runCatching OptionQuote(
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
                        synthetic = false,
                    )
                }

                // No live Greeks — fall back to Black-Scholes using historical IV
                val sigma = volatilityPort.getIvRank(symbol).currentIv
                if (sigma <= 0.0 || tte <= 0.0) {
                    logger.debug { "[$symbol] Strike $strike: no Greeks and cannot compute analytically (sigma=$sigma, tte=$tte)" }
                    return@runCatching null
                }
                val strikeDouble = strike.toDouble()
                val bsMid = BlackScholes.putPrice(spot, strikeDouble, tte, RISK_FREE_RATE, sigma)
                val halfSpread = maxOf(bsMid * 0.05, 0.05)
                logger.debug {
                    "[$symbol] Strike $strike: using BS fallback (sigma=${"%.4f".format(
                        sigma,
                    )}, delta=${"%.4f".format(BlackScholes.putDelta(spot, strikeDouble, tte, RISK_FREE_RATE, sigma))})"
                }
                OptionQuote(
                    contract = contract,
                    bid = Money(BigDecimal(maxOf(bsMid - halfSpread, 0.01)).setScale(4, RoundingMode.HALF_UP)),
                    ask = Money(BigDecimal(bsMid + halfSpread).setScale(4, RoundingMode.HALF_UP)),
                    mid = Money(BigDecimal(bsMid).setScale(4, RoundingMode.HALF_UP)),
                    greeks =
                        OptionGreeks(
                            delta = BlackScholes.putDelta(spot, strikeDouble, tte, RISK_FREE_RATE, sigma),
                            gamma = BlackScholes.gamma(spot, strikeDouble, tte, RISK_FREE_RATE, sigma),
                            theta = BlackScholes.putTheta(spot, strikeDouble, tte, RISK_FREE_RATE, sigma),
                            vega = BlackScholes.vega(spot, strikeDouble, tte, RISK_FREE_RATE, sigma) / 100.0,
                            iv = sigma,
                        ),
                    // BS-fallback: no live market for this strike. Usable for theoretical selection,
                    // but the selector must NOT launch an order on it (would never get a tick).
                    synthetic = true,
                )
            }.getOrElse { e ->
                logger.warn { "[$symbol] Failed to get greeks for strike $strike: ${e.message}" }
                null
            }
        }
    }
}
