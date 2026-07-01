package cz.solvina.options.adapters.outbound.notification

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Email notification configuration (`notifications.email.*`). Credentials are injected from the
 * environment (the same `.env` the bot/engine use) — never committed. When disabled or
 * unconfigured the [SmtpOpportunityNotifier] is a no-op, so the engine runs fine without it wired.
 *
 * [password] is a Gmail **App Password** (16 chars, 2FA required), not the account password.
 */
@ConfigurationProperties("notifications.email")
data class EmailNotificationConfig(
    val enabled: Boolean = false,
    val smtpHost: String = "smtp.gmail.com",
    val smtpPort: Int = 587,
    val username: String = "",
    val password: String = "",
    val from: String = "",
    val to: String = "",
)
