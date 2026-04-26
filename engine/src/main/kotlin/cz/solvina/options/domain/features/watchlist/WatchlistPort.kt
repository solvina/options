package cz.solvina.options.domain.features.watchlist

import cz.solvina.options.domain.models.Symbol

interface WatchlistPort {
    fun getWatchlist(): List<Symbol>
}
