package cz.solvina.options.domain.features.strategy

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Registers the strategy-library templates picked up by [StrategyRegistry].
 *
 * Explicit `@Bean` methods rather than `@Component` on each strategy: a strategy's constructor
 * takes its own params, which are run inputs and not Spring beans, so component scanning would
 * have to guess at them. Here the template is constructed with the descriptor defaults, plainly.
 */
@Configuration
class StrategyLibraryConfig {
    @Bean
    fun supportBounceStrategy(): StockStrategy = SupportBounceStrategy()

    @Bean
    fun rsiMaCrossStrategy(): StockStrategy = RsiMaCrossStrategy()
}
