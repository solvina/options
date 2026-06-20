package cz.solvina.options.backtest

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import cz.solvina.options.adapters.inbound.api.BacktestApiController.FlagBacktestRequest
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals

/**
 * The persisted `params_json` must round-trip back to the exact request so a run's parameters can be
 * recalled and re-run. Mirrors the Spring ObjectMapper config (Kotlin + JavaTime modules).
 */
class BacktestRunParamsRoundTripTest {
    private val mapper =
        jacksonObjectMapper()
            .registerModule(JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    @Test
    fun `flag backtest request round-trips through JSON for param recall`() {
        val request =
            FlagBacktestRequest(
                symbols = listOf("SAP", "ALV", "SIE"),
                from = LocalDate.of(2026, 6, 18),
                to = LocalDate.of(2026, 6, 19),
                label = "loosened-filters baseline",
                maxOpenPositions = 3,
                requireNegativeChannelSlope = false,
                minFlagBarsForEntry = 5,
                minFlagRetracementPct = 0.15,
                minFlagpoleAtrMultiple = 1.5,
                maxFlagpoleAtrMultiple = 5.0,
            )

        val json = mapper.writeValueAsString(request)
        val restored = mapper.readValue<FlagBacktestRequest>(json)

        assertEquals(request, restored, "params_json must deserialize back to the identical request")
    }
}
