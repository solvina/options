package cz.solvina.options.domain.features.flag.config

import cz.solvina.options.domain.features.strategy.ParamDescriptor
import cz.solvina.options.domain.features.strategy.ParamType
import cz.solvina.options.domain.features.strategy.StrategyParams
import cz.solvina.options.domain.features.strategy.TunableStrategy
import org.springframework.stereotype.Component

/** Persisted id for the flag strategy's tuning rows. Never rename in place — see [TunableStrategy]. */
const val FLAG_STRATEGY_ID = "bull_flag"

/**
 * The flag strategy's tunable surface.
 *
 * These defaults are the **absolute** defaults: what the Reset button restores and what a symbol
 * runs when no row exists in `strategy_default_params` or `strategy_symbol_params`. They are the
 * values that were live in `application.yml` up to 2026-07-29, not the placeholder values that used
 * to sit on the [FlagStrategyConfig] data class — those disabled most of the entry filters
 * (`skipFirstRthMinutes = 0`, `minFlagpoleAtrMultiple = 0.0`) and were never the intended defaults.
 *
 * The yaml block is gone. Declaring a parameter here is now the only way to have one, which is what
 * makes the UI form, the sweep grid and server-side validation impossible to forget.
 */
@Component
class FlagStrategyDescriptors : TunableStrategy {
    override val id = FLAG_STRATEGY_ID

    override val displayName = "Bull Flag"

    override val params =
        listOf(
            // ---- Pattern detection ----
            ParamDescriptor(
                name = "atrPeriod",
                type = ParamType.INT,
                default = 14,
                min = 2.0,
                max = 200.0,
                group = "Pattern",
                help = "Bars used for the ATR that scales the flagpole height test.",
            ),
            ParamDescriptor(
                name = "atrMultiplier",
                type = ParamType.DOUBLE,
                default = 2.0,
                min = 0.1,
                max = 20.0,
                group = "Pattern",
                help = "A flagpole must rise at least this many ATRs to count.",
            ),
            ParamDescriptor(
                name = "volumeMaPeriod",
                type = ParamType.INT,
                default = 20,
                min = 2.0,
                max = 200.0,
                group = "Pattern",
                help = "Bars in the volume moving average used for the spike test.",
            ),
            ParamDescriptor(
                name = "volumeSpikeMultiplier",
                type = ParamType.DOUBLE,
                default = 1.5,
                min = 0.1,
                max = 20.0,
                group = "Pattern",
                help = "A pole bar counts as a volume spike above this multiple of the volume MA.",
            ),
            ParamDescriptor(
                name = "poleMinBars",
                type = ParamType.INT,
                default = 5,
                min = 1.0,
                max = 100.0,
                group = "Pattern",
                help = "Shortest run of bars that can form a flagpole.",
            ),
            ParamDescriptor(
                name = "poleMaxBars",
                type = ParamType.INT,
                default = 10,
                min = 1.0,
                max = 200.0,
                group = "Pattern",
                help = "Longest window searched for a flagpole.",
            ),
            ParamDescriptor(
                name = "flagMinBars",
                type = ParamType.INT,
                default = 5,
                min = 1.0,
                max = 100.0,
                group = "Pattern",
                help = "Consolidation must last this many bars before it is a flag.",
            ),
            ParamDescriptor(
                name = "flagMaxBars",
                type = ParamType.INT,
                default = 20,
                min = 1.0,
                max = 200.0,
                group = "Pattern",
                help = "Flag expires after this many bars and the pattern resets.",
            ),
            ParamDescriptor(
                name = "maxRetracementPct",
                type = ParamType.DOUBLE,
                default = 0.50,
                min = 0.0,
                max = 1.0,
                group = "Pattern",
                help = "Retracing more than this fraction of the pole invalidates the flag.",
            ),
            // ---- Entry filters ----
            ParamDescriptor(
                name = "skipFirstRthMinutes",
                type = ParamType.INT,
                default = 90,
                min = 0.0,
                max = 390.0,
                group = "Entry",
                help = "Calm period: take no entries for this many minutes after the session open.",
            ),
            ParamDescriptor(
                name = "requireNegativeChannelSlope",
                type = ParamType.BOOLEAN,
                default = false,
                group = "Entry",
                help = "Require a downward-sloping flag channel. Off since many real bull flags drift flat or up.",
            ),
            ParamDescriptor(
                name = "minFlagpoleAtrMultiple",
                type = ParamType.DOUBLE,
                default = 1.5,
                min = 0.0,
                max = 50.0,
                group = "Entry",
                help = "Reject entries whose pole is smaller than this multiple of ATR.",
            ),
            ParamDescriptor(
                name = "maxFlagpoleAtrMultiple",
                type = ParamType.DOUBLE,
                default = 5.0,
                min = 0.0,
                max = 99.0,
                group = "Entry",
                help = "Reject over-extended moves above this multiple of ATR.",
            ),
            ParamDescriptor(
                name = "minFlagRetracementPct",
                type = ParamType.DOUBLE,
                default = 0.15,
                min = 0.0,
                max = 1.0,
                group = "Entry",
                help = "A flag that has barely pulled back is not a flag; require at least this retracement.",
            ),
            ParamDescriptor(
                name = "minFlagBarsForEntry",
                type = ParamType.INT,
                default = 5,
                min = 1.0,
                max = 100.0,
                group = "Entry",
                help = "Minimum flag bars before a breakout may be traded.",
            ),
            // ---- Data ----
            ParamDescriptor(
                name = "historicalBootstrapDays",
                type = ParamType.INT,
                default = 3,
                min = 1.0,
                max = 30.0,
                group = "Data",
                help = "Days of 5-minute history replayed into the detector when a symbol is subscribed.",
            ),
        )
}

/**
 * Projects resolved [StrategyParams] onto the typed config the detector and scanner already consume.
 *
 * Keeping [FlagStrategyConfig] as a typed data class rather than passing the untyped params around
 * means the ~30 call sites in `PatternDetector`, `FlagScannerService` and `FlagBacktestStrategy` are
 * unchanged, and a mistyped parameter name fails here, once, instead of at some later branch that
 * only a rare market condition reaches.
 */
fun FlagStrategyConfig.Companion.from(params: StrategyParams): FlagStrategyConfig =
    FlagStrategyConfig(
        atrPeriod = params.int("atrPeriod"),
        atrMultiplier = params.double("atrMultiplier"),
        volumeMaPeriod = params.int("volumeMaPeriod"),
        volumeSpikeMultiplier = params.double("volumeSpikeMultiplier"),
        poleMinBars = params.int("poleMinBars"),
        poleMaxBars = params.int("poleMaxBars"),
        flagMinBars = params.int("flagMinBars"),
        flagMaxBars = params.int("flagMaxBars"),
        maxRetracementPct = params.double("maxRetracementPct"),
        historicalBootstrapDays = params.int("historicalBootstrapDays"),
        skipFirstRthMinutes = params.int("skipFirstRthMinutes"),
        requireNegativeChannelSlope = params.boolean("requireNegativeChannelSlope"),
        minFlagpoleAtrMultiple = params.double("minFlagpoleAtrMultiple"),
        maxFlagpoleAtrMultiple = params.double("maxFlagpoleAtrMultiple"),
        minFlagRetracementPct = params.double("minFlagRetracementPct"),
        minFlagBarsForEntry = params.int("minFlagBarsForEntry"),
    )

/**
 * The inverse of [from]: a typed config expressed as a params blob.
 *
 * Used where a caller already holds a [FlagStrategyConfig] and needs to hand it to the tuning layer
 * — seeding a saved baseline, and building resolver stubs in tests without restating every key.
 */
fun FlagStrategyConfig.toParams(): Map<String, Any?> =
    mapOf(
        "atrPeriod" to atrPeriod,
        "atrMultiplier" to atrMultiplier,
        "volumeMaPeriod" to volumeMaPeriod,
        "volumeSpikeMultiplier" to volumeSpikeMultiplier,
        "poleMinBars" to poleMinBars,
        "poleMaxBars" to poleMaxBars,
        "flagMinBars" to flagMinBars,
        "flagMaxBars" to flagMaxBars,
        "maxRetracementPct" to maxRetracementPct,
        "historicalBootstrapDays" to historicalBootstrapDays,
        "skipFirstRthMinutes" to skipFirstRthMinutes,
        "requireNegativeChannelSlope" to requireNegativeChannelSlope,
        "minFlagpoleAtrMultiple" to minFlagpoleAtrMultiple,
        "maxFlagpoleAtrMultiple" to maxFlagpoleAtrMultiple,
        "minFlagRetracementPct" to minFlagRetracementPct,
        "minFlagBarsForEntry" to minFlagBarsForEntry,
    )
