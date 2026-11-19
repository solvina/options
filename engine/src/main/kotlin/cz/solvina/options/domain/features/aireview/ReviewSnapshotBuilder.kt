package cz.solvina.options.domain.features.aireview

import cz.solvina.options.domain.features.market.model.OptionQuote
import cz.solvina.options.domain.features.news.NewsConfig
import cz.solvina.options.domain.features.news.NewsPort
import cz.solvina.options.domain.features.notification.SpreadOpportunity
import cz.solvina.options.domain.features.regime.TrendRegimeService
import cz.solvina.options.domain.features.scanner.BearCallScannerConfig
import cz.solvina.options.domain.features.scanner.BullPutScannerConfig
import cz.solvina.options.domain.features.scanner.ScannerConfig
import cz.solvina.options.domain.features.scanner.ScannerPoolConfig
import cz.solvina.options.domain.features.shadow.ShadowSpreadPort
import cz.solvina.options.domain.features.spread.SpreadQueryFacade
import cz.solvina.options.domain.features.universe.InstrumentConfig
import cz.solvina.options.domain.features.universe.UniversePort
import cz.solvina.options.domain.features.volatility.IvRankStorePort
import cz.solvina.options.domain.features.volatility.VolatilityPort
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.withTimeout
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/**
 * Builds the frozen [ReviewSnapshotV1] for a candidate from what the engine already holds — no
 * IBKR request is made here. Every section except the candidate itself is best-effort: a failing
 * source leaves its section null instead of failing the review.
 */
@Component
class ReviewSnapshotBuilder(
    private val regimeService: TrendRegimeService,
    private val volatilityPort: VolatilityPort,
    private val ivStore: IvRankStorePort,
    private val universePort: UniversePort,
    private val spreadQuery: SpreadQueryFacade,
    private val shadowSpreads: ShadowSpreadPort,
    private val store: AiReviewStorePort,
    private val scannerConfig: ScannerConfig,
    private val bullPutConfig: BullPutScannerConfig,
    private val bearCallConfig: BearCallScannerConfig,
    private val poolConfig: ScannerPoolConfig,
    private val newsConfig: NewsConfig = NewsConfig(enabled = false),
    private val newsPort: NewsPort? = null,
) {
    suspend fun build(o: SpreadOpportunity): ReviewSnapshotV1 {
        val instrument = section("instrument", o) { universePort.get(o.symbol) }
        return ReviewSnapshotV1(
            asOf = o.detectedAt,
            tradingDate = o.detectedAt.atZone(NEW_YORK).toLocalDate(),
            strategyId = o.strategyId.name,
            symbol = o.symbol.value,
            session = o.session,
            candidate = candidate(o),
            trend = section("trend", o) { trend(o) },
            iv = section("iv", o) { iv(o) },
            calendar = calendar(o, instrument),
            portfolio = section("portfolio", o) { portfolio(o) },
            trackRecord = section("trackRecord", o) { trackRecord(o) },
            rulesVersion = section("rules", o) { store.registerRules(rules(instrument), o.detectedAt) },
            news = section("news", o) { news(o) },
        )
    }

    private suspend fun news(o: SpreadOpportunity): NewsSection? {
        val port = newsPort?.takeIf { newsConfig.enabled } ?: return null
        val since = o.detectedAt.minus(Duration.ofDays(newsConfig.lookbackDays))
        return withTimeout(newsConfig.timeoutSeconds.seconds) {
            val found = port.headlines(o.symbol, since, newsConfig.maxHeadlines)
            val items =
                found.mapIndexed { i, h ->
                    val body =
                        if (i < newsConfig.maxArticles) {
                            runCatching { port.article(h.provider, h.articleId) }
                                .getOrNull()
                                ?.let { if (it.length > newsConfig.maxArticleChars) it.take(newsConfig.maxArticleChars) + "…" else it }
                        } else {
                            null
                        }
                    NewsItem(h.time, h.provider, h.headline, body)
                }
            NewsSection(
                providers = found.map { it.provider }.distinct().sorted(),
                since = since,
                headlines = items,
                note =
                    "Headlines from the broker's free feeds since ${since.atZone(NEW_YORK).toLocalDate()}, newest first; " +
                        "bodies for the newest ${newsConfig.maxArticles}. Empty means no headline, not no news: search the web for context.",
            )
        }
    }

    private suspend fun <T> section(
        name: String,
        o: SpreadOpportunity,
        block: suspend () -> T?,
    ): T? =
        runCatching { block() }
            .onFailure { logger.warn { "[${o.symbol}] AI review snapshot: $name section unavailable: ${it.message}" } }
            .getOrNull()

    private fun candidate(o: SpreadOpportunity) =
        CandidateSection(
            expiry = o.expiry,
            dte = o.dte,
            underlyingPrice = o.underlyingPrice,
            shortLeg = leg(o.shortLeg),
            longLeg = leg(o.longLeg),
            midCredit = o.midCredit,
            bidCredit = o.bidCredit,
            minCreditPerShare = o.minCreditPerShare,
            targetWidth = o.targetWidth,
            actualWidth = o.actualWidth,
            creditPctOfWidth =
                o.actualWidth
                    .takeIf { it.signum() > 0 }
                    ?.let { o.midCredit.divide(it, 4, RoundingMode.HALF_UP).toDouble() },
            maxRiskPerShare = o.maxRiskPerShare,
            breakEven = o.breakEven,
            maxProfitPerContract = o.maxProfitPerContract,
            maxLossPerContract = o.maxLossPerContract,
            quantity = o.quantity,
            ivRank = o.ivRank.finiteOrNull(),
            totalCapital = o.totalCapital,
            warnings = o.warnings,
            filters =
                FilterSection(
                    ivRankThreshold = o.ivRankThreshold,
                    minDte = o.minDte,
                    maxDte = o.maxDte,
                    preferredDte = o.preferredDte,
                    targetDelta = o.targetDelta,
                    deltaMin = o.deltaMin,
                    deltaMax = o.deltaMax,
                    maxRiskPercent = o.maxRiskPercent,
                ),
            exits =
                ExitSection(
                    takeProfitPercent = o.takeProfitPercent,
                    stopLossPercent = o.stopLossPercent,
                    underlyingStopEnabled = o.underlyingStopEnabled,
                    underlyingStopBufferPct = o.underlyingStopBufferPct,
                    underlyingStopTrigger = o.underlyingStopTrigger,
                    timeProfitDte = o.timeProfitDte,
                    driftProtectionPct = o.driftProtectionPct,
                ),
        )

    private fun leg(q: OptionQuote) =
        LegSection(
            strike = q.contract.strike,
            right = q.contract.type.name,
            bid = q.bid.amount,
            ask = q.ask.amount,
            mid = q.mid.amount,
            delta = q.greeks.delta.finiteOrNull(),
            gamma = q.greeks.gamma.finiteOrNull(),
            theta = q.greeks.theta.finiteOrNull(),
            vega = q.greeks.vega.finiteOrNull(),
            iv = q.greeks.iv.finiteOrNull(),
            greeksEstimated = q.greeksEstimated,
        )

    private fun trend(o: SpreadOpportunity): TrendSection {
        val s = regimeService.regimeFor(o.symbol)
        return TrendSection(
            regime = s.regime.name,
            bias = s.bias.name,
            lastClose = s.lastClose,
            sma50 = s.smaFast,
            sma200 = s.smaSlow,
            rsi14 = s.rsi,
            change10dPct = s.change10dPct?.finiteOrNull(),
        )
    }

    // A cache read in practice: the selector computed this IV rank moments ago.
    private suspend fun iv(o: SpreadOpportunity): IvSection {
        val r = volatilityPort.getIvRank(o.symbol)
        val summary = runCatching { ivStore.find(o.symbol)?.summary }.getOrNull()
        return IvSection(
            rank = r.rank.finiteOrNull(),
            currentIvIndex = r.currentIv.finiteOrNull(),
            calculatedAt = r.calculatedAt,
            historyMin = summary?.min?.finiteOrNull(),
            historyMax = summary?.max?.finiteOrNull(),
            percentile = summary?.percentile?.finiteOrNull(),
            historyDays = summary?.sampleDays,
            recent30 = summary?.recent?.mapNotNull { it.finiteOrNull() },
        )
    }

    private fun calendar(
        o: SpreadOpportunity,
        instrument: InstrumentConfig?,
    ): CalendarSection? =
        instrument?.let {
            val tradingDate = o.detectedAt.atZone(NEW_YORK).toLocalDate()
            val next = it.nextEarningsDate?.takeUnless { e -> e.isBefore(tradingDate) }
            CalendarSection(
                nextEarningsDate = next,
                earningsBeforeExpiry = next?.let { e -> !e.isAfter(o.expiry) },
                lastEarningsDate = it.nextEarningsDate?.takeIf { e -> e.isBefore(tradingDate) },
                exDividendDate = it.exDividendDate,
                nextDividendAmount = it.nextDividendAmount,
            )
        }

    private suspend fun portfolio(o: SpreadOpportunity): PortfolioSection {
        val active = spreadQuery.symbolsWithActiveSpread().map { ActiveSymbol(it.value, universePort.sectorOf(it)) }
        val sector = universePort.sectorOf(o.symbol)
        return PortfolioSection(
            totalCapital = o.totalCapital,
            maxRiskPercent = o.maxRiskPercent,
            activeSpreadCount = spreadQuery.activeSpreadCount(),
            maxOpenSpreads = scannerConfig.maxOpenSpreads,
            candidateSector = sector,
            sectorActiveCount = if (sector == null) 0 else active.count { it.sector == sector },
            maxOpenSpreadsPerSector = scannerConfig.maxOpenSpreadsPerSector,
            activeSymbols = active.sortedBy { it.symbol },
        )
    }

    private suspend fun trackRecord(o: SpreadOpportunity) =
        TrackRecordSection(
            wouldBeTrades =
                shadowSpreads
                    .findAll()
                    .filter { it.symbol == o.symbol }
                    .sortedByDescending { it.openedAt }
                    .take(TRACK_RECORD_SIZE)
                    .map { s ->
                        WouldBeTrade(
                            openedAt = s.openedAt,
                            strategyId = s.strategyId.name,
                            expiry = s.expiry,
                            soldStrike = s.soldStrike,
                            boughtStrike = s.boughtStrike,
                            midCredit = s.midCredit,
                            status = s.status.name,
                            closeReason = s.closeReason,
                            pnlUsd =
                                (s.exitValue ?: s.lastValue)
                                    ?.let {
                                        s.midCredit
                                            .subtract(it)
                                            .multiply(HUNDRED)
                                            .setScale(2, RoundingMode.HALF_UP)
                                    },
                        )
                    },
        )

    /** The rule configuration a candidate was selected under, including this symbol's overrides. */
    private fun rules(instrument: InstrumentConfig?): Map<String, Any?> =
        mapOf(
            "scanner" to scannerConfig,
            "bullPut" to bullPutConfig,
            "bearCall" to bearCallConfig,
            "pool" to poolConfig,
            "instrumentOverrides" to
                instrument
                    ?.let {
                        mapOf(
                            "ivRankThreshold" to it.ivRankThreshold,
                            "minDte" to it.minDte,
                            "maxDte" to it.maxDte,
                            "preferredDte" to it.preferredDte,
                            "targetDelta" to it.targetDelta,
                            "deltaMin" to it.deltaMin,
                            "deltaMax" to it.deltaMax,
                            "spreadWidthUsd" to it.spreadWidthUsd,
                            "minCreditPerShare" to it.minCreditPerShare,
                            "maxRiskPercent" to it.maxRiskPercent,
                            "takeProfitPercent" to it.takeProfitPercent,
                            "stopLossPercent" to it.stopLossPercent,
                            "timeProfitDte" to it.timeProfitDte,
                        ).filterValues { v -> v != null }
                    }.orEmpty(),
        )

    private companion object {
        const val TRACK_RECORD_SIZE = 10
        val HUNDRED = BigDecimal(100)
    }
}
