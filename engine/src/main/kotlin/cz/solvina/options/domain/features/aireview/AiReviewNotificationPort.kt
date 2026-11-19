package cz.solvina.options.domain.features.aireview

/**
 * Where verdicts are announced besides the email (AI review D5): the Telegram "AI reviews"
 * topic, separate from engine alerts. Best-effort and non-throwing.
 */
fun interface AiReviewNotificationPort {
    suspend fun announce(text: String)

    companion object {
        val NoOp = AiReviewNotificationPort { }
    }
}
