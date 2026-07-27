package cz.solvina.options.adapters.outbound.persistence.postgres.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table

/** Regular trading window of a market ("US", "EU"). Times are "HH:mm" in [timezone]. */
@Entity
@Table(name = "exchange_session")
class ExchangeSessionEntity(
    @Id
    @Column(length = 10)
    var name: String = "",
    @Column(name = "timezone", nullable = false, columnDefinition = "TEXT")
    var timezone: String = "",
    @Column(name = "open_time", nullable = false, length = 5)
    var openTime: String = "",
    @Column(name = "close_time", nullable = false, length = 5)
    var closeTime: String = "",
)
