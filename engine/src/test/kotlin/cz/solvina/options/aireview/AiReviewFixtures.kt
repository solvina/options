package cz.solvina.options.aireview

import cz.solvina.options.domain.features.aireview.CandidateSection
import cz.solvina.options.domain.features.aireview.ExitSection
import cz.solvina.options.domain.features.aireview.FilterSection
import cz.solvina.options.domain.features.aireview.LegSection
import cz.solvina.options.domain.features.aireview.ReviewSnapshotV1
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** A minimal valid bull put snapshot for AI review tests. */
fun testSnapshot(
    symbol: String = "NUE",
    asOf: Instant,
): ReviewSnapshotV1 {
    fun leg(strike: String) =
        LegSection(BigDecimal(strike), "PUT", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, -0.25, 0.01, -0.02, 0.1, 0.3, false)
    return ReviewSnapshotV1(
        asOf = asOf,
        tradingDate = asOf.atZone(ZoneId.of("America/New_York")).toLocalDate(),
        strategyId = "BULL_PUT",
        symbol = symbol,
        session = "US",
        candidate =
            CandidateSection(
                expiry = LocalDate.of(2026, 12, 18),
                dte = 29,
                underlyingPrice = BigDecimal("250"),
                shortLeg = leg("240"),
                longLeg = leg("230"),
                midCredit = BigDecimal("1.80"),
                bidCredit = BigDecimal("1.70"),
                minCreditPerShare = BigDecimal("0.90"),
                targetWidth = BigDecimal("5"),
                actualWidth = BigDecimal("10"),
                creditPctOfWidth = 0.18,
                maxRiskPerShare = BigDecimal("8.20"),
                breakEven = BigDecimal("238.20"),
                maxProfitPerContract = BigDecimal("180"),
                maxLossPerContract = BigDecimal("820"),
                quantity = 1,
                warnings = emptyList(),
                filters = FilterSection(45.0, 30, 45, 35, 0.25, 0.15, 0.35, 0.025),
                exits = ExitSection(0.5, 2.0, true, 0.0, BigDecimal("240"), 21, 0.0),
            ),
        trend = null,
        iv = null,
        calendar = null,
        portfolio = null,
        trackRecord = null,
        rulesVersion = "abc",
    )
}
