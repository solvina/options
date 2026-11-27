package cz.solvina.options.order

import cz.solvina.options.domain.features.order.ExecMetrics
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertTrue

class ExecMetricsTest {
    private val t0 = Instant.parse("2026-11-30T14:00:00Z")

    @Test
    fun `entry above mid is reported as positive`() {
        val line =
            ExecMetrics.line(
                "ENTRY",
                "QQQ",
                "450P/445P",
                "FILLED",
                BigDecimal("1.50"),
                BigDecimal("1.58"),
                BigDecimal("1.53"),
                BigDecimal("1.53"),
                t0,
                t0.plusSeconds(212),
                4,
                123,
                creditSide = true,
            )
        assertTrue("fill_vs_mid=+2.0%" in line, line)
        assertTrue("secs=212" in line && "amends=4" in line, line)
    }

    @Test
    fun `exit below mid is reported as positive and an unfilled attempt has no fill`() {
        val filled =
            ExecMetrics.line(
                "EXIT",
                "QQQ",
                "450P/445P",
                "FILLED",
                BigDecimal("0.25"),
                BigDecimal("0.23"),
                BigDecimal("0.24"),
                BigDecimal("0.24"),
                t0,
                t0.plusSeconds(90),
                2,
                7,
                creditSide = false,
            )
        assertTrue("fill_vs_mid=+4.0%" in filled, filled)
        val unfilled =
            ExecMetrics.line(
                "EXIT",
                "QQQ",
                "450P/445P",
                "CANCELLED",
                BigDecimal("0.25"),
                BigDecimal("0.23"),
                BigDecimal("0.27"),
                null,
                t0,
                t0.plusSeconds(300),
                8,
                7,
                creditSide = false,
            )
        assertTrue("fill=n/a" in unfilled && "fill_vs_mid=n/a" in unfilled, unfilled)
    }
}
