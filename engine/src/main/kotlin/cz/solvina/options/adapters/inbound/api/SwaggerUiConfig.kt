package cz.solvina.options.adapters.inbound.api

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.ClassPathResource
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.RouterFunctions
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.router
import java.net.URI

@Configuration
class SwaggerUiConfig(
    @Value("\${spring.webflux.base-path:}") private val basePath: String,
) {
    @Bean
    fun swaggerUiRouter(): RouterFunction<ServerResponse> {
        val initializerJs = buildInitializerJs()
        return router {
            GET("/swagger-ui.html") {
                ServerResponse.permanentRedirect(URI.create("swagger-ui/index.html")).build()
            }
            GET("/swagger-ui/swagger-initializer.js") {
                ServerResponse
                    .ok()
                    .contentType(MediaType.parseMediaType("application/javascript"))
                    .bodyValue(initializerJs)
            }
        }.and(
            RouterFunctions.resources("/swagger-ui/**", ClassPathResource("META-INF/resources/webjars/swagger-ui/$SWAGGER_UI_VERSION/")),
        )
    }

    private fun buildInitializerJs() =
        """
        window.onload = function() {
          window.ui = SwaggerUIBundle({
            url: "$basePath/v3/api-docs",
            dom_id: '#swagger-ui',
            deepLinking: true,
            presets: [SwaggerUIBundle.presets.apis, SwaggerUIStandalonePreset],
            plugins: [SwaggerUIBundle.plugins.DownloadUrl],
            layout: "StandaloneLayout"
          })
        }
        """.trimIndent()

    companion object {
        private const val SWAGGER_UI_VERSION = "5.32.2"
    }
}
