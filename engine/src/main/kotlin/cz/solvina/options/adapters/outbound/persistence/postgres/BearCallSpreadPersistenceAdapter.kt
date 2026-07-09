package cz.solvina.options.adapters.outbound.persistence.postgres

import cz.solvina.options.adapters.outbound.persistence.postgres.entity.BearCallSpreadEntity
import cz.solvina.options.adapters.outbound.persistence.postgres.repository.BearCallSpreadRepository
import cz.solvina.options.domain.features.order.LegAction
import cz.solvina.options.domain.features.spread.BearCallSpreadPort
import cz.solvina.options.domain.features.spread.model.BearCallSpread
import cz.solvina.options.domain.features.spread.model.SpreadLeg
import cz.solvina.options.domain.features.spread.model.SpreadStatus
import cz.solvina.options.domain.models.Money
import cz.solvina.options.domain.models.OptionContract
import cz.solvina.options.domain.models.OptionType
import cz.solvina.options.domain.models.Symbol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Component
class BearCallSpreadPersistenceAdapter(
    private val repository: BearCallSpreadRepository,
) : BearCallSpreadPort {
    override suspend fun save(spread: BearCallSpread): BearCallSpread =
        withContext(Dispatchers.IO) {
            repository.save(spread.toEntity()).toDomain()
        }

    override suspend fun update(spread: BearCallSpread): BearCallSpread =
        withContext(Dispatchers.IO) {
            requireNotNull(spread.id) { "Cannot update spread without id" }
            repository.save(spread.toEntity()).toDomain()
        }

    override suspend fun findById(id: UUID): BearCallSpread? =
        withContext(Dispatchers.IO) {
            repository.findById(id).orElse(null)?.toDomain()
        }

    override suspend fun findOpen(): List<BearCallSpread> =
        withContext(Dispatchers.IO) {
            repository.findByStatusOrderByOpenedAtDesc(SpreadStatus.OPEN.name).map { it.toDomain() }
        }

    override suspend fun findAll(): List<BearCallSpread> =
        withContext(Dispatchers.IO) {
            repository.findAllByOrderByOpenedAtDesc().map { it.toDomain() }
        }

    override suspend fun countByStatus(status: SpreadStatus): Long =
        withContext(Dispatchers.IO) {
            repository.countByStatus(status.name)
        }

    override suspend fun countFilledSince(since: Instant): Long =
        withContext(Dispatchers.IO) {
            repository.countByOpenedAtGreaterThanEqualAndStatusNotIn(since, SpreadStatus.NOT_FILLED.map { it.name })
        }

    override suspend fun findByStatus(status: SpreadStatus): List<BearCallSpread> =
        withContext(Dispatchers.IO) {
            repository.findByStatusOrderByOpenedAtDesc(status.name).map { it.toDomain() }
        }

    override suspend fun findBySymbolWithLock(symbol: Symbol): List<BearCallSpread> =
        withContext(Dispatchers.IO) {
            repository.findBySymbolWithLock(symbol.value).map { it.toDomain() }
        }

    private fun BearCallSpread.toEntity(): BearCallSpreadEntity =
        BearCallSpreadEntity(
            id = id,
            symbol = symbol.value,
            status = status.name,
            soldStrike = soldLeg.contract.strike,
            boughtStrike = boughtLeg.contract.strike,
            expiryDate = soldLeg.contract.expiry,
            creditPerShare = creditPerShare,
            entryMidPerShare = entryMidPerShare,
            maxRiskPerShare = maxRiskPerShare,
            quantity = quantity,
            soldOrderId = soldLeg.orderId,
            boughtOrderId = boughtLeg.orderId,
            ivRankAtEntry = ivRankAtEntry?.let { BigDecimal(it).setScale(2, java.math.RoundingMode.HALF_UP) },
            underlyingPriceAtEntry = underlyingPriceAtEntry,
            openedAt = openedAt,
            closedAt = closedAt,
            closeReason = closeReason,
            closePricePerShare = closePricePerShare,
            lastSpreadValue = lastSpreadValue,
            lastUnderlyingPrice = lastUnderlyingPrice,
            underlyingPriceAtExit = underlyingPriceAtExit,
            ivRankAtExit = ivRankAtExit,
            exDividendDate = exDividendDate,
        )

    private fun BearCallSpreadEntity.toDomain(): BearCallSpread {
        val sym = Symbol(symbol)
        val expiry = expiryDate
        // Bear call legs are CALLs: SELL the lower strike, BUY the higher strike.
        val soldContract = OptionContract(sym, expiry, soldStrike, OptionType.CALL)
        val boughtContract = OptionContract(sym, expiry, boughtStrike, OptionType.CALL)
        return BearCallSpread(
            id = requireNotNull(id) { "entity must have id after persistence" },
            symbol = sym,
            soldLeg = SpreadLeg(soldContract, LegAction.SELL, Money(BigDecimal.ZERO), soldOrderId ?: 0),
            boughtLeg = SpreadLeg(boughtContract, LegAction.BUY, Money(BigDecimal.ZERO), boughtOrderId ?: 0),
            creditPerShare = creditPerShare,
            entryMidPerShare = entryMidPerShare,
            maxRiskPerShare = maxRiskPerShare,
            quantity = quantity,
            status = SpreadStatus.valueOf(status),
            ivRankAtEntry = ivRankAtEntry?.toDouble(),
            underlyingPriceAtEntry = underlyingPriceAtEntry,
            openedAt = openedAt,
            closedAt = closedAt,
            closeReason = closeReason,
            closePricePerShare = closePricePerShare,
            lastSpreadValue = lastSpreadValue,
            lastUnderlyingPrice = lastUnderlyingPrice,
            underlyingPriceAtExit = underlyingPriceAtExit,
            ivRankAtExit = ivRankAtExit,
            exDividendDate = exDividendDate,
        )
    }
}
