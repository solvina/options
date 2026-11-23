package cz.solvina.options.adapters.outbound.ibkr

import com.ib.client.Contract
import com.ib.client.ContractDetails
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SlimOptionDetailsTest {
    private fun details(secType: String) =
        ContractDetails().apply {
            contract(
                Contract().apply {
                    secType(secType)
                    conid(42)
                    strike(100.0)
                },
            )
            orderTypes("ACTIVETIM,AD,ADJUST,ALERT,ALGO,ALLOC,AVGCOST,BASKET")
            validExchanges("SMART,AMEX,CBOE,PHLX")
            tradingHours("20261123:0930-20261123:1600")
            liquidHours("20261123:0930-20261123:1600")
            longName("APPLE INC")
            minTick(0.01)
            marketRuleIds("32,109")
        }

    @Test
    fun `option rows lose the long text fields but keep what pricing and routing use`() {
        val slim = slimOptionDetails(details("OPT"))

        assertNull(slim.orderTypes())
        assertNull(slim.validExchanges())
        assertNull(slim.tradingHours())
        assertNull(slim.liquidHours())
        assertNull(slim.longName())
        assertEquals(42, slim.contract().conid())
        assertEquals(100.0, slim.contract().strike())
        assertEquals(0.01, slim.minTick())
        assertEquals("32,109", slim.marketRuleIds())
    }

    @Test
    fun `stock rows are kept whole - their liquid hours feed the trading calendar`() {
        val stock = slimOptionDetails(details("STK"))

        assertEquals("20261123:0930-20261123:1600", stock.liquidHours())
        assertEquals("SMART,AMEX,CBOE,PHLX", stock.validExchanges())
    }
}
