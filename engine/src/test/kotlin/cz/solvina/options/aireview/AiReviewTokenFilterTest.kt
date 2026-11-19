package cz.solvina.options.aireview

import cz.solvina.options.adapters.inbound.api.AiReviewTokenFilter
import cz.solvina.options.domain.features.aireview.AiReviewConfig
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AiReviewTokenFilterTest {
    private fun run(
        token: String,
        request: MockServerHttpRequest,
    ): Pair<Boolean, HttpStatus?> {
        var passed = false
        val exchange = MockServerWebExchange.from(request)
        val chain =
            WebFilterChain {
                passed = true
                Mono.empty()
            }
        AiReviewTokenFilter(AiReviewConfig(apiToken = token)).filter(exchange, chain).block()
        return passed to exchange.response.statusCode?.let { HttpStatus.valueOf(it.value()) }
    }

    private fun claim(auth: String? = null) =
        MockServerHttpRequest
            .post("/ai-review/tasks/claim")
            .apply { if (auth != null) header("Authorization", auth) }
            .build()

    @Test
    fun `reviewer calls need the bearer token`() {
        assertTrue(run("secret", claim("Bearer secret")).first)

        val (passed, status) = run("secret", claim("Bearer wrong"))
        assertFalse(passed)
        assertEquals(HttpStatus.UNAUTHORIZED, status)
        assertFalse(run("secret", claim()).first)
    }

    @Test
    fun `a blank configured token refuses every reviewer call`() {
        assertFalse(run("", claim("Bearer ")).first)
    }

    @Test
    fun `read routes and other APIs are not affected`() {
        assertTrue(run("secret", MockServerHttpRequest.get("/ai-review/reviews").build()).first)
        assertTrue(run("secret", MockServerHttpRequest.post("/universe/NUE/toggle").build()).first)
    }
}
