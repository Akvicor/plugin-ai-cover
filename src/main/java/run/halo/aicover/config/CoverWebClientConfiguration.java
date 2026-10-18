package run.halo.aicover.config;

import io.netty.channel.ChannelOption;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

/**
 * 用于下载模型返回的远程图片的 WebClient 配置。
 *
 * <p>默认的 WebClient 内存上限较小，无法容纳生成的图片，因此显式提高上限并设置连接与响应超时。
 */
@Configuration(proxyBeanMethods = false)
public class CoverWebClientConfiguration {

    /** 单个图片的最大内存限制，20MB。 */
    private static final int MAX_IN_MEMORY_SIZE = 20 * 1024 * 1024;

    private static final int CONNECT_TIMEOUT_MILLIS = 10_000;

    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(60);

    @Bean
    WebClient coverImageWebClient() {
        var httpClient = HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MILLIS)
            .responseTimeout(RESPONSE_TIMEOUT);
        var strategies = ExchangeStrategies.builder()
            .codecs(configurer -> configurer.defaultCodecs()
                .maxInMemorySize(MAX_IN_MEMORY_SIZE))
            .build();
        return WebClient.builder()
            .clientConnector(new ReactorClientHttpConnector(httpClient))
            .exchangeStrategies(strategies)
            .build();
    }
}
