package cz.solvina.options.adapters.outbound.persistence.postgres.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "engine_trade_entries")
class EngineTradeEntryEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null,
    @Column(nullable = false, length = 10)
    var symbol: String = "",
    @Column(nullable = false, length = 30)
    var spreadType: String = "",
    @Column(name = "long_strike", nullable = false, precision = 10, scale = 2)
    var longStrike: BigDecimal = BigDecimal.ZERO,
    @Column(name = "short_strike", nullable = true, precision = 10, scale = 2)
    var shortStrike: BigDecimal? = null,
    @Column(nullable = false)
    var quantity: Int = 1,
    @Column(name = "entry_date", nullable = false)
    var entryDate: Instant = Instant.now(),
    @Column(name = "entry_credit", nullable = true, precision = 10, scale = 4)
    var entryCredit: BigDecimal? = null,
    @Column(nullable = false, length = 30)
    var status: String = "OPEN",
    @Column(name = "order_id", unique = true)
    var orderId: Int? = null,
    @Column(name = "close_attempts")
    var closeAttempts: Int = 0,
    @Column(name = "last_close_attempt")
    var lastCloseAttempt: Instant? = null,
    @Column(name = "closed_date")
    var closedDate: Instant? = null,
    @Column(name = "closed_price", precision = 10, scale = 4)
    var closedPrice: BigDecimal? = null,
    @Column(name = "notes", length = 500)
    var notes: String? = null,
)
