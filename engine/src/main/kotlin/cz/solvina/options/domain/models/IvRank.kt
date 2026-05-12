package cz.solvina.options.domain.models

import java.time.Instant

data class IvRank(
    val rank: Double,
    val currentIv: Double,
    val calculatedAt: Instant,
)
