package cz.solvina.options.adapters.outbound.ibkr.order

import com.ib.client.EClientSocket
import cz.solvina.options.adapters.outbound.ibkr.IbkrConnectionConfig
import cz.solvina.options.adapters.outbound.ibkr.cache.IbkrContractCache
import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrOrderRegistry
import cz.solvina.options.domain.models.Money
import cz.solvina.options.domain.models.OptionContract
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

/**
 * Routes spread orders to the appropriate exchange strategy
 *
 * Different exchanges require different order submission approaches:
 * - US: CBOE, ISE, AMEX → Native combo orders (atomic)
 * - EU: EUREX → Leg-by-leg submission with position matching
 * - Asia: Future expansion
 */
@Component
class ExchangeStrategyRouter(
    private val registry: IbkrOrderRegistry,
    private val client: EClientSocket,
    private val contractCache: IbkrContractCache,
    private val connectionConfig: IbkrConnectionConfig,
) {
    private val strategies = mutableMapOf<String, OrderExecutionStrategy>()

    init {
        // Register native combo strategies (US exchanges)
        registerStrategy(NativeComboOrderStrategy("CBOE", registry, client, contractCache, connectionConfig))
        registerStrategy(NativeComboOrderStrategy("ISE", registry, client, contractCache, connectionConfig))
        registerStrategy(NativeComboOrderStrategy("AMEX", registry, client, contractCache, connectionConfig))
        registerStrategy(NativeComboOrderStrategy("SMART", registry, client, contractCache, connectionConfig)) // SMART routes to best

        // Register leg-by-leg strategies (EU exchanges without native combo support)
        registerStrategy(LegByLegOrderStrategy("DTB", registry, client, contractCache, connectionConfig)) // Deutsche Börse
        registerStrategy(LegByLegOrderStrategy("EUREX", registry, client, contractCache, connectionConfig)) // EUREX
        registerStrategy(LegByLegOrderStrategy("FTA", registry, client, contractCache, connectionConfig)) // Frankfurt
        registerStrategy(LegByLegOrderStrategy("EBS", registry, client, contractCache, connectionConfig)) // EU derivatives
    }

    private fun registerStrategy(strategy: OrderExecutionStrategy) {
        strategies[strategy.getExchangeId()] = strategy
        logger.info { "Registered order strategy for ${strategy.getExchangeId()}: ${strategy.notes()}" }
    }

    /**
     * Route a spread order to the appropriate execution strategy based on exchange
     *
     * @return Result containing order IDs and execution metadata
     */
    suspend fun submitSpreadOrder(
        soldContract: OptionContract,
        boughtContract: OptionContract,
        netCredit: Money,
        qty: Int,
        exchange: String = "SMART",
    ): OrderSubmissionResult {
        val strategy =
            strategies[exchange]
                ?: run {
                    logger.warn { "No strategy registered for exchange '$exchange', falling back to SMART (native combo)" }
                    strategies["SMART"] ?: error("SMART strategy not registered")
                }

        logger.info { "[$exchange] Using strategy: ${strategy.notes()}" }

        return strategy.submitSpreadOrder(soldContract, boughtContract, netCredit, qty)
    }

    /**
     * Get strategy details for logging/debugging
     */
    fun getStrategyInfo(exchange: String): String = strategies[exchange]?.notes() ?: "Strategy not found for $exchange"

    /**
     * List all registered strategies
     */
    fun listStrategies(): Map<String, String> = strategies.mapValues { (_, strategy) -> strategy.notes() }
}
