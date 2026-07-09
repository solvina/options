package cz.solvina.options.domain.features.execution

import cz.solvina.options.domain.features.account.EffectiveAccountService
import cz.solvina.options.domain.features.execution.model.ExecutionOutcome
import cz.solvina.options.domain.features.execution.model.TradeExecutionRequest
import cz.solvina.options.domain.features.order.OrderExecutionPort
import cz.solvina.options.domain.features.scanner.ScannerConfig
import cz.solvina.options.domain.features.spread.SpreadQueryFacade
import cz.solvina.options.domain.models.Symbol
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode

private val logger = KotlinLogging.logger {}
private val tradeLogger = KotlinLogging.logger("TRADES")

@Component
class PreTradeValidator(
    private val spreadQuery: SpreadQueryFacade,
    private val orderExecutionPort: OrderExecutionPort,
    private val effectiveAccount: EffectiveAccountService,
    private val config: ScannerConfig,
) {
    suspend fun validate(
        request: TradeExecutionRequest,
        inFlightSymbols: Set<Symbol>,
    ): ExecutionOutcome? {
        // Exposure: open/closing spreads (any strategy) + in-flight (in-memory) + live IBKR open
        // orders (survives restart). Counts across strategies via the facade.
        val openSymbols = spreadQuery.symbolsWithOpenOrClosingSpread()

        // CLOSING freeze: prevent new entries on a symbol while the close is in-flight.
        // Rationale: during the 1-60s window while close orders are being submitted/filled,
        // any new entry would interfere with position reconciliation. Freeze is temporary.
        val closingSymbols = spreadQuery.symbolsWithClosingSpread()
        if (request.underlyingSymbol in closingSymbols) {
            logger.info { "[${request.underlyingSymbol}] CLOSING_FREEZE — symbol has in-flight close order, entry blocked" }
            tradeLogger.info {
                "REJECT ${request.underlyingSymbol}  ${request.soldContract.strike}${request.soldContract.type.ibkrCode}/${request.boughtContract.strike}${request.boughtContract.type.ibkrCode}  reason=CLOSING_FREEZE"
            }
            return ExecutionOutcome.EXPOSURE_REJECTED
        }
        val ibkrOpenSymbols =
            runCatching { orderExecutionPort.getSymbolsWithOpenOrders() }
                .onFailure { e -> logger.warn(e) { "[${request.underlyingSymbol}] Could not fetch open IBKR orders: ${e.message}" } }
                .getOrDefault(emptySet())
        if (request.underlyingSymbol in openSymbols ||
            request.underlyingSymbol in ibkrOpenSymbols ||
            request.underlyingSymbol in inFlightSymbols
        ) {
            logger.info { "[${request.underlyingSymbol}] EXPOSURE_REJECTED — open or in-flight position exists" }
            tradeLogger.info {
                "REJECT ${request.underlyingSymbol}  ${request.soldContract.strike}${request.soldContract.type.ibkrCode}/${request.boughtContract.strike}${request.boughtContract.type.ibkrCode}  reason=EXPOSURE_REJECTED"
            }
            return ExecutionOutcome.EXPOSURE_REJECTED
        }

        // Capital: available funds (capped at effective-account-size) vs total max risk for the qty
        val availableFunds =
            effectiveAccount
                .detail()
                ?.availableFunds
                ?.amount
        val maxRiskTotal =
            request.maxRiskPerShare
                .multiply(config.contractMultiplier)
                .multiply(BigDecimal(request.quantity))
        if (availableFunds == null || availableFunds < maxRiskTotal) {
            logger.info {
                "[${request.underlyingSymbol}] CAPITAL_REJECTED — available=\$$availableFunds " +
                    "required=\$$maxRiskTotal"
            }
            tradeLogger.info {
                "REJECT ${request.underlyingSymbol}  ${request.soldContract.strike}${request.soldContract.type.ibkrCode}/${request.boughtContract.strike}${request.boughtContract.type.ibkrCode}  reason=CAPITAL_REJECTED  available=\$$availableFunds  required=\$$maxRiskTotal"
            }
            return ExecutionOutcome.CAPITAL_REJECTED
        }

        // Liquidity: leg bid-ask spreads
        if (isLiquidityTooWide(request.soldBid, request.soldAsk, "sold")) {
            logger.info { "[${request.underlyingSymbol}] LIQUIDITY_REJECTED — sold leg spread too wide" }
            tradeLogger.info {
                "REJECT ${request.underlyingSymbol}  ${request.soldContract.strike}${request.soldContract.type.ibkrCode}/${request.boughtContract.strike}${request.boughtContract.type.ibkrCode}  reason=LIQUIDITY_REJECTED  leg=sold"
            }
            return ExecutionOutcome.LIQUIDITY_REJECTED
        }
        if (isLiquidityTooWide(request.boughtBid, request.boughtAsk, "bought")) {
            logger.info { "[${request.underlyingSymbol}] LIQUIDITY_REJECTED — bought leg spread too wide" }
            tradeLogger.info {
                "REJECT ${request.underlyingSymbol}  ${request.soldContract.strike}${request.soldContract.type.ibkrCode}/${request.boughtContract.strike}${request.boughtContract.type.ibkrCode}  reason=LIQUIDITY_REJECTED  leg=bought"
            }
            return ExecutionOutcome.LIQUIDITY_REJECTED
        }

        return null
    }

    private fun isLiquidityTooWide(
        bid: BigDecimal,
        ask: BigDecimal,
        leg: String,
    ): Boolean {
        val mid = bid.add(ask).divide(BigDecimal("2"), 4, RoundingMode.HALF_UP)
        if (mid <= BigDecimal.ZERO) {
            logger.debug { "Leg $leg mid is zero, skipping liquidity check" }
            return false
        }
        val spread = ask.subtract(bid)
        val spreadPct = spread.divide(mid, 4, RoundingMode.HALF_UP).toDouble()
        return spreadPct > config.maxLegBidAskSpreadPct
    }
}
