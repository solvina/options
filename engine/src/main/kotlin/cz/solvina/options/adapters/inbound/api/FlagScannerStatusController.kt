package cz.solvina.options.adapters.inbound.api

import cz.solvina.options.domain.features.flag.FlagScannerService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/flags/scanner")
class FlagScannerStatusController(
    private val flagScannerService: FlagScannerService,
) {
    @GetMapping("/status")
    fun getStatus(): List<FlagScannerService.SymbolScannerStatus> = flagScannerService.getScannerStatus()
}
