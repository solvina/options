package cz.solvina.options.domain.features.aireview

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * What a reviewer sees: the frozen market context of a candidate at the moment it first qualified.
 *
 * This is part of the engine ↔ AI contract, so it is its own set of plain types — never a
 * serialized domain object, which a refactor would silently change. Rules:
 *  - within version 1, fields are only ever added;
 *  - no Kotlin value classes (Symbol is a plain String here);
 *  - no NaN / infinity — estimated greeks can carry NaN, which is invalid JSON and rejected by
 *    Postgres JSONB; every Double goes through [finiteOrNull].
 *
 * Sections are null when the engine had no data. Planned additions (D1/D2/D2b): `iv` 1y history,
 * `priceStats`, `dailyBarsCsv`, `news`.
 */
data class ReviewSnapshotV1(
    val version: Int = VERSION,
    /** When the candidate was evaluated. */
    val asOf: Instant,
    /** New York trading date. */
    val tradingDate: LocalDate,
    val strategyId: String,
    val symbol: String,
    val session: String,
    val candidate: CandidateSection,
    val trend: TrendSection?,
    val iv: IvSection?,
    val calendar: CalendarSection?,
    /** Account size and open positions; removed for reviewers without include-portfolio. */
    val portfolio: PortfolioSection?,
    val trackRecord: TrackRecordSection?,
    /** Hash of the spread-rule configuration in force (see ai_review_rules). */
    val rulesVersion: String?,
    /** Broker news feeds (free providers), newest first; null when unavailable. */
    val news: NewsSection? = null,
) {
    companion object {
        const val VERSION = 1
    }
}

data class NewsSection(
    /** Provider codes asked. */
    val providers: List<String>,
    val since: Instant,
    val headlines: List<NewsItem>,
    val note: String,
)

data class NewsItem(
    val time: Instant,
    val provider: String,
    val headline: String,
    /** Plain-text body (shortened) for the newest few; null for the rest. */
    val body: String? = null,
)

data class CandidateSection(
    val expiry: LocalDate,
    val dte: Int,
    val underlyingPrice: BigDecimal,
    val shortLeg: LegSection,
    val longLeg: LegSection,
    val midCredit: BigDecimal,
    /** Achievable credit (BAG bid where quoted, else the leg natural cross). */
    val bidCredit: BigDecimal,
    val minCreditPerShare: BigDecimal,
    val targetWidth: BigDecimal,
    val actualWidth: BigDecimal,
    val creditPctOfWidth: Double?,
    val maxRiskPerShare: BigDecimal,
    val breakEven: BigDecimal,
    val maxProfitPerContract: BigDecimal,
    val maxLossPerContract: BigDecimal,
    val quantity: Int,
    /** IV rank the candidate qualified with (also in [IvSection.rank], which is best-effort). */
    val ivRank: Double? = null,
    /** Account capital the sizing used. */
    val totalCapital: BigDecimal? = null,
    /** Selection warnings (falling / rising / IV rank extreme); a flagged title is never entered. */
    val warnings: List<String>,
    val filters: FilterSection,
    val exits: ExitSection,
)

data class LegSection(
    val strike: BigDecimal,
    /** PUT or CALL. */
    val right: String,
    val bid: BigDecimal,
    val ask: BigDecimal,
    val mid: BigDecimal,
    val delta: Double?,
    val gamma: Double?,
    val theta: Double?,
    val vega: Double?,
    /** This option's own implied volatility (not the 30-day IV index in [IvSection]). */
    val iv: Double?,
    /** IBKR sent no greeks for this strike; they were estimated from neighbouring strikes. */
    val greeksEstimated: Boolean,
)

data class FilterSection(
    val ivRankThreshold: Double,
    val minDte: Int,
    val maxDte: Int,
    val preferredDte: Int,
    val targetDelta: Double,
    val deltaMin: Double,
    val deltaMax: Double,
    val maxRiskPercent: Double,
)

data class ExitSection(
    val takeProfitPercent: Double,
    val stopLossPercent: Double,
    val underlyingStopEnabled: Boolean,
    val underlyingStopBufferPct: Double,
    val underlyingStopTrigger: BigDecimal?,
    val timeProfitDte: Int,
    val driftProtectionPct: Double,
)

data class TrendSection(
    /** UPTREND / DOWNTREND / NEUTRAL from SMA fast vs slow. */
    val regime: String,
    /** BULLISH / BEARISH / NEUTRAL (trend + RSI). */
    val bias: String,
    /** Previous session close used by the regime (refreshed pre-market). */
    val lastClose: BigDecimal?,
    val sma50: BigDecimal?,
    val sma200: BigDecimal?,
    val rsi14: BigDecimal?,
    val change10dPct: Double?,
)

/**
 * The underlying's implied-volatility context. All values here are IBKR's 30-day IV *index*
 * (OPTION_IMPLIED_VOLATILITY), not the IV of the candidate's expiry; each leg's own IV is in
 * [LegSection.iv].
 */
data class IvSection(
    val rank: Double?,
    val currentIvIndex: Double?,
    val calculatedAt: Instant?,
    val historyMin: Double? = null,
    val historyMax: Double? = null,
    /** Share (0–100) of history days with an IV index below today's. */
    val percentile: Double? = null,
    val historyDays: Int? = null,
    /** Last 30 daily IV index values, oldest first. */
    val recent30: List<Double>? = null,
    val note: String = "IV index = IBKR 30-day implied volatility of the underlying, not this expiry; legs carry their own IV",
)

data class CalendarSection(
    /** Next report on or after the trading date; null when unknown. */
    val nextEarningsDate: LocalDate?,
    /** True when [nextEarningsDate] falls on or before the candidate's expiry; null when unknown. */
    val earningsBeforeExpiry: Boolean?,
    /**
     * The stored date when it is already past: the universe keeps the last report until Nasdaq
     * publishes the next one (MPC on 2026-11-18 still showed its Nov 3 report).
     */
    val lastEarningsDate: LocalDate? = null,
    val exDividendDate: LocalDate?,
    val nextDividendAmount: BigDecimal?,
)

data class PortfolioSection(
    val totalCapital: BigDecimal,
    val maxRiskPercent: Double,
    val activeSpreadCount: Long,
    val maxOpenSpreads: Int,
    val candidateSector: String?,
    val sectorActiveCount: Int,
    val maxOpenSpreadsPerSector: Int,
    val activeSymbols: List<ActiveSymbol>,
)

data class ActiveSymbol(
    val symbol: String,
    val sector: String?,
)

data class TrackRecordSection(
    /** Most recent would-be trades on this symbol, newest first. */
    val wouldBeTrades: List<WouldBeTrade>,
)

data class WouldBeTrade(
    val openedAt: Instant,
    val strategyId: String,
    val expiry: LocalDate,
    val soldStrike: BigDecimal,
    val boughtStrike: BigDecimal,
    val midCredit: BigDecimal,
    val status: String,
    val closeReason: String?,
    /** Realized (closed) or current (open) P&L per contract at mid, USD. */
    val pnlUsd: BigDecimal?,
)

internal fun Double.finiteOrNull(): Double? = takeIf { it.isFinite() }
