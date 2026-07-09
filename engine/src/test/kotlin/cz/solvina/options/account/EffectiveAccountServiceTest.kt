package cz.solvina.options.account

import cz.solvina.options.domain.features.account.AccountDetail
import cz.solvina.options.domain.features.account.AccountPort
import cz.solvina.options.domain.features.account.EffectiveAccountService
import cz.solvina.options.domain.models.Money
import kotlinx.coroutines.flow.MutableStateFlow
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EffectiveAccountServiceTest {
    private fun port(
        capital: BigDecimal?,
        funds: BigDecimal?,
    ): AccountPort =
        object : AccountPort {
            override val accountDetail =
                MutableStateFlow(
                    capital?.let {
                        AccountDetail(
                            totalCapital = Money(it),
                            availableFunds = funds?.let(::Money),
                        )
                    },
                )
        }

    @Test
    fun `caps both capital figures at effective size when real balance is larger`() {
        val svc = EffectiveAccountService(port(BigDecimal("1090000"), BigDecimal("1080000")), BigDecimal("50000"))
        val d = svc.detail()!!
        assertEquals(BigDecimal("50000"), d.totalCapital!!.amount, "net liq must be capped to effective size")
        assertEquals(BigDecimal("50000"), d.availableFunds!!.amount, "available funds must be capped to effective size")
    }

    @Test
    fun `a real balance below the cap is left unchanged (cap is a ceiling, not an override)`() {
        val svc = EffectiveAccountService(port(BigDecimal("25000"), BigDecimal("22000")), BigDecimal("50000"))
        val d = svc.detail()!!
        assertEquals(BigDecimal("25000"), d.totalCapital!!.amount)
        assertEquals(BigDecimal("22000"), d.availableFunds!!.amount)
    }

    @Test
    fun `null effective size passes the real figures through untouched`() {
        val svc = EffectiveAccountService(port(BigDecimal("1090000"), BigDecimal("1080000")), null)
        val d = svc.detail()!!
        assertEquals(BigDecimal("1090000"), d.totalCapital!!.amount)
        assertEquals(BigDecimal("1080000"), d.availableFunds!!.amount)
    }

    @Test
    fun `null broker snapshot stays null (do not invent capital)`() {
        val svc = EffectiveAccountService(port(null, null), BigDecimal("50000"))
        assertNull(svc.detail())
    }
}
