package cz.solvina.options.adapters.outbound.alert

import com.fasterxml.jackson.databind.ObjectMapper
import cz.solvina.options.domain.features.aireview.AiReviewConfig
import cz.solvina.options.domain.features.aireview.AiReviewNotificationPort
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

private val logger = KotlinLogging.logger {}

/**
 * Posts AI verdicts into their own forum topic (`ai-review.telegram-message-thread-id`), with the
 * same bot and chat as engine alerts but kept out of the alerts topic. No-op when the alert bot is
 * unconfigured or no topic is set. Verdicts are a few a day, so a simple serialized send with
 * the per-chat spacing is enough; the token is never logged.
 */
@Component
class TelegramAiReviewNotifier(
    private val telegram: TelegramAlertConfig,
    private val aiReview: AiReviewConfig,
    private val objectMapper: ObjectMapper,
) : AiReviewNotificationPort {
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    private val lock = Mutex()

    override suspend fun announce(text: String) {
        val thread = aiReview.telegramMessageThreadId.trim().takeIf { it.matches(Regex("-?\\d+")) }
        if (!telegram.enabled || telegram.botToken.isBlank() || telegram.chatId.isBlank() || thread == null) {
            logger.debug { "AI review Telegram message suppressed (unconfigured)" }
            return
        }
        val payload =
            objectMapper.writeValueAsString(
                mapOf(
                    "chat_id" to telegram.chatId,
                    "message_thread_id" to thread.toLong(),
                    "text" to text.take(MAX_LENGTH),
                    "disable_web_page_preview" to true,
                ),
            )
        lock.withLock {
            runCatching {
                withContext(Dispatchers.IO) {
                    val request =
                        HttpRequest
                            .newBuilder()
                            .uri(URI.create("https://api.telegram.org/bot${telegram.botToken}/sendMessage"))
                            .timeout(Duration.ofSeconds(10))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .build()
                    val response = http.send(request, HttpResponse.BodyHandlers.ofString())
                    if (response.statusCode() !in 200..299) {
                        logger.warn { "AI review Telegram message failed: HTTP ${response.statusCode()} ${response.body().take(300)}" }
                    }
                }
            }.onFailure { logger.warn { "AI review Telegram message failed: ${it.message}" } }
            delay(MIN_INTERVAL_MS)
        }
    }

    private companion object {
        const val MIN_INTERVAL_MS = 1_100L
        const val MAX_LENGTH = 4_000 // Telegram caps a message at 4096 characters
    }
}
