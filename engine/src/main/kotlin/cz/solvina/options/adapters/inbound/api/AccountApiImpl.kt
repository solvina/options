package cz.solvina.options.adapters.inbound.api

import com.ib.client.Decimal
import com.ib.client.EClientSocket
import com.ib.client.Order
import com.ib.client.OrderCancel
import cz.solvina.options.account.api.AccountApi
import cz.solvina.options.account.api.OrdersApi
import cz.solvina.options.account.dto.AccountOverviewDto
import cz.solvina.options.account.dto.AccountPositionDto
import cz.solvina.options.account.dto.ClosePositionRequestDto
import cz.solvina.options.account.dto.OpenOrderDto
import cz.solvina.options.account.dto.OpenPositionDto
import cz.solvina.options.adapters.outbound.ibkr.account.IbkrOpenOrdersAdapter
import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrOrderRegistry
import cz.solvina.options.domain.features.account.AccountPort
import cz.solvina.options.domain.features.account.AccountPosition
import cz.solvina.options.domain.features.account.PositionsPort
import cz.solvina.options.domain.features.spread.SpreadPort
import cz.solvina.options.domain.features.spread.model.BullPutSpread
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

private val logger = KotlinLogging.logger {}

@RestController
@RequestMapping
class AccountApiImpl(
    private val accountPort: AccountPort,
    private val spreadPort: SpreadPort,
    private val positionsPort: PositionsPort,
    private val openOrdersAdapter: IbkrOpenOrdersAdapter,
    private val client: EClientSocket,
    private val orderRegistry: IbkrOrderRegistry,
    private val clock: Clock,
) : AccountApi,
    OrdersApi {
    override suspend fun getAccountOverview(): ResponseEntity<AccountOverviewDto> {
        val detail = accountPort.accountDetail.value
        val openSpreads = spreadPort.findOpen()
        val today = LocalDate.now(clock)

        val ibkrPositions =
            runCatching { positionsPort.getPositions() }
                .onFailure { e -> logger.warn(e) { "Failed to fetch IBKR positions: ${e.message}" } }
                .getOrDefault(emptyList())

        val openOrders =
            runCatching { openOrdersAdapter.getOpenOrders() }
                .onFailure { e -> logger.warn(e) { "Failed to fetch open orders: ${e.message}" } }
                .getOrDefault(emptyList())

        val dto =
            AccountOverviewDto(
                totalCapital = detail?.totalCapital?.amount,
                availableFunds = detail?.availableFunds?.amount,
                unrealizedPnL = detail?.unrealizedPnL?.amount,
                excessLiquidity = detail?.excessLiquidity?.amount,
                openPositionCount = openSpreads.size,
                openPositions = openSpreads.map { it.toDto(today) },
                accountPositionCount = ibkrPositions.size,
                accountPositions = ibkrPositions.map { it.toDto() },
                openOrderCount = openOrders.size,
                openOrders =
                    openOrders.map {
                        OpenOrderDto(
                            it.orderId,
                            it.symbol,
                            it.action,
                            it.orderType,
                            it.status,
                            it.limitPrice?.toBigDecimal(),
                        )
                    },
            )
        return ResponseEntity.ok(dto)
    }

    override suspend fun cancelOrder(orderId: Int): ResponseEntity<Unit> {
        client.cancelOrder(orderId, OrderCancel())
        return ResponseEntity.noContent().build()
    }

    override suspend fun closePosition(closePositionRequestDto: ClosePositionRequestDto): ResponseEntity<Unit> {
        val conId = closePositionRequestDto.conId
        val quantity = closePositionRequestDto.quantity
        val action = if (quantity < BigDecimal.ZERO) "BUY" else "SELL"
        val qty = quantity.abs().toLong()

        val contract =
            com.ib.client.Contract().apply {
                conid(conId)
                exchange("SMART")
            }
        val orderId = orderRegistry.nextOrderId()
        val ibkrOrder =
            Order().apply {
                action(action)
                orderType("MKT")
                totalQuantity(Decimal.get(qty))
                tif("DAY")
            }

        logger.info { "Closing position conId=$conId qty=$quantity → $action $qty orderId=$orderId" }
        client.placeOrder(orderId, contract, ibkrOrder)
        return ResponseEntity.noContent().build()
    }

    private fun BullPutSpread.toDto(today: LocalDate): OpenPositionDto {
        val expiry = soldLeg.contract.expiry
        val dte = ChronoUnit.DAYS.between(today, expiry).toInt()
        val maxRiskTotal =
            maxRiskPerShare
                .multiply(BigDecimal(quantity))
                .multiply(BigDecimal("100"))
        return OpenPositionDto(
            id = id!!,
            symbol = symbol.value,
            soldStrike = soldLeg.contract.strike,
            boughtStrike = boughtLeg.contract.strike,
            expiryDate = expiry,
            creditPerShare = creditPerShare,
            maxRiskPerShare = maxRiskPerShare,
            maxRiskTotal = maxRiskTotal,
            quantity = quantity,
            dte = dte,
            openedAt = openedAt.atOffset(ZoneOffset.UTC),
            ivRankAtEntry = ivRankAtEntry?.toBigDecimal(),
            underlyingPriceAtEntry = underlyingPriceAtEntry,
            unrealizedPnL =
                lastSpreadValue?.let { sv ->
                    creditPerShare
                        .subtract(sv)
                        .multiply(BigDecimal(quantity))
                        .multiply(BigDecimal("100"))
                },
        )
    }

    private fun AccountPosition.toDto(): AccountPositionDto =
        AccountPositionDto(
            account = account,
            symbol = symbol,
            secType = secType,
            currency = currency,
            expiry = expiry,
            strike = strike,
            optionRight = optionRight,
            quantity = quantity,
            avgCost = avgCost,
            conId = conId,
            unrealizedPnL = unrealizedPnL?.toBigDecimal(),
        )
}
