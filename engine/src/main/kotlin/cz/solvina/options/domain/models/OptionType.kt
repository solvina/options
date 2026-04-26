package cz.solvina.options.domain.models

enum class OptionType(
    val ibkrCode: String,
) {
    PUT("P"),
    CALL("C"),
}
