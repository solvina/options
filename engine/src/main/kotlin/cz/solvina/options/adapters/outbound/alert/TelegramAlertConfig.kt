package cz.solvina.options.adapters.outbound.alert

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Telegram alert configuration. Token/chat are injected from the environment (the same
 * `.env` the bot uses) — never committed. When disabled or unconfigured the adapter is a
 * no-op, so the engine runs fine without alerting wired.
 */
@ConfigurationProperties("alerts.telegram")
data class TelegramAlertConfig(
    val enabled: Boolean = false,
    val botToken: String = "",
    val chatId: String = "",
    /**
     * Optional forum-topic thread id to post alerts into (the "engine alerts" topic). Blank posts to
     * the group's General thread. Kept as a String so an unset env var is simply blank, not a parse error.
     */
    val messageThreadId: String = "",
)
