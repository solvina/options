package cz.solvina.options.domain.features.scanner

import org.springframework.stereotype.Component

@Component
class TradingKillSwitch(
    config: ScannerConfig,
) {
    @Volatile var scannerPaused: Boolean = config.scannerPaused

    @Volatile var monitorPaused: Boolean = config.monitorPaused
}
