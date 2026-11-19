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
    /** Plain candidate email (AI review off, or no review possible), deduplicated per spread. */
    suspend fun notify(opportunity: SpreadOpportunity)

    /**
     * The candidate email with its AI review section, sent once per review task. [threadKey]
     * becomes the Message-ID so a later [notifyReviewFollowUp] lands in the same thread.
     */
    suspend fun notifyReviewed(
        opportunity: SpreadOpportunity,
        review: ReviewNote,
        threadKey: String,
    ) = notify(opportunity)

    /** Short reply in the candidate's thread: the verdict that arrived after the email went out. */
    suspend fun notifyReviewFollowUp(
        opportunity: SpreadOpportunity,
        review: ReviewNote,
        threadKey: String,
    ) = Unit

    /** Default for tests that need not supply a notifier. */
    object NoOp : OpportunityNotificationPort {
        override suspend fun notify(opportunity: SpreadOpportunity) = Unit
    }
}
