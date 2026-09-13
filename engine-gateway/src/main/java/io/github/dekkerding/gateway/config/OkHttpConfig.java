package io.github.dekkerding.gateway.config;

import io.github.dekkerding.gateway.proxy.UpstreamProperties;
import okhttp3.OkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * OkHttp 客户端装配 —— 反向代理与健康探测共用一个实例。
 *
 * <p>【教学注释 · 为什么抽成 @Bean】
 * ProxyController（转发）与 UpstreamHealthService（探测）都需要 HTTP 客户端。
 * 各自 new 就会有两套连接池/线程池；抽成 Spring Bean 后：
 *   1. 全应用一份连接池，连接可跨用途复用（同是 127.0.0.1:8081 的 keep-alive 连接）；
 *   2. 超时策略一处配置两处生效——配置漂移（两处超时不一致）在代码层面不可能发生。
 */
@Configuration
public class OkHttpConfig {

    @Bean
    public OkHttpClient okHttpClient(UpstreamProperties properties) {
        return new OkHttpClient.Builder()
                .connectTimeout(properties.getConnectTimeoutMs(), TimeUnit.MILLISECONDS)
                .readTimeout(properties.getReadTimeoutMs(), TimeUnit.MILLISECONDS)
                .build();
    }
}
