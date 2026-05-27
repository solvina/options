package cz.solvina.options.adapters.outbound.persistence.postgres

import cz.solvina.options.adapters.outbound.persistence.postgres.entity.SpreadPositionEntity
import cz.solvina.options.adapters.outbound.persistence.postgres.repository.SpreadPositionRepository
import cz.solvina.options.domain.features.order.LegAction
import cz.solvina.options.domain.features.spread.SpreadPage
import cz.solvina.options.domain.features.spread.SpreadPort
import cz.solvina.options.domain.features.spread.model.BullPutSpread
import cz.solvina.options.domain.features.spread.model.SpreadLeg
import cz.solvina.options.domain.features.spread.model.SpreadStatus
import cz.solvina.options.domain.models.Money
import cz.solvina.options.domain.models.OptionContract
import cz.solvina.options.domain.models.OptionType
import cz.solvina.options.domain.models.Symbol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Component
import java.math.BigDecimal

@Component
class SpreadPersistenceAdapter(
    private val repository: SpreadPositionRepository,
) : SpreadPort {
    override suspend fun save(spread: BullPutSpread): BullPutSpread =
        withContext(Dispatchers.IO) {
            repository.save(spread.toEntity()).toDomain()
        }

    override suspend fun update(spread: BullPutSpread): BullPutSpread =
        withContext(Dispatchers.IO) {
            requireNotNull(spread.id) { "Cannot update spread without id" }
            repository.save(spread.toEntity()).toDomain()
        }

    override suspend fun findById(id: java.util.UUID): BullPutSpread? =
        withContext(Dispatchers.IO) {
            repository.findById(id).orElse(null)?.toDomain()
        }

    override suspend fun findOpen(): List<BullPutSpread> =
        withContext(Dispatchers.IO) {
            repository.findByStatusOrderByOpenedAtDesc(SpreadStatus.OPEN.name).map { it.toDomain() }
        }

    override suspend fun findAll(): List<BullPutSpread> =
        withContext(Dispatchers.IO) {
            repository.findAllByOrderByOpenedAtDesc().map { it.toDomain() }
        }

    override suspend fun findPage(
        status: SpreadStatus?,
        page: Int,
        size: Int,
    ): SpreadPage =
        withContext(Dispatchers.IO) {
            val pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "openedAt"))
            val result =
                if (status != null) {
                    repository.findByStatus(status.name, pageable)
                } else {
                    repository.findAllBy(pageable)
                }
            SpreadPage(
                content = result.content.map { it.toDomain() },
                totalElements = result.totalElements,
                totalPages = result.totalPages,
                page = result.number,
                size = result.size,
            )
        }

    override suspend fun countByStatus(status: SpreadStatus): Long =
        withContext(Dispatchers.IO) {
            repository.countByStatus(status.name)
        }

    override suspend fun findByStatus(status: SpreadStatus): List<BullPutSpread> =
        withContext(Dispatchers.IO) {
            repository.findByStatusOrderByOpenedAtDesc(status.name).map { it.toDomain() }
        }

    private fun BullPutSpread.toEntity(): SpreadPositionEntity =
        SpreadPositionEntity(
            id = id,
            symbol = symbol.value,
            status = status.name,
            soldStrike = soldLeg.contract.strike,
            boughtStrike = boughtLeg.contract.strike,
            expiryDate = soldLeg.contract.expiry,
            creditPerShare = creditPerShare,
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
            underlyingPriceAtExit = underlyingPriceAtExit,
            ivRankAtExit = ivRankAtExit,
        )

    private fun SpreadPositionEntity.toDomain(): BullPutSpread {
        val sym = Symbol(symbol)
        val expiry = expiryDate
        val soldContract = OptionContract(sym, expiry, soldStrike, OptionType.PUT)
        val boughtContract = OptionContract(sym, expiry, boughtStrike, OptionType.PUT)
        return BullPutSpread(
            id = requireNotNull(id) { "entity must have id after persistence" },
            symbol = sym,
            soldLeg = SpreadLeg(soldContract, LegAction.SELL, Money(BigDecimal.ZERO), soldOrderId ?: 0),
            boughtLeg = SpreadLeg(boughtContract, LegAction.BUY, Money(BigDecimal.ZERO), boughtOrderId ?: 0),
            creditPerShare = creditPerShare,
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
            underlyingPriceAtExit = underlyingPriceAtExit,
            ivRankAtExit = ivRankAtExit,
        )
    }
}
