package cz.solvina.options.adapters.inbound.api

import cz.solvina.options.account.api.AccountApi
import cz.solvina.options.account.dto.AccountOverviewDto
import cz.solvina.options.account.dto.AccountPositionDto
import cz.solvina.options.account.dto.OpenPositionDto
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
    private val clock: Clock,
) : AccountApi {
    override suspend fun getAccountOverview(): ResponseEntity<AccountOverviewDto> {
        val detail = accountPort.accountDetail.value
        val openSpreads = spreadPort.findOpen()
        val today = LocalDate.now(clock)

        val ibkrPositions =
            runCatching { positionsPort.getPositions() }
                .onFailure { e -> logger.warn(e) { "Failed to fetch IBKR positions: ${e.message}" } }
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
            )
        return ResponseEntity.ok(dto)
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
        )
}
