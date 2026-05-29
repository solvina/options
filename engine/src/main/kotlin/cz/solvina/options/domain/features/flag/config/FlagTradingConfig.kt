package cz.solvina.options.domain.features.flag.config

import java.math.BigDecimal

/** User-editable trading limits stored in the DB (single row, id=1). */
data class FlagTradingConfig(
    val id: Long = 1L,
    /** Maximum dollar loss per trade (used in share-size formula). Default $100. */
    val riskPerTrade: BigDecimal = BigDecimal("100.00"),
    /** Maximum number of open flag positions at any time. */
    val maxOpenPositions: Int = 3,
    /** Master on/off switch. When false, no new entries are opened. Persisted. */
    val enabled: Boolean = true,
    /** Block new entries this many minutes before exchange close (default 120 = 2 hours). */
    val entryBlockMinutesBeforeClose: Int = 120,
    /** Liquidate remaining open positions this many minutes before exchange close (default 15). */
    val eodLiqMinutesBeforeClose: Int = 15,
)
