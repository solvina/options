package cz.solvina.options.adapters.outbound.ibkr.account

import com.ib.client.Contract
import com.ib.client.Decimal
import cz.solvina.options.domain.features.account.AccountPosition
import kotlinx.coroutines.CompletableDeferred
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArrayList

private val IBKR_DATE = DateTimeFormatter.ofPattern("yyyyMMdd")

@Component
class IbkrPositionsRegistry {
    private val lock = Any()

    @Volatile
    private var pending: CompletableDeferred<List<AccountPosition>>? = null
    private val buffer = CopyOnWriteArrayList<AccountPosition>()
    private var activeRequestId: Long = 0L

    fun startRequest(): CompletableDeferred<List<AccountPosition>> =
        synchronized(lock) {
            pending?.cancel()
            buffer.clear()
            activeRequestId++
            val deferred = CompletableDeferred<List<AccountPosition>>()
            pending = deferred
            deferred
        }

    fun onPosition(
        account: String,
        contract: Contract,
        pos: Decimal,
        avgCost: Double,
    ) {
        val quantity = BigDecimal(pos.value().toPlainString())
        if (quantity.compareTo(BigDecimal.ZERO) == 0) return

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

        val position =
            AccountPosition(
                account = account,
                symbol = contract.symbol() ?: "",
                secType = contract.secType()?.apiString ?: "",
                currency = contract.currency() ?: "",
                expiry = expiry,
                strike = strike,
                optionRight = right,
                quantity = quantity,
                avgCost = BigDecimal(avgCost).setScale(4, RoundingMode.HALF_UP),
                conId = contract.conid(),
            )

        synchronized(lock) {
            // Drop callbacks if no active request is waiting
            if (pending != null && pending?.isCompleted == false) {
                buffer.add(position)
            }
        }
    }

    fun onPositionEnd() =
        synchronized(lock) {
            val deferred = pending ?: return@synchronized
            val snapshot = buffer.toList()
            buffer.clear()
            pending = null
            deferred.complete(snapshot)
        }

    fun cancelPending() =
        synchronized(lock) {
            pending?.cancel()
            pending = null
            buffer.clear()
        }
}
