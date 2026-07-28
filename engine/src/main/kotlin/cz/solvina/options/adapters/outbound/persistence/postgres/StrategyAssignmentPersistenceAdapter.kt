package cz.solvina.options.adapters.outbound.persistence.postgres

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import cz.solvina.options.adapters.outbound.persistence.postgres.entity.StrategyAssignmentEntity
import cz.solvina.options.adapters.outbound.persistence.postgres.repository.StrategyAssignmentRepository
import cz.solvina.options.domain.features.bars.Timeframe
import cz.solvina.options.domain.features.strategy.assignment.StrategyAssignment
import cz.solvina.options.domain.features.strategy.assignment.StrategyAssignmentPort
import cz.solvina.options.domain.models.Symbol
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

private val logger = KotlinLogging.logger {}

/**
 * Not cached, unlike [UniversePersistenceAdapter]: assignments are edited from the UI while the
 * engine runs, and a stale cache would mean a toggle silently not taking effect until restart. The
 * table is tiny and read once per scheduled pass, so the query cost is irrelevant next to that.
 */
@Component
class StrategyAssignmentPersistenceAdapter(
    private val repository: StrategyAssignmentRepository,
    private val mapper: ObjectMapper,
) : StrategyAssignmentPort {
    override fun findAll(): List<StrategyAssignment> = repository.findAll().map { it.toDomain() }

    override fun findEnabled(): List<StrategyAssignment> = repository.findByEnabledTrue().map { it.toDomain() }

    override fun findById(id: UUID): StrategyAssignment? = repository.findById(id).orElse(null)?.toDomain()

    override fun save(assignment: StrategyAssignment): StrategyAssignment {
        val clash =
            repository.findByStrategyIdAndSymbolAndTimeframe(
                assignment.strategyId,
                assignment.symbol.value,
                assignment.timeframe.label,
            )
        require(clash == null || clash.id == assignment.id) {
            "${assignment.strategyId} is already assigned to ${assignment.symbol.value} on " +
                "${assignment.timeframe.label} (assignment ${clash?.id})"
        }
        val now = Instant.now()
        val existing = repository.findById(assignment.id).orElse(null)
        val entity =
            (existing ?: StrategyAssignmentEntity(id = assignment.id, createdAt = now)).apply {
                strategyId = assignment.strategyId
                symbol = assignment.symbol.value
                timeframe = assignment.timeframe.label
                paramsJson = assignment.paramOverrides?.let { mapper.writeValueAsString(it) }
                enabled = assignment.enabled
                updatedAt = now
            }
        val saved = repository.save(entity).toDomain()
        logger.info {
            "Assignment saved: ${saved.strategyId} on ${saved.symbol.value} ${saved.timeframe.label} " +
                "enabled=${saved.enabled}"
        }
        return saved
    }

    override fun delete(id: UUID): Boolean {
        if (!repository.existsById(id)) return false
        repository.deleteById(id)
        logger.info { "Assignment deleted: $id" }
        return true
    }

    private fun StrategyAssignmentEntity.toDomain() =
        StrategyAssignment(
            id = id,
            strategyId = strategyId,
            symbol = Symbol(symbol),
            timeframe = Timeframe.fromLabel(timeframe),
            // A params blob that no longer parses (hand-edited row, format change) must not take the
            // whole runner down — fall back to descriptor defaults and say so.
            paramOverrides =
                paramsJson?.let {
                    runCatching { mapper.readValue<Map<String, Any?>>(it) }
                        .onFailure { e -> logger.warn { "Assignment $id has unreadable params_json, using defaults: ${e.message}" } }
                        .getOrNull()
                },
            enabled = enabled,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
}
