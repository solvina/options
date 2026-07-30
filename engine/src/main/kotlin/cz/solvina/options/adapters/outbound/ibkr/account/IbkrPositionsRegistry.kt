package cz.solvina.options.adapters.outbound.ibkr.account

import com.ib.client.Contract
import com.ib.client.Decimal
import cz.solvina.options.domain.features.account.AccountPosition
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration

private val IBKR_DATE = DateTimeFormatter.ofPattern("yyyyMMdd")

@Component
class IbkrPositionsRegistry(
    private val clock: Clock = Clock.systemUTC(),
) {
    // Thread-safe state store for current portfolio positions with real-time PnL
    val portfolio = ConcurrentHashMap<Int, AccountPosition>()

    // Completes when TWS finishes the initial portfolio download. Until then an empty portfolio is a
    // cold feed, not a flat account — recovery must not read it as "no positions".
    @Volatile
    private var downloadComplete = CompletableDeferred<Unit>()

    fun onAccountDownloadEnd() {
        downloadComplete.complete(Unit)
    }

    fun onDisconnect() {
        downloadComplete = CompletableDeferred()
    }

    suspend fun awaitInitialDownload(timeout: Duration): Boolean =
        try {
            withTimeout(timeout) { downloadComplete.await() }
            true
        } catch (_: TimeoutCancellationException) {
            false
        }

    fun onUpdatePortfolio(
        contract: Contract,
        position: Decimal,
        marketPrice: Double,
        marketValue: Double,
        averageCost: Double,
        unrealizedPNL: Double,
        realizedPNL: Double,
        accountName: String,
    ) {
        val position =
            toPosition(
                account = accountName,
                contract = contract,
                pos = position,
                marketPrice = marketPrice,
                marketValue = marketValue,
                avgCost = averageCost,
                unrealizedPNL = unrealizedPNL,
                realizedPNL = realizedPNL,
                updatedAt = clock.instant(),
            )
        if (position == null) {
            portfolio.remove(contract.conid())
        } else {
            portfolio[contract.conid()] = position.copy(unrealizedPnL = unrealizedPNL)
        }
    }

    fun getPositions(): List<AccountPosition> = portfolio.values.toList()
}

private fun toPosition(
    account: String,
    contract: Contract,
    pos: Decimal,
    marketPrice: Double,
    marketValue: Double,
    avgCost: Double,
    unrealizedPNL: Double,
    realizedPNL: Double,
    updatedAt: Instant,
): AccountPosition? {
    val quantity = BigDecimal(pos.value().toPlainString())
    if (quantity.compareTo(BigDecimal.ZERO) == 0) return null

    val expiryStr = contract.lastTradeDateOrContractMonth()
    val expiry =
        if (!expiryStr.isNullOrBlank()) {
            runCatching { LocalDate.parse(expiryStr, IBKR_DATE) }.getOrNull()
        } else {
            null
        }

    val strike =
        contract
            .strike()
            .takeIf { it != 0.0 }
            ?.let { BigDecimal(it).setScale(2, RoundingMode.HALF_UP) }

    val rightApi = contract.right()?.apiString
    val right = rightApi?.takeIf { it.isNotBlank() && it != "?" && it != "0" }

    return AccountPosition(
        account = account,
        symbol = contract.symbol() ?: "",
        secType = contract.secType()?.apiString ?: "",
        currency = contract.currency() ?: "",
        expiry = expiry,
        strike = strike,
        optionRight = right,
        quantity = quantity,
        marketPrice = marketPrice,
        marketValue = marketValue,
        avgCost = BigDecimal(avgCost).setScale(4, RoundingMode.HALF_UP),
        unrealizedPnL = unrealizedPNL,
        realizedPnL = realizedPNL,
        conId = contract.conid(),
        updatedAt = updatedAt,
    )
}
