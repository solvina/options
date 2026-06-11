package cz.solvina.options.adapters.outbound.persistence.postgres.repository

import cz.solvina.options.adapters.outbound.persistence.postgres.entity.EngineTradeEntryEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.math.BigDecimal
import java.util.UUID

interface EngineTradeEntryRepository : JpaRepository<EngineTradeEntryEntity, UUID> {
    fun findBySymbolAndLongStrikeAndShortStrike(
        symbol: String,
        longStrike: BigDecimal,
        shortStrike: BigDecimal?,
    ): List<EngineTradeEntryEntity>

    fun findBySymbolAndLongStrike(
        symbol: String,
        longStrike: BigDecimal,
    ): List<EngineTradeEntryEntity>

    fun findBySymbol(symbol: String): List<EngineTradeEntryEntity>

    fun findByStatus(status: String): List<EngineTradeEntryEntity>

    fun findByOrderId(orderId: Int): EngineTradeEntryEntity?

    fun findAllByOrderByEntryDateDesc(): List<EngineTradeEntryEntity>
}
