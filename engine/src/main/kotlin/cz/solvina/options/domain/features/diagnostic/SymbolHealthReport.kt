package cz.solvina.options.domain.features.diagnostic

import cz.solvina.options.domain.models.Symbol
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

data class SymbolHealthReport(
    val symbol: Symbol,
    val probedAt: Instant,
    val contractResolution: ContractResolutionResult,
    val optionParams: OptionParamsResult,
    val historicalData: HistoricalDataResult,
    val spot: SpotResult,
    val optionSamples: List<OptionMidSample>,
    val tickStream: TickStreamResult?,
    val errors: List<String>,
) {
    data class ContractResolutionResult(
        val stockConId: Int?,
        val durationMs: Long,
        val error: String?,
    )

    data class OptionParamsResult(
        val strikeCount: Int,
        val availableExpiries: List<LocalDate>,
        val error: String?,
    )

    data class HistoricalDataResult(
        val barCount: Int,
        val ivPopulated: Boolean,
        val currentIv: Double?,
        val ivRank: Double?,
        val error: String?,
    )

    data class SpotResult(
        val price: BigDecimal?,
        val source: DataSource,
        val durationMs: Long,
        val error: String?,
    )

    data class OptionMidSample(
        val strike: BigDecimal,
        val expiry: LocalDate,
        val bid: Double,
        val ask: Double,
        val mid: BigDecimal,
        val delta: Double,
        val impliedVol: Double,
        val source: DataSource,
        val durationMs: Long,
    )

    data class TickStreamResult(
        val ticksReceived: Int,
        val lastBid: Double,
        val lastAsk: Double,
        val lastDelta: Double,
        val windowMs: Long,
        val error: String?,
    )
}
