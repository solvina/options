package cz.solvina.options.domain.features.notification

/**
 * Outbound port for rich trade-opportunity notifications (email). Separate from the operational
 * [cz.solvina.options.domain.features.alert.AlertPort] (INFO/WARN/CRITICAL ops noise) because a
 * spread candidate is structured, per-trade content rather than an operational event.
 *
 * Implementations must be best-effort and non-throwing — a failed notification must never break
 * or delay the scan loop.
 */
interface OpportunityNotificationPort {
    suspend fun notify(opportunity: SpreadOpportunity)

    /** Default wired into the candidate selectors so unit tests need not supply a notifier. */
    object NoOp : OpportunityNotificationPort {
        override suspend fun notify(opportunity: SpreadOpportunity) = Unit
    }
}
