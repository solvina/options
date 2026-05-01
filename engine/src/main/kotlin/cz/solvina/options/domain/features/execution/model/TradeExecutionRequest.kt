package cz.solvina.options.domain.features.execution.model

import cz.solvina.options.domain.models.OptionContract
import cz.solvina.options.domain.models.Symbol
import java.math.BigDecimal

data class TradeExecutionRequest(
    val soldContract: OptionContract,
    val boughtContract: OptionContract,
    val underlyingSymbol: Symbol,
    /** Initial net credit limit price — scanner's (soldMid − boughtMid). */
    val targetCredit: BigDecimal,
    /** Never submit a combo order below this net credit. */
    val floorCredit: BigDecimal,
    /** maxRiskPerShare for spread persistence and capital accounting. */
    val maxRiskPerShare: BigDecimal,
    /** IV rank at scan time — persisted with the spread record. */
    val ivRankAtEntry: Double,
    val soldBid: BigDecimal,
    val soldAsk: BigDecimal,
    val boughtBid: BigDecimal,
    val boughtAsk: BigDecimal,
    /** Bought-leg mid at scan time — used as approximate bought-leg premium in the spread record. */
    val boughtMid: BigDecimal,
    /** Underlying spot price at scan time — used as drift anchor during execution. */
    val underlyingPriceAtEntry: BigDecimal,
    val quantity: Int = 1,
)
