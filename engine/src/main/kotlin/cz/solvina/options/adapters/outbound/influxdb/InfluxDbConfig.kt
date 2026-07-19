package cz.solvina.options.adapters.outbound.influxdb

import com.influxdb.client.InfluxDBClientOptions
import com.influxdb.client.kotlin.InfluxDBClientKotlin
import com.influxdb.client.kotlin.InfluxDBClientKotlinFactory
import okhttp3.OkHttpClient
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.TimeUnit

@Configuration
class InfluxDbConfig {
    @Bean(destroyMethod = "close")
    fun influxDbClient(props: InfluxDbProperties): InfluxDBClientKotlin =
        InfluxDBClientKotlinFactory.create(
            InfluxDBClientOptions
                .builder()
                .url(props.url)
                .authenticateToken(props.token.toCharArray())
                .org(props.org)
                .bucket(props.bucket)
                // The client's 10s default read timeout is too short for full-bucket scans
                // (/historical/summary) on the RPi's SD-card storage.
                .okHttpClient(
                    OkHttpClient
                        .Builder()
                        .readTimeout(120, TimeUnit.SECONDS),
                ).build(),
        )
}
