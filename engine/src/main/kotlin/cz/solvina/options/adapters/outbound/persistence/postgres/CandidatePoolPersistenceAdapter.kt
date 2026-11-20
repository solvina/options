package cz.solvina.options.adapters.outbound.persistence.postgres

import cz.solvina.options.adapters.outbound.persistence.postgres.entity.CandidatePoolEntity
import cz.solvina.options.adapters.outbound.persistence.postgres.repository.CandidatePoolRepository
import cz.solvina.options.domain.features.scanner.CandidatePoolStorePort
import cz.solvina.options.domain.models.Symbol
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate

@Component
class CandidatePoolPersistenceAdapter(
    private val repository: CandidatePoolRepository,
) : CandidatePoolStorePort {
    override fun load(day: LocalDate): Map<Symbol, Instant> =
        repository.findByPoolDate(day).associate { Symbol(it.symbol) to it.firstSeenAt }

    @Transactional
    override fun add(
        day: LocalDate,
        symbol: Symbol,
        firstSeenAt: Instant,
    ) {
        if (repository.existsByPoolDateAndSymbol(day, symbol.value)) return
        repository.save(CandidatePoolEntity(poolDate = day, symbol = symbol.value, firstSeenAt = firstSeenAt))
    }

    @Transactional
    override fun remove(
        day: LocalDate,
        symbol: Symbol,
    ) {
        repository.deleteByPoolDateAndSymbol(day, symbol.value)
    }

    @Transactional
    override fun clear(day: LocalDate) {
        repository.deleteByPoolDate(day)
    }

    @Transactional
    override fun deleteBefore(day: LocalDate) {
        repository.deleteByPoolDateBefore(day)
    }
}
