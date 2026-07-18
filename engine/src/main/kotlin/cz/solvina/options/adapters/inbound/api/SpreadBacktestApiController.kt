package cz.solvina.options.adapters.inbound.api

import cz.solvina.options.domain.features.backtest.SpreadBacktestService
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

private val logger = KotlinLogging.logger {}

/**
 * Research endpoint for bull-put spread backtests. Every entry-criteria lever is an optional
 * override so variants can be swept and compared (e.g. IV-rank 45 vs 60, delta 0.30 vs 0.20).
 * See SpreadBacktestService for the modelling caveats — structural outputs are trustworthy, dollar
 * P&L is optimistic (BS, flat IV, modeled fills).
 */
@RestController
@RequestMapping("/backtest")
class SpreadBacktestApiController(
    private val service: SpreadBacktestService,
) {
    data class SpreadBacktestRequest(
        val symbols: List<String>,
        val from: LocalDate,
        val to: LocalDate,
        val ivRankThreshold: Double? = null,
        val targetDelta: Double? = null,
        val spreadWidth: Double? = null,
        val dte: Int? = null,
        val takeProfitPct: Double? = null,
        val stopLossMultiple: Double? = null,
        val timeExitDte: Int? = null,
        val minCredit: Double? = null,
    )

    @PostMapping("/spread")
    suspend fun runSpreadBacktest(
        @RequestBody request: SpreadBacktestRequest,
    ): ResponseEntity<SpreadBacktestService.Result> {
        if (request.symbols.isEmpty() || request.from.isAfter(request.to)) {
            return ResponseEntity.badRequest().build()
        }
        val d = SpreadBacktestService.Params()
        val params =
            SpreadBacktestService.Params(
                ivRankThreshold = request.ivRankThreshold ?: d.ivRankThreshold,
                targetDelta = request.targetDelta ?: d.targetDelta,
                spreadWidth = request.spreadWidth ?: d.spreadWidth,
                dte = request.dte ?: d.dte,
                takeProfitPct = request.takeProfitPct ?: d.takeProfitPct,
                stopLossMultiple = request.stopLossMultiple ?: d.stopLossMultiple,
                timeExitDte = request.timeExitDte ?: d.timeExitDte,
                minCredit = request.minCredit ?: d.minCredit,
            )
        logger.info { "Spread backtest: ${request.symbols} ${request.from}..${request.to} params=$params" }
        return ResponseEntity.ok(service.run(request.symbols, request.from, request.to, params))
    }
}
