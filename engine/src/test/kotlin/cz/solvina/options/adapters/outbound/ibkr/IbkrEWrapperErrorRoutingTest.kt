package cz.solvina.options.adapters.outbound.ibkr

import cz.solvina.options.adapters.outbound.ibkr.account.IbkrOrdersRegistry
import cz.solvina.options.adapters.outbound.ibkr.account.IbkrPositionsRegistry
import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrAccountRegistry
import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrContractDetailsRegistry
import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrDividendTickRegistry
import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrHistoricalDataRegistry
import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrMarketDataRegistry
import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrMarketRuleRegistry
import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrOptionParamsRegistry
import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrOrderIdCounter
import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrRealTimeBarsRegistry
import cz.solvina.options.adapters.outbound.ibkr.registry.PendingRealTimeBarsRequest
import cz.solvina.options.domain.features.alert.AlertService
import cz.solvina.options.domain.features.market.MarketDataHealthTracker
import cz.solvina.options.domain.features.market.MarketDataTypeTracker
import cz.solvina.options.domain.models.Symbol
import io.mockk.mockk
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Regression coverage for real-time-bar error routing through the EWrapper.
 *
 * IbkrRealTimeBarsRegistry was split out of IbkrMarketDataRegistry, which used to special-case
 * routing bars-subscription errors (competing session, error 456, no live subscription) to the
 * subscriber before any generic handling. The registry split re-added that routing method
 * (onError), but IbkrEWrapper.error() must actually call it — otherwise a rejected bars
 * subscription is silently dropped and the flag strategy goes blind with zero alert.
 */
class IbkrEWrapperErrorRoutingTest {
    private fun buildWrapper(realTimeBarsRegistry: IbkrRealTimeBarsRegistry) =
        IbkrEWrapper(
            historicalRegistry = mockk<IbkrHistoricalDataRegistry>(relaxed = true),
            contractDetailsRegistry = mockk<IbkrContractDetailsRegistry>(relaxed = true),
            optionParamsRegistry = mockk<IbkrOptionParamsRegistry>(relaxed = true),
            marketRuleRegistry = mockk<IbkrMarketRuleRegistry>(relaxed = true),
            marketDataRegistry = mockk<IbkrMarketDataRegistry>(relaxed = true),
            realTimeBarsRegistry = realTimeBarsRegistry,
            orderRegistry = mockk<IbkrOrdersRegistry>(relaxed = true),
            ibkrOrderIdCounter = mockk<IbkrOrderIdCounter>(relaxed = true),
            accountRegistry = mockk<IbkrAccountRegistry>(relaxed = true),
            positionsRegistry = mockk<IbkrPositionsRegistry>(relaxed = true),
            dividendTickRegistry = mockk<IbkrDividendTickRegistry>(relaxed = true),
            marketDataHealthTracker = mockk<MarketDataHealthTracker>(relaxed = true),
            marketDataTypeTracker = mockk<MarketDataTypeTracker>(relaxed = true),
            alertService = mockk<AlertService>(relaxed = true),
        )

    @Test
    fun `error() routes a rejected real-time-bars subscription to its onError callback`() {
        val realTimeBarsRegistry = IbkrRealTimeBarsRegistry()
        val reqId = 7
        var receivedCode: Int? = null
        var receivedMsg: String? = null
        realTimeBarsRegistry.addRealTimeBarRequest(
            reqId,
            Symbol("AAPL"),
            PendingRealTimeBarsRequest(
                onBar = {},
                onError = { code, msg ->
                    receivedCode = code
                    receivedMsg = msg
                },
            ),
        )
        val wrapper = buildWrapper(realTimeBarsRegistry)

        // 354 = no live subscription — one of the rejection codes a bars stream can receive.
        wrapper.error(reqId, 354, "no live subscription", null)

        assertEquals(354, receivedCode, "real-time-bars rejection must reach the subscriber's onError")
        assertEquals("no live subscription", receivedMsg)
    }
}
