package cz.solvina.options.adapters.outbound.ibkr.account

import cz.solvina.options.domain.features.account.AccountPosition
import cz.solvina.options.domain.features.account.PositionsPort
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

@Component
class IbkrPositionsAdapter(
    private val registry: IbkrPositionsRegistry,
) : PositionsPort {
    override suspend fun getPositions(): List<AccountPosition> =
        registry
            .getPositions()
            .also { pos ->
                logger.info { "Retrieved positions: ${pos.size}" }
            }
}
