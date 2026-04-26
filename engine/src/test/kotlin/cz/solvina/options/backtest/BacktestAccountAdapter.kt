package cz.solvina.options.backtest

import cz.solvina.options.domain.features.account.AccountDetail
import cz.solvina.options.domain.features.account.AccountPort
import cz.solvina.options.domain.models.Money
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.math.BigDecimal

/**
 * In-memory account for backtesting.
 *
 * Tracks available capital. The backtest engine calls [debit] and [credit]
 * after each trade to keep the balance current so ScannerService's capital-
 * guard logic (`maxRiskPercent`) works exactly as it does in production.
 */
class BacktestAccountAdapter(
    initialCapital: BigDecimal,
) : AccountPort {
    private val _accountDetail =
        MutableStateFlow<AccountDetail?>(
            AccountDetail(
                totalCapital = Money(initialCapital),
                availableFunds = Money(initialCapital),
            ),
        )
    override val accountDetail: StateFlow<AccountDetail?> = _accountDetail.asStateFlow()

    /** Reduce available funds by [amount] when a spread is opened. */
    fun debit(amount: BigDecimal) {
        _accountDetail.value =
            _accountDetail.value?.let { detail ->
                val newFunds = (detail.availableFunds?.amount ?: BigDecimal.ZERO).subtract(amount)
                val newTotal = (detail.totalCapital?.amount ?: BigDecimal.ZERO).subtract(amount)
                detail.copy(
                    availableFunds = Money(newFunds),
                    totalCapital = Money(newTotal),
                )
            }
    }

    /** Add [amount] to available funds when a spread is closed. */
    fun credit(amount: BigDecimal) {
        _accountDetail.value =
            _accountDetail.value?.let { detail ->
                val newFunds = (detail.availableFunds?.amount ?: BigDecimal.ZERO).add(amount)
                val newTotal = (detail.totalCapital?.amount ?: BigDecimal.ZERO).add(amount)
                detail.copy(
                    availableFunds = Money(newFunds),
                    totalCapital = Money(newTotal),
                )
            }
    }
}
