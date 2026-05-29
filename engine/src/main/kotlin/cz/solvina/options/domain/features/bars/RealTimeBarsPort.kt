package cz.solvina.options.domain.features.bars

import cz.solvina.options.domain.models.Symbol
import kotlinx.coroutines.flow.Flow

interface RealTimeBarsPort {
    /** Emits a [RealTimeBar] every 5 seconds for [symbol] while the flow is collected. */
    fun streamBars(symbol: Symbol): Flow<RealTimeBar>
}
