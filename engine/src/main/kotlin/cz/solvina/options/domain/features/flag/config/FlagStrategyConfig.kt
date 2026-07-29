package cz.solvina.options.domain.features.flag.config

import cz.solvina.options.domain.features.strategy.StrategyParams

/**
 * Resolved flag strategy parameters for one symbol.
 *
 * No longer bound from `application.yml` and no longer a Spring bean: values now come from the
 * tuning layer (`descriptor defaults -> strategy_default_params -> strategy_symbol_params`) via
 * [cz.solvina.options.domain.features.strategy.tuning.StrategyParamsResolver], so two symbols can
 * run the same strategy with different parameters and both are editable at runtime.
 *
 * Deliberately has **no default values**. The defaults live in exactly one place — the
 * [ParamDescriptor][cz.solvina.options.domain.features.strategy.ParamDescriptor] list on
 * [FlagStrategyDescriptors] — and a second set here is how the previous arrangement drifted into
 * three disagreeing copies (this data class, `application.yml`, and the frontend's hardcoded
 * `DEFAULTS` object). Use [Companion.defaults] when you want them.
 *
 * The flag watchlist is DB-driven via `instrument_universe.flag_enabled` (see
 * [cz.solvina.options.domain.features.universe.UniversePort.getFlagWatchlist]).
 */
data class FlagStrategyConfig(
    val atrPeriod: Int,
    val atrMultiplier: Double,
    val volumeMaPeriod: Int,
    val volumeSpikeMultiplier: Double,
    val poleMinBars: Int,
    val poleMaxBars: Int,
    val flagMinBars: Int,
    val flagMaxBars: Int,
    val maxRetracementPct: Double,
    val historicalBootstrapDays: Int,
    /** Skip entries for this many minutes after session open (avoids opening-bell chop). */
    val skipFirstRthMinutes: Int,
    /** Require downward-sloping flag channel (rising wedge is not a bull flag). */
    val requireNegativeChannelSlope: Boolean,
    /** Minimum flagpole height as a multiple of ATR at entry. */
    val minFlagpoleAtrMultiple: Double,
    /** Maximum flagpole height as a multiple of ATR at entry (filters over-extended moves). */
    val maxFlagpoleAtrMultiple: Double,
    /** Minimum flag retracement as a fraction (e.g. 0.25 = 25%). */
    val minFlagRetracementPct: Double,
    /** Minimum number of flag bars before an entry is allowed. */
    val minFlagBarsForEntry: Int,
) {
    companion object {
        /** The absolute defaults, read from the descriptors so there is only ever one copy. */
        fun defaults(): FlagStrategyConfig = from(StrategyParams.resolve(FlagStrategyDescriptors().params))
    }
}
