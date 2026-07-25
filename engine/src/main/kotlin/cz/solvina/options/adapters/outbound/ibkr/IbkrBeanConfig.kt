package cz.solvina.options.adapters.outbound.ibkr

import com.ib.client.EClientSocket
import com.ib.client.EJavaSignal
import com.ib.client.EReaderSignal
import com.ib.client.EWrapper
import cz.solvina.options.domain.features.fatal.FatalLockoutService
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(IbkrConnectionConfig::class)
class IbkrBeanConfig {
    @Bean
    fun eJavaSignal(): EJavaSignal = EJavaSignal()

    @Bean
    fun eClientSocket(
        wrapper: EWrapper,
        eReaderSignal: EReaderSignal,
        fatalLockout: FatalLockoutService,
    ): EClientSocket = GuardedEClientSocket(wrapper, eReaderSignal, fatalLockout)
}
