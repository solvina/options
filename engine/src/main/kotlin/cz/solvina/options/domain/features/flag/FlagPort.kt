package cz.solvina.options.domain.features.flag

import cz.solvina.options.domain.features.flag.model.FlagPosition
import cz.solvina.options.domain.features.flag.model.FlagStatus
import java.util.UUID

interface FlagPort {
    suspend fun save(position: FlagPosition): FlagPosition
    suspend fun update(position: FlagPosition): FlagPosition
    suspend fun findById(id: UUID): FlagPosition?
    suspend fun findOpen(): List<FlagPosition>
    suspend fun findAll(): List<FlagPosition>
    suspend fun findPage(status: FlagStatus?, page: Int, size: Int): FlagPage
    suspend fun countByStatus(status: FlagStatus): Long
    suspend fun findByStatus(status: FlagStatus): List<FlagPosition>
}

data class FlagPage(
    val content: List<FlagPosition>,
    val totalElements: Long,
    val totalPages: Int,
    val page: Int,
    val size: Int,
)
