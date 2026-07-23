package cz.solvina.options.adapters.outbound.ibkr

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.ib.client.Bar
import com.ib.client.CommissionReport
import com.ib.client.Contract
import com.ib.client.ContractDescription
import com.ib.client.ContractDetails
import com.ib.client.Decimal
import com.ib.client.DeltaNeutralContract
import com.ib.client.DepthMktDataDescription
import com.ib.client.EWrapper
import com.ib.client.Execution
import com.ib.client.FamilyCode
import com.ib.client.HistogramEntry
import com.ib.client.HistoricalSession
import com.ib.client.HistoricalTick
import com.ib.client.HistoricalTickBidAsk
import com.ib.client.HistoricalTickLast
import com.ib.client.NewsProvider
import com.ib.client.Order
import com.ib.client.OrderState
import com.ib.client.PriceIncrement
import com.ib.client.SoftDollarTier
import com.ib.client.TickAttrib
import com.ib.client.TickAttribBidAsk
import com.ib.client.TickAttribLast
import com.ib.client.TickType
import cz.solvina.options.adapters.outbound.ibkr.account.IbkrOpenOrdersRegistry
import cz.solvina.options.adapters.outbound.ibkr.account.IbkrPnlRegistry
import cz.solvina.options.adapters.outbound.ibkr.account.IbkrPositionsRegistry
import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrAccountRegistry
import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrContractRegistry
import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrDividendTickRegistry
import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrHistoricalDataRegistry
import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrMarketDataRegistry
import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrOrderRegistry
import cz.solvina.options.adapters.outbound.ibkr.registry.TickByTickBidAsk
import cz.solvina.options.domain.features.market.MarketDataHealthTracker
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import java.time.Instant

private val logger = KotlinLogging.logger {}
private val twsRaw = KotlinLogging.logger("TWS_RAW")
private val jsonMapper = ObjectMapper().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

private fun twsJson(
    type: String,
    vararg fields: Pair<String, Any?>,
): String {
    val map = linkedMapOf<String, Any?>("ts" to Instant.now().toString(), "type" to type)
    fields.forEach { (k, v) -> if (v != null) map[k] = v }
    return jsonMapper.writeValueAsString(map)
}

private fun tickFieldName(field: Int) =
    when (field) {
        0 -> "BID_SIZE"
        1 -> "BID"
        2 -> "ASK"
        3 -> "ASK_SIZE"
        4 -> "LAST"
        5 -> "LAST_SIZE"
        6 -> "HIGH"
        7 -> "LOW"
        9 -> "CLOSE_PREV"
        14 -> "OPEN"
        24 -> "VOLUME"
        37 -> "MARK"
        10 -> "BID_OPT"
        11 -> "ASK_OPT"
        12 -> "LAST_OPT"
        13 -> "MODEL"
        66 -> "DELAYED_BID"
        67 -> "DELAYED_ASK"
        68 -> "DELAYED_LAST"
        72 -> "DELAYED_HIGH"
        73 -> "DELAYED_LOW"
        75 -> "DELAYED_CLOSE"
        else -> "FIELD_$field"
    }

private fun optFieldName(field: Int) =
    when (field) {
        10 -> "BID"
        11 -> "ASK"
        12 -> "LAST"
        13 -> "MODEL"
        53 -> "CUSTOM"
        80 -> "DELAYED_BID"
        81 -> "DELAYED_ASK"
        82 -> "DELAYED_LAST"
        83 -> "DELAYED_MODEL"
        else -> "FIELD_$field"
    }

@Component
class IbkrEWrapper(
    private val historicalRegistry: IbkrHistoricalDataRegistry,
    private val contractRegistry: IbkrContractRegistry,
    private val marketDataRegistry: IbkrMarketDataRegistry,
    private val orderRegistry: IbkrOrderRegistry,
    private val accountRegistry: IbkrAccountRegistry,
    private val positionsRegistry: IbkrPositionsRegistry,
    private val openOrdersRegistry: IbkrOpenOrdersRegistry,
    private val pnlRegistry: IbkrPnlRegistry,
    private val dividendTickRegistry: IbkrDividendTickRegistry,
    private val marketDataHealthTracker: MarketDataHealthTracker,
) : EWrapper {
    override fun tickPrice(
        tickerId: Int,
        field: Int,
        price: Double,
        attrib: TickAttrib,
    ) {
        logger.trace { "tickPrice: tickerId=$tickerId, field=$field, price=$price" }
        twsRaw.debug { twsJson("TICK_PRICE", "tid" to tickerId, "field" to tickFieldName(field), "price" to price) }
        // A positive price is live data flowing (IBKR sends -1 when a value is unavailable); keep the
        // market-data flow heartbeat fresh between scans. See MarketDataHealthTracker.recordLiveTick.
        if (price > 0.0) marketDataHealthTracker.recordLiveTick()
        marketDataRegistry.onTickPrice(tickerId, field, price)
    }

    override fun tickSize(
        tickerId: Int,
        field: Int,
        size: Decimal,
    ) {
        logger.trace { "tickSize: tickerId=$tickerId, field=$field, size=$size" }
        twsRaw.debug { twsJson("TICK_SIZE", "tid" to tickerId, "field" to tickFieldName(field), "size" to size.toString()) }
    }

    override fun tickOptionComputation(
        tickerId: Int,
        field: Int,
        tickAttrib: Int,
        impliedVol: Double,
        delta: Double,
        optPrice: Double,
        pvDividend: Double,
        gamma: Double,
        vega: Double,
        theta: Double,
        undPrice: Double,
    ) {
        logger.trace { "tickOptionComputation: tickerId=$tickerId, field=$field, delta=$delta" }
        twsRaw.debug {
            twsJson(
                "OPT_COMPUTE",
                "tid" to tickerId,
                "field" to optFieldName(field),
                "iv" to impliedVol,
                "delta" to delta,
                "gamma" to gamma,
                "vega" to vega,
                "theta" to theta,
                "optPrice" to optPrice,
                "undPrice" to undPrice,
            )
        }
        marketDataRegistry.onTickOptionComputation(tickerId, field, impliedVol, delta, gamma, vega, theta)
    }

    override fun tickGeneric(
        tickerId: Int,
        tickType: Int,
        value: Double,
    ) {
        logger.trace { "tickGeneric: tickerId=$tickerId, tickType=$tickType, value=$value" }
    }

    override fun tickString(
        tickerId: Int,
        tickType: Int,
        value: String,
    ) {
        logger.trace { "tickString: tickerId=$tickerId, tickType=$tickType" }
        if (tickType == TickType.IB_DIVIDENDS.index()) {
            dividendTickRegistry.onDividendTick(tickerId, value)
        }
    }

    override fun tickEFP(
        tickerId: Int,
        tickType: Int,
        basisPoints: Double,
        formattedBasisPoints: String,
        impliedFuture: Double,
        holdDays: Int,
        futureLastTradeDate: String,
        dividendImpact: Double,
        dividendsToLastTradeDate: Double,
    ) {
        logger.trace { "tickEFP: tickerId=$tickerId" }
    }

    override fun orderStatus(
        orderId: Int,
        status: String,
        filled: Decimal,
        remaining: Decimal,
        avgFillPrice: Double,
        permId: Int,
        parentId: Int,
        lastFillPrice: Double,
        clientId: Int,
        whyHeld: String?,
        mktCapPrice: Double,
    ) {
        logger.debug { "orderStatus: orderId=$orderId, status=$status, filled=$filled avgFill=$avgFillPrice" }
        twsRaw.debug {
            twsJson(
                "ORDER_STATUS",
                "orderId" to orderId,
                "status" to status,
                "filled" to filled.toString(),
                "remaining" to remaining.toString(),
                "avgFill" to avgFillPrice,
                "lastFill" to lastFillPrice,
                "parentId" to parentId,
                "whyHeld" to whyHeld,
            )
        }
        orderRegistry.onOrderStatus(orderId, status, avgFillPrice, filled.value(), remaining.value())
    }

    override fun openOrder(
        orderId: Int,
        contract: Contract,
        order: Order,
        orderState: OrderState,
    ) {
        logger.debug { "openOrder: orderId=$orderId, symbol=${contract.symbol()}" }
        twsRaw.debug {
            twsJson(
                "OPEN_ORDER",
                "orderId" to orderId,
                "sym" to contract.symbol(),
                "secType" to contract.secType().toString(),
                "expiry" to contract.lastTradeDateOrContractMonth(),
                "strike" to contract.strike(),
                "right" to contract.right().toString(),
                "action" to order.action().toString(),
                "qty" to order.totalQuantity().toString(),
                "orderType" to order.orderType().toString(),
                "lmtPrice" to order.lmtPrice(),
                "auxPrice" to order.auxPrice(),
                "tif" to order.tif().toString(),
                "status" to orderState.status(),
            )
        }
        openOrdersRegistry.onOpenOrder(orderId, contract, order, orderState)
    }

    override fun openOrderEnd() {
        logger.debug { "openOrderEnd" }
        openOrdersRegistry.onOpenOrderEnd()
    }

    override fun updateAccountValue(
        key: String,
        value: String,
        currency: String?,
        accountName: String,
    ) {
        logger.trace { "updateAccountValue: key=$key, value=$value, currency=$currency" }
        accountRegistry.onAccountValue(key, value, accountName)
    }

    override fun updatePortfolio(
        contract: Contract,
        position: Decimal,
        marketPrice: Double,
        marketValue: Double,
        averageCost: Double,
        unrealizedPNL: Double,
        realizedPNL: Double,
        accountName: String,
    ) {
        logger.trace { "updatePortfolio: symbol=${contract.symbol()}, position=$position" }
        twsRaw.debug {
            twsJson(
                "PORTFOLIO",
                "sym" to contract.symbol(),
                "secType" to contract.secType().toString(),
                "expiry" to contract.lastTradeDateOrContractMonth(),
                "strike" to contract.strike(),
                "right" to contract.right().toString(),
                "pos" to position.toString(),
                "mktPrice" to marketPrice,
                "mktValue" to marketValue,
                "avgCost" to averageCost,
                "unrealPnl" to unrealizedPNL,
                "realPnl" to realizedPNL,
            )
        }
    }

    override fun updateAccountTime(timeStamp: String) {
        logger.trace { "updateAccountTime: $timeStamp" }
    }

    override fun accountDownloadEnd(accountName: String) {
        logger.debug { "accountDownloadEnd: $accountName" }
    }

    override fun nextValidId(orderId: Int) {
        logger.info { "nextValidId: $orderId" }
        orderRegistry.seedOrderId(orderId)
    }

    override fun contractDetails(
        reqId: Int,
        contractDetails: ContractDetails,
    ) {
        // Per-row DEBUG removed 2026-07-22: an under-specified option reqContractDetails returns the
        // whole chain across every listing exchange (thousands of rows per symbol), and one log line
        // each flooded and rotated the journal during a competing-session storm (evidence loss). The
        // row count is logged once by IbkrContractCache; raw rows still go to twsRaw below.
        val c = contractDetails.contract()
        twsRaw.debug {
            twsJson(
                "CONTRACT_DET",
                "reqId" to reqId,
                "sym" to c.symbol(),
                "secType" to c.secType().toString(),
                "conId" to c.conid(),
                "expiry" to c.lastTradeDateOrContractMonth(),
                "strike" to c.strike(),
                "right" to c.right().toString(),
                "exchange" to c.exchange(),
                "currency" to c.currency(),
                "multiplier" to c.multiplier(),
                "tradingClass" to c.tradingClass(),
                "minTick" to contractDetails.minTick(),
                "orderTypes" to contractDetails.orderTypes(),
            )
        }
        contractRegistry.onContractDetails(reqId, contractDetails)
    }

    override fun bondContractDetails(
        reqId: Int,
        contractDetails: ContractDetails,
    ) {
        logger.debug { "bondContractDetails: reqId=$reqId" }
    }

    override fun contractDetailsEnd(reqId: Int) {
        logger.debug { "contractDetailsEnd: reqId=$reqId" }
        contractRegistry.onContractDetailsEnd(reqId)
    }

    override fun execDetails(
        reqId: Int,
        contract: Contract,
        execution: Execution,
    ) {
        logger.debug { "execDetails: reqId=$reqId" }
        twsRaw.debug {
            twsJson(
                "EXEC_DETAILS",
                "reqId" to reqId,
                "sym" to contract.symbol(),
                "secType" to contract.secType().toString(),
                "expiry" to contract.lastTradeDateOrContractMonth(),
                "strike" to contract.strike(),
                "right" to contract.right().toString(),
                "side" to execution.side(),
                "qty" to execution.shares().toString(),
                "price" to execution.price(),
                "avgPrice" to execution.avgPrice(),
                "execId" to execution.execId(),
                "orderId" to execution.orderId(),
                "time" to execution.time(),
            )
        }
    }

    override fun execDetailsEnd(reqId: Int) {
        logger.debug { "execDetailsEnd: reqId=$reqId" }
    }

    override fun updateMktDepth(
        tickerId: Int,
        position: Int,
        operation: Int,
        side: Int,
        price: Double,
        size: Decimal,
    ) {
        logger.trace { "updateMktDepth: tickerId=$tickerId" }
    }

    override fun updateMktDepthL2(
        tickerId: Int,
        position: Int,
        marketMaker: String,
        operation: Int,
        side: Int,
        price: Double,
        size: Decimal,
        isSmartDepth: Boolean,
    ) {
        logger.trace { "updateMktDepthL2: tickerId=$tickerId" }
    }

    override fun updateNewsBulletin(
        msgId: Int,
        msgType: Int,
        message: String,
        origExchange: String,
    ) {
        logger.debug { "updateNewsBulletin: msgId=$msgId" }
    }

    override fun managedAccounts(accountsList: String) {
        logger.info { "managedAccounts: $accountsList" }
        accountRegistry.onManagedAccounts(accountsList)
    }

    override fun receiveFA(
        faDataType: Int,
        xml: String,
    ) {
        logger.debug { "receiveFA: faDataType=$faDataType" }
    }

    override fun historicalData(
        reqId: Int,
        bar: Bar,
    ) {
        logger.trace { "historicalData: reqId=$reqId, time=${bar.time()}" }
        twsRaw.debug {
            twsJson(
                "HIST_BAR",
                "reqId" to reqId,
                "time" to bar.time(),
                "open" to bar.open(),
                "high" to bar.high(),
                "low" to bar.low(),
                "close" to bar.close(),
                "vol" to bar.volume().toString(),
                "wap" to bar.wap().toString(),
                "count" to bar.count(),
            )
        }
        historicalRegistry.onHistoricalBar(reqId, bar)
    }

    override fun scannerParameters(xml: String) {
        logger.debug { "scannerParameters received" }
    }

    override fun scannerData(
        reqId: Int,
        rank: Int,
        contractDetails: ContractDetails,
        distance: String,
        benchmark: String,
        projection: String,
        legsStr: String,
    ) {
        logger.debug { "scannerData: reqId=$reqId, rank=$rank" }
    }

    override fun scannerDataEnd(reqId: Int) {
        logger.debug { "scannerDataEnd: reqId=$reqId" }
    }

    override fun realtimeBar(
        reqId: Int,
        time: Long,
        open: Double,
        high: Double,
        low: Double,
        close: Double,
        volume: Decimal,
        wap: Decimal,
        count: Int,
    ) {
        logger.trace { "realtimeBar: reqId=$reqId time=$time close=$close" }
        twsRaw.debug {
            twsJson(
                "REALTIME_BAR",
                "reqId" to reqId,
                "time" to time,
                "open" to open,
                "high" to high,
                "low" to low,
                "close" to close,
                "vol" to volume.toString(),
                "wap" to wap.toString(),
                "count" to count,
            )
        }
        // Real-time bars stream continuously (flag scanner) even between scans — the best live-data
        // heartbeat for the market-data flow signal. See MarketDataHealthTracker.recordLiveTick.
        marketDataHealthTracker.recordLiveTick()
        marketDataRegistry.onRealtimeBar(reqId, time, open, high, low, close, volume.value().toLong(), wap.value().toDouble())
    }

    override fun currentTime(time: Long) {
        logger.debug { "currentTime: $time" }
    }

    override fun fundamentalData(
        reqId: Int,
        data: String,
    ) {
        logger.debug { "fundamentalData: reqId=$reqId" }
    }

    override fun deltaNeutralValidation(
        reqId: Int,
        deltaNeutralContract: DeltaNeutralContract,
    ) {
        logger.debug { "deltaNeutralValidation: reqId=$reqId" }
    }

    override fun tickSnapshotEnd(reqId: Int) {
        logger.trace { "tickSnapshotEnd: reqId=$reqId" }
        marketDataRegistry.onTickSnapshotEnd(reqId)
    }

    override fun marketDataType(
        reqId: Int,
        marketDataType: Int,
    ) {
        logger.debug { "marketDataType: reqId=$reqId, type=$marketDataType" }
    }

    override fun commissionReport(commissionReport: CommissionReport) {
        logger.debug { "commissionReport: ${commissionReport.execId()}" }
    }

    override fun position(
        account: String,
        contract: Contract,
        pos: Decimal,
        avgCost: Double,
    ) {
        logger.debug { "position: account=$account, symbol=${contract.symbol()}, pos=$pos" }
        positionsRegistry.onPosition(account, contract, pos, avgCost)
    }

    override fun positionEnd() {
        logger.debug { "positionEnd" }
        positionsRegistry.onPositionEnd()
    }

    override fun accountSummary(
        reqId: Int,
        account: String,
        tag: String,
        value: String,
        currency: String,
    ) {
        logger.trace { "accountSummary: account=$account, tag=$tag, value=$value" }
    }

    override fun accountSummaryEnd(reqId: Int) {
        logger.debug { "accountSummaryEnd: reqId=$reqId" }
    }

    override fun verifyMessageAPI(apiData: String) {
        logger.debug { "verifyMessageAPI" }
    }

    override fun verifyCompleted(
        isSuccessful: Boolean,
        errorText: String,
    ) {
        logger.debug { "verifyCompleted: success=$isSuccessful" }
    }

    override fun verifyAndAuthMessageAPI(
        apiData: String,
        xyzChallenge: String,
    ) {
        logger.debug { "verifyAndAuthMessageAPI" }
    }

    override fun verifyAndAuthCompleted(
        isSuccessful: Boolean,
        errorText: String,
    ) {
        logger.debug { "verifyAndAuthCompleted: success=$isSuccessful" }
    }

    override fun displayGroupList(
        reqId: Int,
        groups: String,
    ) {
        logger.debug { "displayGroupList: reqId=$reqId" }
    }

    override fun displayGroupUpdated(
        reqId: Int,
        contractInfo: String,
    ) {
        logger.debug { "displayGroupUpdated: reqId=$reqId" }
    }

    override fun error(e: Exception) {
        logger.error(e) { "IBKR error: ${e.message}" }
    }

    override fun error(str: String) {
        logger.error { "IBKR error: $str" }
    }

    override fun error(
        id: Int,
        errorCode: Int,
        errorMsg: String,
        advancedOrderRejectJson: String?,
    ) {
        // Competing-session detection: a TWS/app on the same account from another IP is contending for
        // market data (IBKR message "connected from a different IP address" / competing live session).
        // Surface it via the market-data health signal so the UI can say "log out of TWS".
        if (errorMsg.contains("different IP address", ignoreCase = true) ||
            errorMsg.contains("competing live session", ignoreCase = true)
        ) {
            marketDataHealthTracker.recordCompetingSession()
        }

        // 100 = max messages/sec exceeded, 101 = max market-data lines reached. The admission
        // controller exists to make both impossible — count every occurrence loudly.
        if (errorCode == 100 || errorCode == 101) {
            IbkrAdmissionController.noteBrokerLimitHit(errorCode)
        }

        if (errorCode in listOf(2104, 2106, 2108, 2119, 2158, 2161, 10090, 10167)) {
            logger.info { "IBKR info [id=$id, code=$errorCode]: $errorMsg" }
        } else {
            logger.error { "IBKR error [id=$id, code=$errorCode]: $errorMsg" }
            if (id > 0) {
                historicalRegistry.onError(id, errorCode, errorMsg)
                contractRegistry.onError(id, errorCode, errorMsg)
                marketDataRegistry.onError(id, errorCode, errorMsg)
                orderRegistry.onError(id, errorCode, errorMsg)
                dividendTickRegistry.onError(id, errorCode, errorMsg)
            }
        }
    }

    override fun connectionClosed() {
        logger.warn { "IBKR connection closed" }
        val cause = RuntimeException("IBKR disconnected")
        historicalRegistry.cancelAllPending(cause)
        contractRegistry.cancelAllPending(cause)
        marketDataRegistry.cancelAllPending(cause)
        orderRegistry.cancelAllPending(cause)
        positionsRegistry.cancelPending()
        openOrdersRegistry.cancelPending()
        accountRegistry.onDisconnect()
    }

    override fun connectAck() {
        logger.info { "IBKR connection acknowledged" }
    }

    override fun positionMulti(
        reqId: Int,
        account: String,
        modelCode: String,
        contract: Contract,
        pos: Decimal,
        avgCost: Double,
    ) {
        logger.debug { "positionMulti: reqId=$reqId" }
    }

    override fun positionMultiEnd(reqId: Int) {
        logger.debug { "positionMultiEnd: reqId=$reqId" }
    }

    override fun accountUpdateMulti(
        reqId: Int,
        account: String,
        modelCode: String,
        key: String,
        value: String,
        currency: String,
    ) {
        logger.trace { "accountUpdateMulti: reqId=$reqId, key=$key" }
    }

    override fun accountUpdateMultiEnd(reqId: Int) {
        logger.debug { "accountUpdateMultiEnd: reqId=$reqId" }
    }

    override fun securityDefinitionOptionalParameter(
        reqId: Int,
        exchange: String,
        underlyingConId: Int,
        tradingClass: String,
        multiplier: String,
        expirations: MutableSet<String>,
        strikes: MutableSet<Double>,
    ) {
        logger.info {
            "securityDefinitionOptionalParameter: reqId=$reqId, exchange=$exchange, tradingClass=$tradingClass, multiplier=$multiplier, expirations=${expirations.size}, strikes=${strikes.size}"
        }
        twsRaw.debug {
            twsJson(
                "OPT_PARAMS",
                "reqId" to reqId,
                "exchange" to exchange,
                "tradingClass" to tradingClass,
                "multiplier" to multiplier,
                "expirations" to expirations.sorted(),
                "strikes" to strikes.sorted(),
            )
        }
        contractRegistry.onSecurityDefinitionOptionalParameter(reqId, exchange, tradingClass, multiplier, expirations, strikes)
    }

    override fun securityDefinitionOptionalParameterEnd(reqId: Int) {
        logger.debug { "securityDefinitionOptionalParameterEnd: reqId=$reqId" }
        contractRegistry.onSecurityDefinitionOptionalParameterEnd(reqId)
    }

    override fun softDollarTiers(
        reqId: Int,
        tiers: Array<out SoftDollarTier>,
    ) {
        logger.debug { "softDollarTiers: reqId=$reqId" }
    }

    override fun familyCodes(familyCodes: Array<out FamilyCode>) {
        logger.debug { "familyCodes: ${familyCodes.size} codes" }
    }

    override fun symbolSamples(
        reqId: Int,
        contractDescriptions: Array<out ContractDescription>,
    ) {
        logger.debug { "symbolSamples: reqId=$reqId, count=${contractDescriptions.size}" }
    }

    override fun historicalDataEnd(
        reqId: Int,
        startDateStr: String,
        endDateStr: String,
    ) {
        logger.debug { "historicalDataEnd: reqId=$reqId, start=$startDateStr, end=$endDateStr" }
        historicalRegistry.onHistoricalDataEnd(reqId)
    }

    override fun mktDepthExchanges(depthMktDataDescriptions: Array<out DepthMktDataDescription>) {
        logger.debug { "mktDepthExchanges: ${depthMktDataDescriptions.size} exchanges" }
    }

    override fun tickNews(
        tickerId: Int,
        timeStamp: Long,
        providerCode: String,
        articleId: String,
        headline: String,
        extraData: String,
    ) {
        logger.debug { "tickNews: tickerId=$tickerId" }
    }

    override fun smartComponents(
        reqId: Int,
        theMap: MutableMap<Int, MutableMap.MutableEntry<String, Char>>,
    ) {
        logger.debug { "smartComponents: reqId=$reqId" }
    }

    override fun tickReqParams(
        tickerId: Int,
        minTick: Double,
        bboExchange: String?,
        snapshotPermissions: Int,
    ) {
        logger.trace { "tickReqParams: tickerId=$tickerId" }
    }

    override fun newsProviders(newsProviders: Array<out NewsProvider>) {
        logger.debug { "newsProviders: ${newsProviders.size} providers" }
    }

    override fun newsArticle(
        requestId: Int,
        articleType: Int,
        articleText: String,
    ) {
        logger.debug { "newsArticle: requestId=$requestId" }
    }

    override fun historicalNews(
        requestId: Int,
        time: String,
        providerCode: String,
        articleId: String,
        headline: String,
    ) {
        logger.debug { "historicalNews: requestId=$requestId" }
    }

    override fun historicalNewsEnd(
        requestId: Int,
        hasMore: Boolean,
    ) {
        logger.debug { "historicalNewsEnd: requestId=$requestId" }
    }

    override fun headTimestamp(
        reqId: Int,
        headTimestamp: String,
    ) {
        logger.debug { "headTimestamp: reqId=$reqId, timestamp=$headTimestamp" }
    }

    override fun histogramData(
        reqId: Int,
        items: MutableList<HistogramEntry>,
    ) {
        logger.debug { "histogramData: reqId=$reqId, items=${items.size}" }
    }

    override fun historicalDataUpdate(
        reqId: Int,
        bar: Bar,
    ) {
        logger.trace { "historicalDataUpdate: reqId=$reqId" }
    }

    override fun rerouteMktDataReq(
        reqId: Int,
        conId: Int,
        exchange: String,
    ) {
        logger.debug { "rerouteMktDataReq: reqId=$reqId" }
    }

    override fun rerouteMktDepthReq(
        reqId: Int,
        conId: Int,
        exchange: String,
    ) {
        logger.debug { "rerouteMktDepthReq: reqId=$reqId" }
    }

    override fun marketRule(
        marketRuleId: Int,
        priceIncrements: Array<out PriceIncrement>,
    ) {
        logger.debug { "marketRule: marketRuleId=$marketRuleId increments=${priceIncrements.size}" }
        contractRegistry.onMarketRule(marketRuleId, priceIncrements.toList())
    }

    override fun pnl(
        reqId: Int,
        dailyPnL: Double,
        unrealizedPnL: Double,
        realizedPnL: Double,
    ) {
        logger.debug { "pnl: reqId=$reqId, daily=$dailyPnL" }
    }

    override fun pnlSingle(
        reqId: Int,
        pos: Decimal,
        dailyPnL: Double,
        unrealizedPnL: Double,
        realizedPnL: Double,
        value: Double,
    ) {
        pnlRegistry.onPnlSingle(reqId, unrealizedPnL)
    }

    override fun historicalTicks(
        reqId: Int,
        ticks: MutableList<HistoricalTick>,
        done: Boolean,
    ) {
        logger.debug { "historicalTicks: reqId=$reqId, count=${ticks.size}, done=$done" }
    }

    override fun historicalTicksBidAsk(
        reqId: Int,
        ticks: MutableList<HistoricalTickBidAsk>,
        done: Boolean,
    ) {
        logger.debug { "historicalTicksBidAsk: reqId=$reqId, count=${ticks.size}, done=$done" }
    }

    override fun historicalTicksLast(
        reqId: Int,
        ticks: MutableList<HistoricalTickLast>,
        done: Boolean,
    ) {
        logger.debug { "historicalTicksLast: reqId=$reqId, count=${ticks.size}, done=$done" }
    }

    override fun tickByTickAllLast(
        reqId: Int,
        tickType: Int,
        time: Long,
        price: Double,
        size: Decimal,
        tickAttribLast: TickAttribLast,
        exchange: String,
        specialConditions: String,
    ) {
        logger.trace { "tickByTickAllLast: reqId=$reqId, price=$price" }
    }

    override fun tickByTickBidAsk(
        reqId: Int,
        time: Long,
        bidPrice: Double,
        askPrice: Double,
        bidSize: Decimal,
        askSize: Decimal,
        tickAttribBidAsk: TickAttribBidAsk,
    ) {
        logger.trace { "tickByTickBidAsk: reqId=$reqId, bid=$bidPrice, ask=$askPrice" }
        twsRaw.debug {
            twsJson(
                "TICK_BIDASK",
                "reqId" to reqId,
                "time" to time,
                "bid" to bidPrice,
                "ask" to askPrice,
                "bidSize" to bidSize.toString(),
                "askSize" to askSize.toString(),
            )
        }
        marketDataRegistry.onTickByTickBidAsk(reqId, TickByTickBidAsk(time, bidPrice, askPrice))
    }

    override fun tickByTickMidPoint(
        reqId: Int,
        time: Long,
        midPoint: Double,
    ) {
        logger.trace { "tickByTickMidPoint: reqId=$reqId, midPoint=$midPoint" }
    }

    override fun orderBound(
        orderId: Long,
        apiClientId: Int,
        apiOrderId: Int,
    ) {
        logger.debug { "orderBound: orderId=$orderId" }
    }

    override fun completedOrder(
        contract: Contract,
        order: Order,
        orderState: OrderState,
    ) {
        logger.debug { "completedOrder: symbol=${contract.symbol()}" }
    }

    override fun completedOrdersEnd() {
        logger.debug { "completedOrdersEnd" }
    }

    override fun replaceFAEnd(
        reqId: Int,
        text: String,
    ) {
        logger.debug { "replaceFAEnd: reqId=$reqId" }
    }

    override fun wshMetaData(
        reqId: Int,
        dataJson: String,
    ) {
        logger.debug { "wshMetaData: reqId=$reqId" }
    }

    override fun wshEventData(
        reqId: Int,
        dataJson: String,
    ) {
        logger.debug { "wshEventData: reqId=$reqId" }
    }

    override fun historicalSchedule(
        reqId: Int,
        startDateTime: String,
        endDateTime: String,
        timeZone: String,
        sessions: MutableList<HistoricalSession>,
    ) {
        logger.debug { "historicalSchedule: reqId=$reqId" }
    }

    override fun userInfo(
        reqId: Int,
        whiteBrandingId: String,
    ) {
        logger.debug { "userInfo: reqId=$reqId" }
    }
}
