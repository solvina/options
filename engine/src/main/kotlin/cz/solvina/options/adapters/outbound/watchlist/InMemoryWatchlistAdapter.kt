package cz.solvina.options.adapters.outbound.watchlist

import cz.solvina.options.domain.features.scanner.ScannerConfig
import cz.solvina.options.domain.features.watchlist.WatchlistPort
import cz.solvina.options.domain.models.Symbol
import org.springframework.stereotype.Component

@Component
class InMemoryWatchlistAdapter(
    private val config: ScannerConfig,
) : WatchlistPort {
    override fun getWatchlist(): List<Symbol> = config.watchlist.map { Symbol(it) }
}
