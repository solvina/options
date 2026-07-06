package cz.solvina.options.adapters.outbound.persistence.remote

import cz.solvina.options.domain.features.alert.AlertPort
import cz.solvina.options.domain.models.Symbol
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import java.math.BigDecimal
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RemoteDbDividendAdapterTest {
    @Test
    fun `maps prod universe rows including non-payers with null columns`() {
        val resultSet =
            mockk<ResultSet> {
                every { next() } returnsMany listOf(true, true, false)
                every { getString("symbol") } returnsMany listOf("AAPL", "SPY")
                every { getDate("ex_dividend_date") } returnsMany listOf(java.sql.Date.valueOf("2026-08-10"), null)
                every { getBigDecimal("next_dividend_amount") } returnsMany listOf(BigDecimal("0.2600"), null)
                every { close() } returns Unit
            }
        val statement =
            mockk<PreparedStatement> {
                every { executeQuery() } returns resultSet
                every { close() } returns Unit
            }
        val connection =
            mockk<Connection> {
                every { prepareStatement(any()) } returns statement
            }

        val rows = readRemoteDividendRows(connection)

        assertEquals(2, rows.size)
        assertEquals(LocalDate.parse("2026-08-10"), rows["AAPL"]?.exDividendDate)
        assertEquals(BigDecimal("0.2600"), rows["AAPL"]?.amount)
        // Non-payer row is present but empty — DividendRefreshService skips saving it.
        assertNull(rows["SPY"]?.exDividendDate)
        assertNull(rows["SPY"]?.amount)
    }

    @Test
    fun `unreachable prod returns null, records the attempt, and backs off instead of reconnecting per symbol`() =
        runTest {
            val jdbcTemplate = mockk<JdbcTemplate>(relaxed = true)
            val alertPort = mockk<AlertPort>(relaxed = true)
            val config =
                RemoteDividendConfig(
                    jdbcUrl = "jdbc:postgresql://127.0.0.1:1/none",
                    connectTimeoutSeconds = 1,
                )
            val adapter = RemoteDbDividendAdapter(config, jdbcTemplate, alertPort)

            assertNull(adapter.fetchDividendInfo(Symbol("AAPL")))
            // Second symbol during the same (failed) refresh run: served from the failure backoff —
            // no second connection attempt, so exactly one status row was written.
            assertNull(adapter.fetchDividendInfo(Symbol("MSFT")))
            verify(exactly = 1) { jdbcTemplate.update(any<String>(), *anyVararg()) }
        }
}
