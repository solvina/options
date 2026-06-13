package cz.solvina.options.domain.features.order

/**
 * Thrown by the leg-by-leg execution path when the protective LONG leg filled but the SHORT leg
 * did not, and the [unwind][cz.solvina.options.adapters.outbound.ibkr.IbkrConnectionConfig.unwindStrandedLongLeg]
 * switch is OFF — so a paid-for long put remains open and must be made visible for manual handling.
 *
 * Critically this is NEVER a naked short: the long is submitted and confirmed first, so the worst
 * case carried here is a bounded long-debit position, not unhedged short exposure.
 */
class StrandedLongLegException(
    val longOrderId: Int,
    message: String,
) : IllegalStateException(message)
