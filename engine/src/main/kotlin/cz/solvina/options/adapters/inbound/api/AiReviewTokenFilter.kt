package cz.solvina.options.adapters.inbound.api

import cz.solvina.options.domain.features.aireview.AiReviewConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import java.security.MessageDigest

private val logger = KotlinLogging.logger {}

/**
 * Bearer-token check for the AI reviewer routes (every POST/PUT under /ai-review/). The engine API
 * has no other authentication and is reachable on the LAN (`*:8081`, nginx `/api/`), so without
 * this anyone there could post verdicts. A blank `ai-review.api-token` refuses every reviewer
 * call. Read routes stay as open as the rest of the API and never return snapshots.
 */
@Component
class AiReviewTokenFilter(
    private val config: AiReviewConfig,
) : WebFilter {
    override fun filter(
        exchange: ServerWebExchange,
        chain: WebFilterChain,
    ): Mono<Void> {
        val request = exchange.request
        val path = request.path.pathWithinApplication().value()
        val guarded = path.startsWith(PREFIX) && (request.method == HttpMethod.POST || request.method == HttpMethod.PUT)
        if (!guarded) return chain.filter(exchange)

        val presented =
            request.headers
                .getFirst("Authorization")
                ?.removePrefix("Bearer ")
                ?.trim()
                .orEmpty()
        val expected = config.apiToken
        val ok =
            expected.isNotBlank() &&
                MessageDigest.isEqual(presented.toByteArray(), expected.toByteArray())
        if (ok) return chain.filter(exchange)

        logger.warn { "AI review call refused (${request.method} $path from ${request.remoteAddress?.address?.hostAddress})" }
        exchange.response.statusCode = HttpStatus.UNAUTHORIZED
        return exchange.response.setComplete()
    }

    private companion object {
        const val PREFIX = "/ai-review/"
    }
}
