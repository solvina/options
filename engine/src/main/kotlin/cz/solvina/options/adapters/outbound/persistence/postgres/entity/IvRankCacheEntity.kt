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
    @Column(name = "iv_min")
    var ivMin: Double? = null,
    @Column(name = "iv_max")
    var ivMax: Double? = null,
    @Column(name = "iv_percentile")
    var ivPercentile: Double? = null,
    @Column(name = "sample_days")
    var sampleDays: Int? = null,
    /** Comma-separated daily IVs, oldest first. */
    @Column(name = "recent_ivs", columnDefinition = "TEXT")
    var recentIvs: String? = null,
)
