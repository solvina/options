package cz.solvina.options.adapters.outbound.persistence.postgres.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "iv_rank_cache")
class IvRankCacheEntity(
    @Id
    @Column(length = 10)
    var symbol: String = "",
    @Column(nullable = false)
    var rank: Double = 0.0,
    @Column(name = "current_iv", nullable = false)
    var currentIv: Double = 0.0,
    @Column(name = "calculated_at", nullable = false)
    var calculatedAt: Instant = Instant.EPOCH,
)
