package cz.solvina.options.adapters.outbound.ibkr.account

import com.ib.client.Contract
import com.ib.client.Decimal
import cz.solvina.options.domain.features.account.AccountPosition
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CompletableDeferred
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Collections

private val logger = KotlinLogging.logger {}
private val IBKR_DATE = DateTimeFormatter.ofPattern("yyyyMMdd")

@Component
class IbkrPositionsRegistry {
    @Volatile private var pending: CompletableDeferred<List<AccountPosition>>? = null
    private val buffer: MutableList<AccountPosition> = Collections.synchronizedList(mutableListOf())

    fun startRequest(): CompletableDeferred<List<AccountPosition>> {
        pending?.cancel()
        buffer.clear()
        val deferred = CompletableDeferred<List<AccountPosition>>()
        pending = deferred
        return deferred
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

        val rightApi = contract.right()?.getApiString()
        val right = rightApi?.takeIf { it.isNotBlank() && it != "?" && it != "0" }

        buffer.add(
            AccountPosition(
                account = account,
                symbol = contract.symbol() ?: "",
                secType = contract.secType()?.getApiString() ?: "",
                currency = contract.currency() ?: "",
                expiry = expiry,
                strike = strike,
                optionRight = right,
                quantity = quantity,
                avgCost = BigDecimal(avgCost).setScale(4, RoundingMode.HALF_UP),
                conId = contract.conid(),
            ),
        )
    }

    fun onPositionEnd() {
        val snapshot = buffer.toList()
        logger.debug { "positionEnd: ${snapshot.size} position(s) received" }
        pending?.complete(snapshot)
        pending = null
    }

    fun cancelPending() {
        pending?.cancel()
        pending = null
        buffer.clear()
    }
}
