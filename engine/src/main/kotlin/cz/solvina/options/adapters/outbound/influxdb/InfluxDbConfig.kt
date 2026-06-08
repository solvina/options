package cz.solvina.options.adapters.outbound.influxdb

import com.influxdb.client.kotlin.InfluxDBClientKotlin
import com.influxdb.client.kotlin.InfluxDBClientKotlinFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class InfluxDbConfig {
    @Bean(destroyMethod = "close")
    fun influxDbClient(props: InfluxDbProperties): InfluxDBClientKotlin =
        InfluxDBClientKotlinFactory.create(
            props.url,
            props.token.toCharArray(),
            props.org,
            props.bucket,
        )
}
