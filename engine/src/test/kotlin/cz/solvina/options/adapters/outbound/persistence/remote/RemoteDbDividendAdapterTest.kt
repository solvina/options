package cz.solvina.options.adapters.outbound.persistence.remote

import cz.solvina.options.domain.models.Symbol
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
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
    fun `returns null instead of throwing when prod database is unreachable`() =
        runTest {
            val config = RemoteDividendConfig(enabled = true, jdbcUrl = "jdbc:postgresql://invalid-host:1/none?connectTimeout=1")
            val adapter = RemoteDbDividendAdapter(config)
            assertNull(adapter.fetchDividendInfo(Symbol("AAPL")))
        }
}
