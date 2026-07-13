package cz.solvina.options.adapters.outbound.ibkr

import com.ib.client.Contract
import com.ib.client.EClientSocket
import com.ib.client.EReaderSignal
import com.ib.client.EWrapper
import com.ib.client.Order
import com.ib.client.OrderCancel
import com.ib.client.TagValue
import cz.solvina.options.domain.features.fatal.FatalLockoutService
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * The one choke point every outbound call takes to the broker.
 *
 * Order guard: when [FatalLockoutService] has latched (e.g. account mismatch), placeOrder drops
 * the order instead of sending it — market data, account subscriptions and cancellations keep
 * working so the operator can still see and unwind state, but nothing new reaches the market.
 *
 * Message pacing: every outbound request first draws a token from
 * [IbkrAdmissionController.paceMessage], so the global ~50 msgs/sec IBKR ceiling is enforced here
 * for ALL senders — no call site can bypass it. The token is taken BEFORE delegating to the
 * (synchronized) super method, so a pacing wait never blocks other threads on the client monitor.
 */
class GuardedEClientSocket(
    wrapper: EWrapper,
    signal: EReaderSignal,
    private val fatalLockout: FatalLockoutService,
    private val admission: IbkrAdmissionController,
) : EClientSocket(wrapper, signal) {
    override fun placeOrder(
        id: Int,
        contract: Contract,
        order: Order,
    ) {
        if (fatalLockout.isFatal) {
            logger.error {
                "BLOCKED placeOrder(id=$id, ${contract.symbol()} ${order.action()} ${order.totalQuantity()} ${contract.secType()}) — " +
                    "engine is in FATAL lockout: ${fatalLockout.reasons.joinToString { it.title }}"
            }
            return
        }
        admission.paceMessage()
        super.placeOrder(id, contract, order)
    }

    override fun cancelOrder(
        id: Int,
        orderCancel: OrderCancel?,
    ) {
        admission.paceMessage()
        super.cancelOrder(id, orderCancel)
    }

    override fun reqMktData(
        tickerId: Int,
        contract: Contract?,
        genericTickList: String?,
        snapshot: Boolean,
        regulatorySnapshot: Boolean,
        mktDataOptions: MutableList<TagValue>?,
    ) {
        admission.paceMessage()
        super.reqMktData(tickerId, contract, genericTickList, snapshot, regulatorySnapshot, mktDataOptions)
    }

    override fun cancelMktData(tickerId: Int) {
        admission.paceMessage()
        super.cancelMktData(tickerId)
    }

    override fun reqTickByTickData(
        reqId: Int,
        contract: Contract?,
        tickType: String?,
        numberOfTicks: Int,
        ignoreSize: Boolean,
    ) {
        admission.paceMessage()
        super.reqTickByTickData(reqId, contract, tickType, numberOfTicks, ignoreSize)
    }

    override fun cancelTickByTickData(reqId: Int) {
        admission.paceMessage()
        super.cancelTickByTickData(reqId)
    }

    override fun reqContractDetails(
        reqId: Int,
        contract: Contract?,
    ) {
        admission.paceMessage()
        super.reqContractDetails(reqId, contract)
    }

    override fun reqMarketDataType(marketDataType: Int) {
        admission.paceMessage()
        super.reqMarketDataType(marketDataType)
    }

    override fun reqHistoricalData(
        tickerId: Int,
        contract: Contract?,
        endDateTime: String?,
        durationStr: String?,
        barSizeSetting: String?,
        whatToShow: String?,
        useRTH: Int,
        formatDate: Int,
        keepUpToDate: Boolean,
        chartOptions: MutableList<TagValue>?,
    ) {
        admission.paceMessage()
        super.reqHistoricalData(
            tickerId,
            contract,
            endDateTime,
            durationStr,
            barSizeSetting,
            whatToShow,
            useRTH,
            formatDate,
            keepUpToDate,
            chartOptions,
        )
    }

    override fun reqAccountUpdates(
        subscribe: Boolean,
        acctCode: String?,
    ) {
        admission.paceMessage()
        super.reqAccountUpdates(subscribe, acctCode)
    }

    override fun reqSecDefOptParams(
        reqId: Int,
        underlyingSymbol: String?,
        futFopExchange: String?,
        underlyingSecType: String?,
        underlyingConId: Int,
    ) {
        admission.paceMessage()
        super.reqSecDefOptParams(reqId, underlyingSymbol, futFopExchange, underlyingSecType, underlyingConId)
    }

    override fun reqRealTimeBars(
        tickerId: Int,
        contract: Contract?,
        barSize: Int,
        whatToShow: String?,
        useRTH: Boolean,
        realTimeBarsOptions: MutableList<TagValue>?,
    ) {
        admission.paceMessage()
        super.reqRealTimeBars(tickerId, contract, barSize, whatToShow, useRTH, realTimeBarsOptions)
    }

    override fun cancelRealTimeBars(tickerId: Int) {
        admission.paceMessage()
        super.cancelRealTimeBars(tickerId)
    }

    override fun reqPositions() {
        admission.paceMessage()
        super.reqPositions()
    }

    override fun cancelPositions() {
        admission.paceMessage()
        super.cancelPositions()
    }

    override fun reqPnLSingle(
        reqId: Int,
        account: String?,
        modelCode: String?,
        conId: Int,
    ) {
        admission.paceMessage()
        super.reqPnLSingle(reqId, account, modelCode, conId)
    }

    override fun cancelPnLSingle(reqId: Int) {
        admission.paceMessage()
        super.cancelPnLSingle(reqId)
    }

    override fun reqMarketRule(marketRuleId: Int) {
        admission.paceMessage()
        super.reqMarketRule(marketRuleId)
    }

    override fun reqAllOpenOrders() {
        admission.paceMessage()
        super.reqAllOpenOrders()
    }
}
