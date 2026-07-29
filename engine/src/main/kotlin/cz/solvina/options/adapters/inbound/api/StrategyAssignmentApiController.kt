package cz.solvina.options.adapters.inbound.api

import cz.solvina.options.domain.features.bars.Timeframe
import cz.solvina.options.domain.features.strategy.StrategyParams
import cz.solvina.options.domain.features.strategy.StrategyRegistry
import cz.solvina.options.domain.features.strategy.assignment.StrategyAssignment
import cz.solvina.options.domain.features.strategy.assignment.StrategyAssignmentPort
import cz.solvina.options.domain.features.strategy.tuning.StrategySymbolParamsPort
import cz.solvina.options.domain.models.Symbol
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

private val logger = KotlinLogging.logger {}

/**
 * CRUD for strategy assignments — the managed layer: which strategy trades which symbol, and with
 * which parameter overrides.
 *
 * Overrides are validated against the strategy's own descriptors on write, not on read. An
 * assignment that reaches the live runner has therefore already been checked; the runner never has
 * to decide what to do with a parameter that does not exist.
 *
 * Since v38 the overrides themselves are stored in `strategy_symbol_params`, not on the assignment
 * row: the flag strategy is tuned per symbol without ever having an assignment, and two storage
 * paths for one concept is what made "which parameters is this actually running with" hard to
 * answer. The DTO still carries `params` so the screen is unchanged — only where they land moved.
 *
 * Path has no /api prefix — both proxies rewrite /api/X to /options/X (see StockBacktestApiController).
 */
@RestController
@RequestMapping("/strategy-assignments")
class StrategyAssignmentApiController(
    private val assignments: StrategyAssignmentPort,
    private val strategies: StrategyRegistry,
    private val symbolParams: StrategySymbolParamsPort,
) {
    data class AssignmentDto(
        val id: UUID?,
        val strategyId: String,
        val symbol: String,
        val timeframe: String?,
        val params: Map<String, Any?>?,
        val enabled: Boolean?,
        val createdAt: Instant?,
        val updatedAt: Instant?,
    )

    @GetMapping
    suspend fun list(): List<AssignmentDto> = assignments.findAll().map { it.toDto(overridesOf(it)) }

    @GetMapping("/{id}")
    suspend fun get(
        @PathVariable id: UUID,
    ): ResponseEntity<AssignmentDto> =
        assignments.findById(id)?.let { ResponseEntity.ok(it.toDto(overridesOf(it))) } ?: ResponseEntity.notFound().build()

    @PostMapping
    suspend fun create(
        @RequestBody dto: AssignmentDto,
    ): ResponseEntity<Any> = upsert(dto, UUID.randomUUID(), HttpStatus.CREATED)

    @PutMapping("/{id}")
    suspend fun update(
        @PathVariable id: UUID,
        @RequestBody dto: AssignmentDto,
    ): ResponseEntity<Any> {
        if (assignments.findById(id) == null) return ResponseEntity.notFound().build()
        return upsert(dto, id, HttpStatus.OK)
    }

    @DeleteMapping("/{id}")
    fun delete(
        @PathVariable id: UUID,
    ): ResponseEntity<Any> = if (assignments.delete(id)) ResponseEntity.noContent().build() else ResponseEntity.notFound().build()

    private suspend fun upsert(
        dto: AssignmentDto,
        id: UUID,
        okStatus: HttpStatus,
    ): ResponseEntity<Any> {
        val strategy = strategies.find(dto.strategyId) ?: return reject("unknown strategy '${dto.strategyId}'")
        val symbol = dto.symbol.trim().uppercase()
        if (symbol.isEmpty()) return reject("symbol is required")

        val timeframe =
            runCatching { Timeframe.fromLabel(dto.timeframe ?: Timeframe.DAILY.label) }
                .getOrElse { return reject("unknown timeframe '${dto.timeframe}'") }
        if (timeframe !in strategy.inputs.timeframes) {
            return reject(
                "${strategy.id} declares ${strategy.inputs.timeframes.map { it.label }}, not ${timeframe.label}",
            )
        }

        // Resolve the overrides through the descriptors: an unknown name or an out-of-range value
        // fails here, where a human is watching, instead of at 09:05 in the runner.
        dto.params?.let { overrides ->
            val resolved =
                runCatching { StrategyParams.resolve(strategy.params, overrides) }
                    .getOrElse { return reject("${strategy.id}: ${it.message}") }
            strategy.validate(resolved)?.let { return reject("${strategy.id}: $it") }
        }

        val saved =
            runCatching {
                assignments.save(
                    StrategyAssignment(
                        id = id,
                        strategyId = strategy.id,
                        symbol = Symbol(symbol),
                        timeframe = timeframe,
                        enabled = dto.enabled ?: false,
                        createdAt = Instant.now(),
                        updatedAt = Instant.now(),
                    ),
                )
            }.getOrElse { return reject(it.message ?: "could not save assignment") }
        // Params are written after the assignment so a rejected assignment cannot leave orphaned
        // tuning behind. A null params field means "leave whatever is stored alone", not "clear it" —
        // clearing is the explicit DELETE on the tuning endpoint.
        dto.params?.let { symbolParams.upsert(strategy.id, symbol, it, timeframe.label) }
        return ResponseEntity.status(okStatus).body(saved.toDto(overridesOf(saved)))
    }

    private fun reject(reason: String): ResponseEntity<Any> {
        logger.warn { "Assignment rejected: $reason" }
        return ResponseEntity.badRequest().body<Any>(mapOf("error" to reason))
    }

    private suspend fun overridesOf(assignment: StrategyAssignment): Map<String, Any?>? =
        symbolParams.get(assignment.strategyId, assignment.symbol.value, assignment.timeframe.label)

    private fun StrategyAssignment.toDto(overrides: Map<String, Any?>?) =
        AssignmentDto(
            id = id,
            strategyId = strategyId,
            symbol = symbol.value,
            timeframe = timeframe.label,
            params = overrides,
            enabled = enabled,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
}
