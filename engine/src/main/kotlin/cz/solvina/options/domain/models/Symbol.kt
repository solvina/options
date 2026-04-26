package cz.solvina.options.domain.models

@JvmInline
value class Symbol(
    val value: String,
) {
    override fun toString(): String = value
}
