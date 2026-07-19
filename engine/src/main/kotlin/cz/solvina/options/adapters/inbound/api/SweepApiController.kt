package cz.solvina.options.adapters.inbound.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import cz.solvina.options.domain.features.backtest.sweep.SweepAxis
import cz.solvina.options.domain.features.backtest.sweep.SweepDefinition
import cz.solvina.options.domain.features.backtest.sweep.SweepJob
import cz.solvina.options.domain.features.backtest.sweep.SweepService
import cz.solvina.options.domain.features.backtest.sweep.SweepStatus
import cz.solvina.options.domain.features.bars.Timeframe
import cz.solvina.options.domain.models.Symbol
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.Resource
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val logger = KotlinLogging.logger {}

/**
 * Parameter sweeps as terminable engine jobs (the in-process successor of scripts/param-sweep.py —
 * same CSV layout, same output directory, so runs from either tool list and resume interchangeably).
 * Mapped without the /api prefix: the proxies rewrite /api/X → /options/X (see StockBacktestApiController).
 */
@RestController
@RequestMapping("/backtest/sweeps")
class SweepApiController(
    private val sweepService: SweepService,
    private val mapper: ObjectMapper,
) {
    /** `request` = fixed fields (same shape as /backtest/stock); `sweep` = param → range or list. */
    data class SweepCreateRequest(
        val name: String? = null,
        val parallelism: Int? = null,
        val request: StockBacktestApiController.StockBacktestRequest,
        val sweep: Map<String, JsonNode>,
    )

    data class PreviewResponse(
        val totalCombos: Long,
        val redundantCombos: Long,
        val toRun: Long,
        val axisSizes: Map<String, Int>,
    )

    @PostMapping("/preview")
    fun preview(
        @RequestBody req: SweepCreateRequest,
    ): ResponseEntity<Any> =
        try {
            val def = toDefinition(req)
            val counts = sweepService.counts(def)
            ResponseEntity.ok(
                PreviewResponse(
                    totalCombos = counts.total,
                    redundantCombos = counts.redundant,
                    toRun = counts.toRun,
                    axisSizes = def.axes.mapValues { it.value.values.size },
                ),
            )
        } catch (e: IllegalArgumentException) {
            ResponseEntity.badRequest().body(mapOf("error" to e.message))
        }

    @PostMapping
    fun create(
        @RequestBody req: SweepCreateRequest,
    ): ResponseEntity<Any> =
        try {
            val def = toDefinition(req)
            // Provenance copy, readable by the results viewer and by param-sweep.py.
            val configJson =
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                    mapOf("name" to def.name, "parallelism" to def.parallelism, "request" to req.request, "sweep" to req.sweep),
                )
            ResponseEntity.status(HttpStatus.CREATED).body(sweepService.start(def, configJson))
        } catch (e: IllegalArgumentException) {
            logger.warn { "Sweep rejected: ${e.message}" }
            ResponseEntity.badRequest().body(mapOf("error" to e.message))
        }

    @GetMapping
    fun list(): List<SweepJob> = sweepService.list()

    @GetMapping("/{id}")
    fun get(
        @PathVariable id: String,
    ): ResponseEntity<SweepJob> = sweepService.get(id)?.let { ResponseEntity.ok(it) } ?: ResponseEntity.notFound().build()

    /** Terminate a running sweep (rows written so far stay; same name resumes later). ?purge=true also deletes the run dir. */
    @DeleteMapping("/{id}")
    fun delete(
        @PathVariable id: String,
        @RequestParam(defaultValue = "false") purge: Boolean,
    ): ResponseEntity<Any> {
        val job = sweepService.get(id) ?: return ResponseEntity.notFound().build()
        return if (purge) {
            if (job.status == SweepStatus.RUNNING) {
                ResponseEntity.status(HttpStatus.CONFLICT).body(mapOf("error" to "terminate before purging"))
            } else {
                sweepService.purge(id)
                ResponseEntity.noContent().build()
            }
        } else {
            ResponseEntity.ok(sweepService.cancel(id) ?: job)
        }
    }

    @GetMapping("/{id}/results", produces = ["text/csv"])
    fun results(
        @PathVariable id: String,
    ): ResponseEntity<Resource> =
        sweepService.resultsPath(id)?.let {
            ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv")).body(FileSystemResource(it) as Resource)
        } ?: ResponseEntity.notFound().build()

    @GetMapping("/{id}/config", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun config(
        @PathVariable id: String,
    ): ResponseEntity<Resource> =
        sweepService.configPath(id)?.let {
            ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(FileSystemResource(it) as Resource)
        } ?: ResponseEntity.notFound().build()

    private fun toDefinition(req: SweepCreateRequest): SweepDefinition {
        val r = req.request
        require(r.symbols.isNotEmpty()) { "request.symbols is required" }
        require(!r.from.isAfter(r.to)) { "from must be <= to" }
        val name = (req.name ?: defaultName(r.symbols.first(), r.timeframe)).trim()
        // The name becomes a directory under the sweep output root — never a path.
        require(NAME_RE.matches(name)) { "name must match ${NAME_RE.pattern}" }
        val axes = LinkedHashMap<String, SweepAxis>()
        for ((param, spec) in req.sweep) {
            require(param in SweepService.SWEEPABLE) { "not a sweepable parameter: $param" }
            axes[param] = parseAxis(param, spec)
        }
        require(axes.isNotEmpty()) { "sweep needs at least one axis" }
        return SweepDefinition(
            name = name,
            symbols = r.symbols.map { Symbol(it.trim().uppercase()) },
            from = r.from,
            to = r.to,
            timeframe = Timeframe.fromLabel(r.timeframe ?: Timeframe.DAILY.label),
            initialCapital = r.initialCapital ?: BigDecimal("20000"),
            baseParams = r.toParams(),
            axes = axes,
            parallelism = (req.parallelism ?: Runtime.getRuntime().availableProcessors()).coerceIn(1, 64),
        )
    }

    private fun parseAxis(
        param: String,
        spec: JsonNode,
    ): SweepAxis =
        when {
            spec.isArray -> {
                val values =
                    spec.map { v ->
                        when {
                            v.isBoolean -> v.booleanValue()
                            v.isNumber -> v.decimalValue()
                            else -> throw IllegalArgumentException("$param: unsupported list value $v")
                        }
                    }
                require(values.isNotEmpty()) { "$param: empty value list" }
                SweepAxis(values)
            }
            spec.isObject && spec.has("min") && spec.has("max") && spec.has("step") -> {
                require(spec["min"].isNumber && spec["max"].isNumber && spec["step"].isNumber) {
                    "$param: min/max/step must be numbers"
                }
                SweepAxis.range(spec["min"].decimalValue(), spec["max"].decimalValue(), spec["step"].decimalValue())
            }
            else -> throw IllegalArgumentException("$param: sweep spec must be a value list or {min,max,step}")
        }

    private fun defaultName(
        symbol: String,
        timeframe: String?,
    ): String =
        "sweep-${symbol.trim().uppercase()}-${timeframe ?: "1d"}-" +
            LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))

    companion object {
        private val NAME_RE = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,80}$")
    }
}
