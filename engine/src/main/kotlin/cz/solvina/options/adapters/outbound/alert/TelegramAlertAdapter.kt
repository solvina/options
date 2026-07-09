package cz.solvina.options.adapters.outbound.alert

import cz.solvina.options.domain.features.alert.AlertLevel
import cz.solvina.options.domain.features.alert.AlertPort
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

private val logger = KotlinLogging.logger {}

/**
 * Sends alerts to Telegram via the Bot API. No-op when disabled or unconfigured.
 *
 * SECURITY: the bot token appears in the request URL (Telegram's API design). This adapter
 * never logs the URL or token — only the alert level/title — to avoid the token leaking into
 * journald (see TELEGRAM_BOT.md note about httpx leaking token URLs).
 */
@Component
class TelegramAlertAdapter(
    private val config: TelegramAlertConfig,
) : AlertPort {
    private val http: HttpClient =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    override suspend fun send(
        level: AlertLevel,
        title: String,
        body: String,
    ) {
        if (!config.enabled || config.botToken.isBlank() || config.chatId.isBlank()) {
            logger.debug { "Alert suppressed (telegram disabled/unconfigured): [$level] $title" }
            return
        }

        val text = "${level.emoji} $title\n\n$body"
        // Route into the alerts topic when a thread id is configured; the numeric check keeps a blank
        // (or stray non-numeric) env var posting to the group's General thread and can't corrupt the JSON.
        val threadField =
            config.messageThreadId
                .trim()
                .takeIf { it.matches(Regex("-?\\d+")) }
                ?.let { ""","message_thread_id":$it""" }
                ?: ""
        val payload =
            """{"chat_id":${jsonString(config.chatId)}$threadField,"text":${jsonString(text)},"disable_web_page_preview":true}"""

        try {
            withContext(Dispatchers.IO) {
                val request =
                    HttpRequest
                        .newBuilder()
                        .uri(URI.create("https://api.telegram.org/bot${config.botToken}/sendMessage"))
                        .timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(payload))
                        .build()
                val response = http.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() !in 200..299) {
                    // Body may echo the request but never the token; safe to log.
                    logger.warn { "Telegram alert [$level] '$title' failed: HTTP ${response.statusCode()} ${response.body()}" }
                }
            }
        } catch (e: Exception) {
            logger.warn { "Telegram alert [$level] '$title' failed: ${e.message}" }
        }
    }

    /** Minimal JSON string escaping (quotes, backslash, control chars). */
    private fun jsonString(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        return sb.append("\"").toString()
    }
}
