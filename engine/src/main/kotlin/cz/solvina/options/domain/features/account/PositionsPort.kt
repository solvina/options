package cz.solvina.options.domain.features.account

interface PositionsPort {
    suspend fun getPositions(): List<AccountPosition>
}
