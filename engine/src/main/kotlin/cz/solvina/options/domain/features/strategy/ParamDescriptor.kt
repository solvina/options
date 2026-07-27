package cz.solvina.options.domain.features.strategy

/**
 * Self-description of one tunable parameter. One declaration drives three consumers — server-side
 * validation, the sweep grid, and the generic UI form — so adding a parameter to a strategy costs
 * no frontend work and cannot be forgotten in validation.
 */
data class ParamDescriptor(
    val name: String,
    val type: ParamType,
    val default: Any?,
    /** Inclusive bounds for numeric params; null = unbounded on that side. */
    val min: Double? = null,
    val max: Double? = null,
    /** Form grouping, e.g. "Entry", "Exit", "Money management". */
    val group: String = "General",
    val help: String? = null,
)

enum class ParamType { INT, DOUBLE, BOOLEAN, STRING }

/**
 * Resolved parameter values for one strategy run: strategy defaults with any assignment-level
 * override applied. Typed accessors fail loudly rather than silently defaulting — a typo in a
 * params blob must not quietly produce a different strategy than the one that was backtested.
 */
class StrategyParams(
    private val values: Map<String, Any?>,
) {
    fun int(name: String): Int = num(name).toInt()

    fun double(name: String): Double = num(name).toDouble()

    fun boolean(name: String): Boolean =
        when (val v = values[name]) {
            is Boolean -> v
            is String -> v.toBooleanStrict()
            else -> error("Param '$name' is not a boolean: $v")
        }

    fun string(name: String): String = values[name]?.toString() ?: error("Param '$name' is missing")

    /** For host-side conventions a strategy may or may not declare, e.g. a position cap. */
    fun intOrNull(name: String): Int? = if (values[name] == null) null else int(name)

    fun asMap(): Map<String, Any?> = values

    private fun num(name: String): Number =
        when (val v = values[name]) {
            is Number -> v
            is String -> v.toDoubleOrNull() ?: error("Param '$name' is not numeric: $v")
            else -> error("Param '$name' is missing or not numeric: $v")
        }

    companion object {
        /** Descriptor defaults with [overrides] applied on top. Unknown override keys are rejected. */
        fun resolve(
            descriptors: List<ParamDescriptor>,
            overrides: Map<String, Any?> = emptyMap(),
        ): StrategyParams {
            val known = descriptors.associateBy { it.name }
            val unknown = overrides.keys - known.keys
            require(unknown.isEmpty()) { "Unknown strategy params: ${unknown.sorted()}" }
            return StrategyParams(descriptors.associate { it.name to (overrides[it.name] ?: it.default) })
        }
    }
}
