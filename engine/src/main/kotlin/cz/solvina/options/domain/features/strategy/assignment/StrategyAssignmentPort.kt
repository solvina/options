package cz.solvina.options.domain.features.strategy.assignment

import java.util.UUID

/**
 * Storage for [StrategyAssignment]s. Kept deliberately small: the live runner needs
 * [findEnabled] and nothing else on the hot path; the rest serves CRUD.
 */
interface StrategyAssignmentPort {
    fun findAll(): List<StrategyAssignment>

    /** The only hot read — asked once per scheduled runner pass. */
    fun findEnabled(): List<StrategyAssignment>

    fun findById(id: UUID): StrategyAssignment?

    /**
     * Creates or updates. Uniqueness is (strategyId, symbol, timeframe); a second assignment of the
     * same triple is a configuration mistake, so this throws rather than silently making a duplicate.
     */
    fun save(assignment: StrategyAssignment): StrategyAssignment

    fun delete(id: UUID): Boolean
}
