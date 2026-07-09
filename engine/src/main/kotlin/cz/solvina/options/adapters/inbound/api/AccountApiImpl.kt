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
import cz.solvina.options.domain.features.account.OrphanPositionDetector
import cz.solvina.options.domain.features.account.PositionsPort
import cz.solvina.options.domain.features.flag.FlagPort
import cz.solvina.options.domain.features.spread.BullPutSpreadPort
import cz.solvina.options.domain.features.spread.SpreadCloserRegistry
import cz.solvina.options.domain.features.spread.model.BullPutSpread
import cz.solvina.options.domain.features.spread.model.Spread
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
    private val spreadPort: BullPutSpreadPort,
    private val spreadClosers: SpreadCloserRegistry,
    private val flagPort: FlagPort,
    private val orphanDetector: OrphanPositionDetector,
    private val positionsPort: PositionsPort,
    private val openOrdersAdapter: IbkrOpenOrdersAdapter,
    private val client: EClientSocket,
    private val orderRegistry: IbkrOrderRegistry,
    private val clock: Clock,
) : AccountApi,
    OrdersApi {
    /** Membership of one held option leg within a tracked spread. */
    private data class LegMembership(
        val spreadId: java.util.UUID?,
        val spreadLabel: String,
        val legRole: String,
    )

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

        // Classify each held IBKR position: is it a leg of a tracked spread, or an orphan (no
        // managing open spread/flag, or a quantity mismatch)? Reuses the same detector the
        // reconciliation job uses for alerting, and covers every managed strategy (bull put +
        // bear call spreads via the closer registry, bull flags via the flag port), so bear-call
        // legs and flag stock are classified too — not just bull puts.
        val allManagedSpreads =
            runCatching { spreadClosers.allOpen() + spreadClosers.allClosing() }
                .onFailure { e -> logger.warn(e) { "Failed to load managed spreads: ${e.message}" } }
                .getOrDefault(emptyList())
        val openFlags =
            runCatching { flagPort.findOpen() }
                .onFailure { e -> logger.warn(e) { "Failed to load open flags: ${e.message}" } }
                .getOrDefault(emptyList())
        // Orphans keyed by the exact AccountPosition instance the detector was handed.
        val orphanReasons: Map<AccountPosition, String> =
            runCatching { orphanDetector.detect(allManagedSpreads, openFlags, ibkrPositions).associate { it.position to it.reason } }
                .onFailure { e -> logger.warn(e) { "Orphan detection failed: ${e.message}" } }
                .getOrDefault(emptyMap())
        val legMembership: Map<PosKey, LegMembership> = buildLegMembership(allManagedSpreads)

        val dto =
            AccountOverviewDto(
                totalCapital = detail?.totalCapital?.amount,
                availableFunds = detail?.availableFunds?.amount,
                unrealizedPnL = detail?.unrealizedPnL?.amount,
                excessLiquidity = detail?.excessLiquidity?.amount,
                openPositionCount = openSpreads.size,
                openPositions = openSpreads.map { it.toDto(today) },
                accountPositionCount = ibkrPositions.size,
                accountPositions = ibkrPositions.map { it.toDto(orphanReasons[it], legMembership) },
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
            underlyingPriceNow = lastUnderlyingPrice,
            distanceToShortStrikePct = cushionPct(lastUnderlyingPrice, soldLeg.contract.strike, bullish = true),
            unrealizedPnL =
                lastSpreadValue?.let { sv ->
                    creditPerShare
                        .subtract(sv)
                        .multiply(BigDecimal(quantity))
                        .multiply(BigDecimal("100"))
                },
        )
    }

    private fun AccountPosition.toDto(
        orphanReason: String?,
        legMembership: Map<PosKey, LegMembership>,
    ): AccountPositionDto {
        // Orphan wins over spread membership: a leg whose quantity mismatches its spread is still
        // flagged by the detector, and the operator needs to see it in the orphan table with the
        // reason rather than tucked under a "healthy" pair.
        val isOrphan = orphanReason != null
        val membership = if (isOrphan) null else legMembership[PosKey(symbol, strike, optionRight, expiry)]
        return AccountPositionDto(
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
            orphan = isOrphan,
            orphanReason = orphanReason,
            spreadId = membership?.spreadId,
            spreadLabel = membership?.spreadLabel,
            legRole = membership?.legRole,
        )
    }

    /** Contract-identity key shared by held positions and spread legs (mirrors OrphanPositionDetector). */
    private data class PosKey(
        val symbol: String,
        val strike: BigDecimal?,
        val right: String?,
        val expiry: LocalDate?,
    )

    private fun buildLegMembership(spreads: List<Spread>): Map<PosKey, LegMembership> {
        val map = HashMap<PosKey, LegMembership>()
        for (s in spreads) {
            val label =
                "${s.symbol.value} ${s.soldLeg.contract.strike}/${s.boughtLeg.contract.strike} ${s.soldLeg.contract.type.ibkrCode}"
            map[legKey(s.soldLeg)] = LegMembership(s.id, label, "SHORT")
            map[legKey(s.boughtLeg)] = LegMembership(s.id, label, "LONG")
        }
        return map
    }

    private fun legKey(leg: cz.solvina.options.domain.features.spread.model.SpreadLeg): PosKey =
        PosKey(leg.contract.symbol.value, leg.contract.strike, leg.contract.type.ibkrCode, leg.contract.expiry)
}
