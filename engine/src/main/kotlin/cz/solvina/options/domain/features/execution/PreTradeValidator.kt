package cz.solvina.options.domain.features.execution

import cz.solvina.options.domain.features.account.AccountPort
import cz.solvina.options.domain.features.execution.model.ExecutionOutcome
import cz.solvina.options.domain.features.execution.model.TradeExecutionRequest
import cz.solvina.options.domain.features.order.OrderExecutionPort
import cz.solvina.options.domain.features.scanner.ScannerConfig
import cz.solvina.options.domain.features.spread.SpreadPort
import cz.solvina.options.domain.models.Symbol
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode

private val logger = KotlinLogging.logger {}

@Component
class PreTradeValidator(
    private val spreadPort: SpreadPort,
    private val orderExecutionPort: OrderExecutionPort,
    private val accountPort: AccountPort,
    private val config: ScannerConfig,
) {
    suspend fun validate(request: TradeExecutionRequest, inFlightSymbols: Set<Symbol>): ExecutionOutcome? {
        // Exposure: open spreads + in-flight (in-memory) + live IBKR open orders (survives restart)
        val openSymbols = spreadPort.findOpen().map { it.symbol }.toSet()
        val ibkrOpenSymbols =
            runCatching { orderExecutionPort.getSymbolsWithOpenOrders() }
                .onFailure { e -> logger.warn(e) { "[${request.underlyingSymbol}] Could not fetch open IBKR orders: ${e.message}" } }
                .getOrDefault(emptySet())
        if (request.underlyingSymbol in openSymbols ||
            request.underlyingSymbol in ibkrOpenSymbols ||
            request.underlyingSymbol in inFlightSymbols
        ) {
            logger.info { "[${request.underlyingSymbol}] EXPOSURE_REJECTED — open or in-flight position exists" }
            return ExecutionOutcome.EXPOSURE_REJECTED
        }

        // Capital: available funds vs max risk per contract
        val availableFunds = accountPort.accountDetail.value?.availableFunds?.amount
        val maxRiskPerContract = request.maxRiskPerShare.multiply(BigDecimal("100"))
        if (availableFunds == null || availableFunds < maxRiskPerContract) {
            logger.info {
                "[${request.underlyingSymbol}] CAPITAL_REJECTED — available=\$$availableFunds " +
                    "required=\$$maxRiskPerContract"
            }
            return ExecutionOutcome.CAPITAL_REJECTED
        }

        // Liquidity: leg bid-ask spreads
        if (isLiquidityTooWide(request.soldBid, request.soldAsk, "sold")) {
            logger.info { "[${request.underlyingSymbol}] LIQUIDITY_REJECTED — sold leg spread too wide" }
            return ExecutionOutcome.LIQUIDITY_REJECTED
        }
        if (isLiquidityTooWide(request.boughtBid, request.boughtAsk, "bought")) {
            logger.info { "[${request.underlyingSymbol}] LIQUIDITY_REJECTED — bought leg spread too wide" }
            return ExecutionOutcome.LIQUIDITY_REJECTED
        }

        return null
    }

    private fun isLiquidityTooWide(bid: BigDecimal, ask: BigDecimal, leg: String): Boolean {
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
