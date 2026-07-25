package cz.solvina.options.adapters.outbound.ibkr.account

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

@Component
class IbkrOpenOrdersAdapter(
    private val registry: IbkrOrdersRegistry,
) {
    suspend fun getOpenOrders(): List<OpenOrder> = registry.getOpenOrders()
}
