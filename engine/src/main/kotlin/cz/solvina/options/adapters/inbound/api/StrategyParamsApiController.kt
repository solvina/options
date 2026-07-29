package cz.solvina.options.adapters.inbound.api

import cz.solvina.options.domain.features.flag.FlagScannerService
import cz.solvina.options.domain.features.flag.config.FLAG_STRATEGY_ID
import cz.solvina.options.domain.features.strategy.ParamType
import cz.solvina.options.domain.features.strategy.StrategyParams
import cz.solvina.options.domain.features.strategy.tuning.StrategyDefaultParamsPort
import cz.solvina.options.domain.features.strategy.tuning.StrategyParamsResolver
import cz.solvina.options.domain.features.strategy.tuning.StrategySymbolParamsPort
import cz.solvina.options.domain.features.strategy.tuning.TunableStrategyRegistry
import cz.solvina.options.domain.models.Symbol
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

private val logger = KotlinLogging.logger {}

/**
 * Tuning for every [cz.solvina.options.domain.features.strategy.TunableStrategy].
 *
 * Generic on purpose: the form the UI renders is built from the descriptors returned here, so a new
 * strategy — or a new parameter on an existing one — appears in the UI without a frontend change.
 * That is the whole reason parameters are descriptor-driven rather than columns.
 *
 * Path has no /api prefix — both proxies rewrite /api/X to /options/X.
 */
@RestController
@RequestMapping("/strategy-params")
class StrategyParamsApiController(
    private val registry: TunableStrategyRegistry,
    private val resolver: StrategyParamsResolver,
    private val defaults: StrategyDefaultParamsPort,
    private val symbolParams: StrategySymbolParamsPort,
    private val flagScannerService: FlagScannerService,
) {
    data class ParamDescriptorDto(
        val name: String,
        val type: ParamType,
        val default: Any?,
        val min: Double?,
        val max: Double?,
        val group: String,
        val help: String?,
    )

    data class StrategyParamsDto(
        val strategyId: String,
        val displayName: String,
        val descriptors: List<ParamDescriptorDto>,
        /** Descriptor defaults — what Reset restores. Never null, never stored. */
        val defaults: Map<String, Any?>,
        /** Currently saved global baseline (defaults merged with the saved row). */
        val values: Map<String, Any?>,
        /** True when a saved baseline exists, i.e. Reset would actually change something. */
        val customised: Boolean,
    )

    data class SymbolParamsDto(
        val strategyId: String,
        val symbol: String,
        /** Effective values for this symbol: global baseline with its own override applied. */
        val values: Map<String, Any?>,
        /** Only the parameters this symbol overrides. Empty when it inherits everything. */
        val overrides: Map<String, Any?>,
    )

    @GetMapping
    suspend fun list(): List<StrategyParamsDto> = registry.all().map { describe(it.id) }

    @GetMapping("/{strategyId}")
    suspend fun get(
        @PathVariable strategyId: String,
    ): ResponseEntity<Any> = withStrategy(strategyId) { ResponseEntity.ok(describe(strategyId)) }

    /** Saves the global baseline. Applies immediately — see [applyToRunningScanner]. */
    @PutMapping("/{strategyId}")
    suspend fun update(
        @PathVariable strategyId: String,
        @RequestBody values: Map<String, Any?>,
    ): ResponseEntity<Any> =
        withStrategy(strategyId) {
            // Validate against the descriptors before persisting: an unknown name or a value outside
            // its declared bounds must fail here, with a human watching, not at the next breakout.
            validate(strategyId, values)?.let { return reject(it) }
            defaults.upsert(strategyId, values)
            applyToRunningScanner(strategyId)
            ResponseEntity.ok(describe(strategyId))
        }

    /** Resets the global baseline to descriptor defaults by deleting the saved row. */
    @DeleteMapping("/{strategyId}")
    suspend fun reset(
        @PathVariable strategyId: String,
    ): ResponseEntity<Any> =
        withStrategy(strategyId) {
            defaults.delete(strategyId)
            applyToRunningScanner(strategyId)
            ResponseEntity.ok(describe(strategyId))
        }

    @GetMapping("/{strategyId}/symbols")
    suspend fun listSymbolOverrides(
        @PathVariable strategyId: String,
    ): ResponseEntity<Any> =
        withStrategy(strategyId) {
            ResponseEntity.ok(
                resolver.symbolsWithOverrides(strategyId).mapValues { (_, o) ->
                    o.mapValues { (_, override) -> override.custom }
                },
            )
        }

    @GetMapping("/{strategyId}/symbols/{symbol}")
    suspend fun getSymbol(
        @PathVariable strategyId: String,
        @PathVariable symbol: String,
    ): ResponseEntity<Any> =
        withStrategy(strategyId) {
            val sym = symbol.uppercase()
            ResponseEntity.ok(
                SymbolParamsDto(
                    strategyId = strategyId,
                    symbol = sym,
                    values = resolver.effectiveParams(strategyId, sym).asMap(),
                    overrides = resolver.overridesFor(strategyId, sym).mapValues { it.value.custom },
                ),
            )
        }

    @PutMapping("/{strategyId}/symbols/{symbol}")
    suspend fun updateSymbol(
        @PathVariable strategyId: String,
        @PathVariable symbol: String,
        @RequestBody values: Map<String, Any?>,
    ): ResponseEntity<Any> =
        withStrategy(strategyId) {
            val sym = symbol.uppercase()
            validate(strategyId, values)?.let { return reject(it) }
            symbolParams.upsert(strategyId, sym, values)
            applyToRunningScanner(strategyId, sym)
            ResponseEntity.ok(describe(strategyId))
        }

    /** Clears this symbol's override so it falls back to the global baseline. */
    @DeleteMapping("/{strategyId}/symbols/{symbol}")
    suspend fun resetSymbol(
        @PathVariable strategyId: String,
        @PathVariable symbol: String,
    ): ResponseEntity<Any> =
        withStrategy(strategyId) {
            val sym = symbol.uppercase()
            symbolParams.delete(strategyId, sym)
            applyToRunningScanner(strategyId, sym)
            ResponseEntity.ok(describe(strategyId))
        }

    /** Re-applies parameters to live subscriptions without touching them. Idempotent. */
    @PostMapping("/{strategyId}/apply")
    suspend fun apply(
        @PathVariable strategyId: String,
    ): ResponseEntity<Any> =
        withStrategy(strategyId) {
            ResponseEntity.ok(mapOf("rebuilt" to applyToRunningScanner(strategyId)))
        }

    /**
     * Pushes the new parameters into whatever is already running.
     *
     * Only the flag scanner holds long-lived per-symbol state built from parameters; the stock
     * strategy runner resolves fresh on each scheduled pass and so needs nothing. Silence here would
     * mean a save that only takes effect at the next restart, which is precisely the behaviour this
     * feature replaces.
     */
    private suspend fun applyToRunningScanner(
        strategyId: String,
        symbol: String? = null,
    ): Int {
        if (strategyId != FLAG_STRATEGY_ID) return 0
        return flagScannerService.applyParams(symbol?.let { listOf(Symbol(it)) })
    }

    private suspend fun describe(strategyId: String): StrategyParamsDto {
        val strategy = registry.require(strategyId)
        return StrategyParamsDto(
            strategyId = strategy.id,
            displayName = strategy.displayName,
            descriptors =
                strategy.params.map {
                    ParamDescriptorDto(it.name, it.type, it.default, it.min, it.max, it.group, it.help)
                },
            defaults = resolver.descriptorDefaults(strategyId).asMap(),
            values = resolver.globalParams(strategyId).asMap(),
            customised = defaults.get(strategyId) != null,
        )
    }

    /** Returns why [values] is unusable, or null. Bounds are checked here; descriptors declare them. */
    private fun validate(
        strategyId: String,
        values: Map<String, Any?>,
    ): String? {
        val strategy = registry.require(strategyId)
        runCatching { StrategyParams.resolve(strategy.params, values) }
            .onFailure { return it.message ?: "invalid parameters" }
        val byName = strategy.params.associateBy { it.name }
        values.forEach { (name, value) ->
            val descriptor = byName[name] ?: return "unknown parameter '$name'"
            val number = (value as? Number)?.toDouble() ?: (value as? String)?.toDoubleOrNull() ?: return@forEach
            descriptor.min?.let { if (number < it) return "$name must be >= $it" }
            descriptor.max?.let { if (number > it) return "$name must be <= $it" }
        }
        return null
    }

    private inline fun withStrategy(
        strategyId: String,
        block: () -> ResponseEntity<Any>,
    ): ResponseEntity<Any> {
        if (registry.find(strategyId) == null) {
            return ResponseEntity.badRequest().body(mapOf("error" to "unknown strategy '$strategyId'"))
        }
        return block()
    }

    private fun reject(reason: String): ResponseEntity<Any> {
        logger.warn { "Strategy params rejected: $reason" }
        return ResponseEntity.badRequest().body(mapOf("error" to reason))
    }
}
