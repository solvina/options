package cz.solvina.options.domain.features.spread.strategy.bullput

import cz.solvina.options.domain.features.spread.model.StrategyId
import cz.solvina.options.domain.features.spread.strategy.SpreadStrategy
import org.springframework.stereotype.Component

/**
 * Bull put spread strategy. Entries come from the (currently bull-put) scanner and exits are fully
 * covered by the shared TP/SL/DTE rules, so it contributes no strategy-specific exit — it inherits
 * the null default. It exists so the core can resolve [StrategyId.BULL_PUT] through the registry
 * like any other strategy, with no special-casing.
 */
@Component
class BullPutStrategy : SpreadStrategy {
    override val id = StrategyId.BULL_PUT
}
