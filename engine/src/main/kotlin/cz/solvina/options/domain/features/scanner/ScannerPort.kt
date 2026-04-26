package cz.solvina.options.domain.features.scanner

interface ScannerPort {
    suspend fun scan()
}
