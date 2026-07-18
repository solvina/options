package cz.solvina.options.adapters.inbound.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import cz.solvina.options.adapters.outbound.persistence.postgres.entity.BacktestConfigEntity
import cz.solvina.options.adapters.outbound.persistence.postgres.repository.BacktestConfigRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

/** CRUD for named backtest parameter presets (save / recall / delete). Payload is opaque form JSON. */
// Mapped without the /api prefix: the proxies rewrite /api/X → /options/X (see StockBacktestApiController).
@RestController
@RequestMapping("/backtest/configs")
class BacktestConfigController(
    private val repo: BacktestConfigRepository,
    private val mapper: ObjectMapper,
) {
    data class ConfigDto(
        val id: String,
        val name: String,
        val payload: JsonNode,
        val createdAt: String,
    )

    data class SaveRequest(
        val name: String,
        val payload: JsonNode,
    )

    @GetMapping
    suspend fun list(): List<ConfigDto> =
        withContext(Dispatchers.IO) {
            repo.findAllByOrderByCreatedAtDesc().map { it.toDto() }
        }

    @PostMapping
    suspend fun save(
        @RequestBody req: SaveRequest,
    ): ResponseEntity<ConfigDto> =
        withContext(Dispatchers.IO) {
            val trimmed = req.name.trim()
            if (trimmed.isEmpty()) return@withContext ResponseEntity.badRequest().build()
            // Upsert by name so re-saving a preset overwrites rather than duplicating.
            val entity =
                repo.findByName(trimmed)?.apply { payloadJson = mapper.writeValueAsString(req.payload) }
                    ?: BacktestConfigEntity(
                        name = trimmed,
                        payloadJson = mapper.writeValueAsString(req.payload),
                        createdAt = Instant.now(),
                    )
            ResponseEntity.ok(repo.save(entity).toDto())
        }

    @DeleteMapping("/{id}")
    suspend fun delete(
        @PathVariable id: String,
    ): ResponseEntity<Unit> =
        withContext(Dispatchers.IO) {
            runCatching { repo.deleteById(UUID.fromString(id)) }
            ResponseEntity.noContent().build()
        }

    private fun BacktestConfigEntity.toDto() = ConfigDto(id.toString(), name, mapper.readTree(payloadJson), createdAt.toString())
}
