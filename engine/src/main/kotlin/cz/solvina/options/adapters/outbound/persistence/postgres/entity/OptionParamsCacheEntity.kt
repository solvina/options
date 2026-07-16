package cz.solvina.options.adapters.outbound.persistence.postgres.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * Persisted option-chain params for a symbol. The expiration/strike collections are stored as JSON
 * TEXT (see OptionParamsCachePersistenceAdapter) — no length penalty in PostgreSQL and no schema
 * churn when a symbol's strike ladder changes.
 */
@Entity
@Table(name = "option_params_cache")
class OptionParamsCacheEntity(
    @Id
    @Column(length = 10)
    var symbol: String = "",
    @Column(name = "expirations_json", columnDefinition = "TEXT", nullable = false)
    var expirationsJson: String = "[]",
    @Column(name = "strikes_json", columnDefinition = "TEXT", nullable = false)
    var strikesJson: String = "[]",
    @Column(name = "strikes_by_expiry_json", columnDefinition = "TEXT", nullable = false)
    var strikesByExpiryJson: String = "{}",
    @Column(nullable = false, length = 32)
    var exchange: String = "SMART",
    @Column(name = "trading_class", nullable = false, length = 32)
    var tradingClass: String = "",
    @Column(nullable = false, length = 16)
    var multiplier: String = "100",
    @Column(name = "fetched_at", nullable = false)
    var fetchedAt: Instant = Instant.EPOCH,
)
