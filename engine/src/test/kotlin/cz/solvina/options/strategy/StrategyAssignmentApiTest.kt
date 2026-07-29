package cz.solvina.options.strategy

import cz.solvina.options.adapters.inbound.api.StrategyAssignmentApiController
import cz.solvina.options.domain.features.bars.Timeframe
import cz.solvina.options.domain.features.strategy.RsiMaCrossStrategy
import cz.solvina.options.domain.features.strategy.StrategyRegistry
import cz.solvina.options.domain.features.strategy.assignment.StrategyAssignment
import cz.solvina.options.domain.features.strategy.assignment.StrategyAssignmentPort
import cz.solvina.options.domain.features.strategy.tuning.StrategySymbolParamsPort
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * An assignment is what the live runner obeys, so a bad one must be refused at write time — while
 * a human is watching — rather than surfacing as a failure at 09:05 with real money configured.
 */
class StrategyAssignmentApiTest {
    private class InMemoryAssignments : StrategyAssignmentPort {
        val stored = mutableMapOf<UUID, StrategyAssignment>()

        override fun findAll() = stored.values.toList()

        override fun findEnabled() = stored.values.filter { it.enabled }

        override fun findById(id: UUID) = stored[id]

        override fun save(assignment: StrategyAssignment): StrategyAssignment {
            val clash =
                stored.values.firstOrNull {
                    it.strategyId == assignment.strategyId &&
                        it.symbol == assignment.symbol &&
                        it.timeframe == assignment.timeframe &&
                        it.id != assignment.id
                }
            require(clash == null) { "already assigned" }
            stored[assignment.id] = assignment
            return assignment
        }

        override fun delete(id: UUID) = stored.remove(id) != null
    }

    /** Overrides now land in the tuning store, not on the assignment row (v38). */
    private class InMemorySymbolParams : StrategySymbolParamsPort {
        val stored = mutableMapOf<Triple<String, String, String>, Map<String, Any?>>()

        override suspend fun get(
            strategyId: String,
            symbol: String,
            timeframe: String,
        ) = stored[Triple(strategyId, symbol, timeframe)]

        override suspend fun allForStrategy(strategyId: String) = stored.filterKeys { it.first == strategyId }.mapKeys { it.key.second }

        override suspend fun upsert(
            strategyId: String,
            symbol: String,
            params: Map<String, Any?>,
            timeframe: String,
        ) {
            stored[Triple(strategyId, symbol, timeframe)] = params
        }

        override suspend fun delete(
            strategyId: String,
            symbol: String,
            timeframe: String,
        ) {
            stored.remove(Triple(strategyId, symbol, timeframe))
        }
    }

    private val registry = StrategyRegistry(listOf(RsiMaCrossStrategy()))
    private val port = InMemoryAssignments()
    private val symbolParams = InMemorySymbolParams()
    private val controller = StrategyAssignmentApiController(port, registry, symbolParams)

    private fun dto(
        params: Map<String, Any?>? = null,
        symbol: String = "exv6",
        timeframe: String? = "1d",
        strategy: String = "rsi_ma_cross",
    ) = StrategyAssignmentApiController.AssignmentDto(
        id = null,
        strategyId = strategy,
        symbol = symbol,
        timeframe = timeframe,
        params = params,
        enabled = true,
        createdAt = null,
        updatedAt = null,
    )

    @Test
    fun `creates an assignment and normalises the symbol`() =
        runTest {
            val response = controller.create(dto())
            assertEquals(HttpStatus.CREATED, response.statusCode)
            assertEquals(
                "EXV6",
                port.stored.values
                    .single()
                    .symbol.value,
            )
            assertEquals(
                Timeframe.DAILY,
                port.stored.values
                    .single()
                    .timeframe,
            )
        }

    @Test
    fun `rejects an unknown strategy`() =
        runTest {
            val response = controller.create(dto(strategy = "no_such_strategy"))
            assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
            assertTrue(port.stored.isEmpty())
        }

    @Test
    fun `rejects a param the strategy does not declare`() =
        runTest {
            // The exact class of mistake that silently ran a whole sweep at defaults before the
            // param-sweep fix — here it must fail loudly instead.
            val response = controller.create(dto(params = mapOf("rsiMAPeriod" to 7)))
            assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
            assertTrue(port.stored.isEmpty())
        }

    @Test
    fun `rejects a timeframe the strategy does not declare`() =
        runTest {
            val response = controller.create(dto(timeframe = "5min"))
            assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
            assertTrue(port.stored.isEmpty())
        }

    @Test
    fun `accepts a valid param override`() =
        runTest {
            val response = controller.create(dto(params = mapOf("rsiPeriod" to 21, "maxOpenPositions" to 3)))
            assertEquals(HttpStatus.CREATED, response.statusCode)
            assertEquals(21, symbolParams.stored.values.single()["rsiPeriod"])
        }

    @Test
    fun `refuses a duplicate strategy-symbol-timeframe triple`() =
        runTest {
            assertEquals(HttpStatus.CREATED, controller.create(dto()).statusCode)
            assertEquals(HttpStatus.BAD_REQUEST, controller.create(dto()).statusCode)
            assertEquals(1, port.stored.size)
        }

    @Test
    fun `delete removes it and reports missing ones`() =
        runTest {
            controller.create(dto())
            val id = port.stored.keys.single()
            assertEquals(HttpStatus.NO_CONTENT, controller.delete(id).statusCode)
            assertEquals(HttpStatus.NOT_FOUND, controller.delete(id).statusCode)
        }
}
