package cz.solvina.options.adapters.outbound.ibkr.order

import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrOrderRegistry
import cz.solvina.options.domain.features.order.BrokerOrderUpdate
import cz.solvina.options.domain.features.order.OrderLifecyclePort
import kotlinx.coroutines.flow.Flow
import org.springframework.stereotype.Component

@Component
class IbkrOrderLifecycleAdapter(
    private val registry: IbkrOrderRegistry,
) : OrderLifecyclePort {
    override val updates: Flow<BrokerOrderUpdate> = registry.updates

    override fun current(orderId: Int): BrokerOrderUpdate? = registry.current(orderId)
}
