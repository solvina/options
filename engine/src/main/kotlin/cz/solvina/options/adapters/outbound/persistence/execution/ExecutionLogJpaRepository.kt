package cz.solvina.options.adapters.outbound.persistence.execution

import cz.solvina.options.domain.features.execution.ExecutionLogPort
import cz.solvina.options.domain.features.execution.model.ExecutionLog
import cz.solvina.options.domain.models.Symbol
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Component
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

private val logger = KotlinLogging.logger {}

@Entity
@Table(name = "execution_log")
data class ExecutionLogEntity(
    @Id
    val id: UUID = UUID.randomUUID(),
    @Column(nullable = false)
    val symbol: String,
    @Column(nullable = false, name = "sold_strike")
    val soldStrike: BigDecimal,
    @Column(nullable = false, name = "bought_strike")
    val boughtStrike: BigDecimal,
    @Column(nullable = false, name = "expiry_date")
    val expiryDate: LocalDate,
    @Column(nullable = false)
    val quantity: Int,
    @Column(nullable = true)
    val orderId: Int? = null,
    @Column(nullable = false, name = "event_type")
    val eventType: String,
    @Column(nullable = false, name = "event_status")
    val eventStatus: String,
    @Column(nullable = true, name = "target_credit")
    val targetCredit: BigDecimal? = null,
    @Column(nullable = true, name = "iv_rank")
    val ivRank: BigDecimal? = null,
    @Column(nullable = true, name = "underlying_price")
    val underlyingPrice: BigDecimal? = null,
    @Column(nullable = true)
    val reason: String? = null,
    @Column(nullable = true, name = "spread_id")
    val spreadId: UUID? = null,
    @Column(nullable = false, name = "created_at")
    val createdAt: Instant = Instant.now(),
    @Column(nullable = true, name = "updated_at")
    val updatedAt: Instant? = null,
) {
    fun toDomain(): ExecutionLog =
        ExecutionLog(
            id = id,
            symbol = Symbol(symbol),
            soldStrike = soldStrike,
            boughtStrike = boughtStrike,
            expiryDate = expiryDate,
            quantity = quantity,
            orderId = orderId,
            eventType =
                cz.solvina.options.domain.features.execution.model.ExecutionEventType
                    .valueOf(eventType),
            eventStatus = eventStatus,
            targetCredit = targetCredit,
            ivRank = ivRank,
            underlyingPrice = underlyingPrice,
            reason = reason,
            spreadId = spreadId,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
}

@Repository
interface ExecutionLogJpaRepo : JpaRepository<ExecutionLogEntity, UUID> {
    fun findBySymbol(symbol: String): List<ExecutionLogEntity>

    fun findBySpreadId(spreadId: UUID): List<ExecutionLogEntity>
}

@Component
class ExecutionLogRepositoryAdapter(
    private val jpaRepo: ExecutionLogJpaRepo,
) : ExecutionLogPort {
    override suspend fun log(executionLog: ExecutionLog): ExecutionLog {
        val entity =
            ExecutionLogEntity(
                id = executionLog.id ?: UUID.randomUUID(),
                symbol = executionLog.symbol.value,
                soldStrike = executionLog.soldStrike,
                boughtStrike = executionLog.boughtStrike,
                expiryDate = executionLog.expiryDate,
                quantity = executionLog.quantity,
                orderId = executionLog.orderId,
                eventType = executionLog.eventType.name,
                eventStatus = executionLog.eventStatus,
                targetCredit = executionLog.targetCredit,
                ivRank = executionLog.ivRank,
                underlyingPrice = executionLog.underlyingPrice,
                reason = executionLog.reason,
                spreadId = executionLog.spreadId,
                createdAt = executionLog.createdAt,
                updatedAt = executionLog.updatedAt,
            )
        val saved = jpaRepo.save(entity)
        logger.debug { "[${saved.symbol}] Execution logged: ${saved.eventType} (orderId=${saved.orderId})" }
        return saved.toDomain()
    }

    override suspend fun logEvent(
        symbol: Symbol,
        soldStrike: BigDecimal,
        boughtStrike: BigDecimal,
        expiryDate: LocalDate,
        quantity: Int,
        eventType: cz.solvina.options.domain.features.execution.model.ExecutionEventType,
        eventStatus: String,
        orderId: Int?,
        targetCredit: BigDecimal?,
        ivRank: BigDecimal?,
        underlyingPrice: BigDecimal?,
        reason: String?,
        spreadId: UUID?,
    ): ExecutionLog =
        log(
            ExecutionLog(
                symbol = symbol,
                soldStrike = soldStrike,
                boughtStrike = boughtStrike,
                expiryDate = expiryDate,
                quantity = quantity,
                orderId = orderId,
                eventType = eventType,
                eventStatus = eventStatus,
                targetCredit = targetCredit,
                ivRank = ivRank,
                underlyingPrice = underlyingPrice,
                reason = reason,
                spreadId = spreadId,
            ),
        )

    override suspend fun findBySpreadId(spreadId: UUID): List<ExecutionLog> = jpaRepo.findBySpreadId(spreadId).map { it.toDomain() }

    override suspend fun findBySymbol(symbol: Symbol): List<ExecutionLog> = jpaRepo.findBySymbol(symbol.value).map { it.toDomain() }

    override suspend fun findAll(): List<ExecutionLog> = jpaRepo.findAll().map { it.toDomain() }
}
