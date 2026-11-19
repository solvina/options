package cz.solvina.options.domain.features.notification

/**
 * What the candidate email says about the AI review (AI review D5). Built by the review
 * dispatcher from the primary reviewer's stored result; rendering only, no trading effect.
 */
data class ReviewNote(
    val reviewerId: String,
    /** APPROVE / VETO / ABSTAIN; null when no verdict is available (see [unavailableReason]). */
    val verdict: String?,
    val confidence: Double? = null,
    val reason: String? = null,
    val risks: List<ReviewRisk> = emptyList(),
    val sources: List<ReviewSource> = emptyList(),
    val newsSummary: String? = null,
    val model: String? = null,
    /** The verdict arrived after the decision it was meant for. */
    val late: Boolean = false,
    val unavailableReason: String? = null,
    /** One line per non-primary reviewer that has answered, e.g. "openai-gpt: VETO (0.70)". */
    val otherReviewers: List<String> = emptyList(),
)

data class ReviewRisk(
    val category: String,
    val severity: String,
    val description: String,
)

data class ReviewSource(
    val title: String,
    val url: String? = null,
    val publishedAt: String? = null,
)
