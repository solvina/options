package cz.solvina.options.adapters.outbound.persistence.postgres.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** One title in the day's candidate pool (v49). Unique per (pool_date, symbol). */
@Entity
@Table(name = "candidate_pool")
class CandidatePoolEntity(
    @Id
    var id: UUID = UUID.randomUUID(),
    @Column(name = "pool_date", nullable = false)
    var poolDate: LocalDate = LocalDate.EPOCH,
    @Column(columnDefinition = "TEXT", nullable = false)
    var symbol: String = "",
    @Column(name = "first_seen_at", nullable = false)
    var firstSeenAt: Instant = Instant.EPOCH,
)
