package cz.solvina.options.domain.features.execution

import cz.solvina.options.domain.features.account.AccountPort
import cz.solvina.options.domain.features.execution.model.ExecutionOutcome
import cz.solvina.options.domain.features.execution.model.TradeExecutionRequest
import cz.solvina.options.domain.features.order.OrderExecutionPort
import cz.solvina.options.domain.features.scanner.ScannerConfig
import cz.solvina.options.domain.features.spread.BullPutSpreadPort
import cz.solvina.options.domain.features.spread.model.SpreadStatus
import cz.solvina.options.domain.models.Symbol
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode

private val logger = KotlinLogging.logger {}
private val tradeLogger = KotlinLogging.logger("TRADES")

@Component
class PreTradeValidator(
    private val spreadPort: BullPutSpreadPort,
    private val orderExecutionPort: OrderExecutionPort,
    private val accountPort: AccountPort,
    private val config: ScannerConfig,
) {
    suspend fun validate(
        request: TradeExecutionRequest,
        inFlightSymbols: Set<Symbol>,
    ): ExecutionOutcome? {
        // Exposure: open/closing spreads + in-flight (in-memory) + live IBKR open orders (survives restart)
        val allSpreads = spreadPort.findOpen() + spreadPort.findByStatus(SpreadStatus.CLOSING)
        val openSymbols = allSpreads.map { it.symbol }.toSet()

        // CLOSING freeze: prevent new entries on a symbol while the close is in-flight.
        // Rationale: during the 1-60s window while close orders are being submitted/filled,
        // any new entry would interfere with position reconciliation. Freeze is temporary.
        val closingSymbols = spreadPort.findByStatus(SpreadStatus.CLOSING).map { it.symbol }.toSet()
        if (request.underlyingSymbol in closingSymbols) {
            logger.info { "[${request.underlyingSymbol}] CLOSING_FREEZE — symbol has in-flight close order, entry blocked" }
            tradeLogger.info {
                "REJECT ${request.underlyingSymbol}  ${request.soldContract.strike}P/${request.boughtContract.strike}P  reason=CLOSING_FREEZE"
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
                "REJECT ${request.underlyingSymbol}  ${request.soldContract.strike}P/${request.boughtContract.strike}P  reason=EXPOSURE_REJECTED"
            }
            return ExecutionOutcome.EXPOSURE_REJECTED
        }

        // Capital: available funds vs max risk per contract
        val availableFunds =
            accountPort.accountDetail.value
                ?.availableFunds
                ?.amount
        val maxRiskPerContract = request.maxRiskPerShare.multiply(BigDecimal("100"))
        if (availableFunds == null || availableFunds < maxRiskPerContract) {
            logger.info {
                "[${request.underlyingSymbol}] CAPITAL_REJECTED — available=\$$availableFunds " +
                    "required=\$$maxRiskPerContract"
            }
            tradeLogger.info {
                "REJECT ${request.underlyingSymbol}  ${request.soldContract.strike}P/${request.boughtContract.strike}P  reason=CAPITAL_REJECTED  available=\$$availableFunds  required=\$$maxRiskPerContract"
            }
            return ExecutionOutcome.CAPITAL_REJECTED
        }

        // Liquidity: leg bid-ask spreads
        if (isLiquidityTooWide(request.soldBid, request.soldAsk, "sold")) {
            logger.info { "[${request.underlyingSymbol}] LIQUIDITY_REJECTED — sold leg spread too wide" }
            tradeLogger.info {
                "REJECT ${request.underlyingSymbol}  ${request.soldContract.strike}P/${request.boughtContract.strike}P  reason=LIQUIDITY_REJECTED  leg=sold"
            }
            return ExecutionOutcome.LIQUIDITY_REJECTED
        }
        if (isLiquidityTooWide(request.boughtBid, request.boughtAsk, "bought")) {
            logger.info { "[${request.underlyingSymbol}] LIQUIDITY_REJECTED — bought leg spread too wide" }
            tradeLogger.info {
                "REJECT ${request.underlyingSymbol}  ${request.soldContract.strike}P/${request.boughtContract.strike}P  reason=LIQUIDITY_REJECTED  leg=bought"
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
