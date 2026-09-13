package io.github.dekkerding.gateway.statics;

import org.springframework.core.Ordered;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.DispatcherType;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.EnumSet;

/**
 * 哈希资产的长缓存头过滤器 —— 给 /assets/** 打上
 * "public, max-age=31536000, immutable"（spec gateway-routing
 * 「静态资源服务 · 哈希资产长缓存」）。
 *
 * <p>【教学注释 · 为什么是 Filter 而不是 WebStaticConfig 的 setCacheControl】
 * Spring 5.3（Boot 2.6 锁定版本）的 {@code CacheControl} 还没有
 * {@code immutable()}——那是 Spring 6.0 才加入的 API。要在 JDK 8 /
 * Boot 2.6 下拿到 immutable 语义，只能直接写响应头。而资源 handler 的
 * {@code setCacheControl} 会用 applyCacheControl 覆盖 Filter 先设的头
 * （Filter → DispatcherServlet → Handler，后写者胜），所以两条规则各管一段：
 *
 * <pre>
 * /assets/**   本 Filter 负责（REQUEST + FORWARD 两种派发都要生效）
 * index.html   WebStaticConfig 的 setCacheControl(noCache) 负责
 * </pre>
 *
 * <p>【immutable 到底省了什么】没有它，浏览器在 max-age 过期前不发请求、
 * 过期后也要发条件请求（If-None-Match）问一句"变了吗"；带哈希文件名的
 * 资产内容变了名字必然变，这句询问永远不会得到"变了"的答案——immutable
 * 让浏览器连问都不问，是 SPA 发布体系里省流量最多的一行头。
 */
@Configuration
public class AssetCacheFilter implements Filter {

    /** 365 天的秒数：与"内容变名字必变"的哈希策略配套的永久缓存语义 */
    static final String ASSETS_CACHE_HEADER = "public, max-age=31536000, immutable";

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;

        // URL 模式已由注册处限定为 /assets/*，这里只是防御性再核对一次
        if (request.getRequestURI().startsWith("/assets/")) {
            response.setHeader("Cache-Control", ASSETS_CACHE_HEADER);
        }
        chain.doFilter(request, response);
    }

    /**
     * 注册为仅匹配 /assets/* 的 Filter。两个关键点：
     * <ul>
     *   <li>dispatcherTypes 含 FORWARD —— SpaFallbackController 对存在的静态文件
     *       会 forward 原路径，转发后的请求也要带上这层缓存头；</li>
     *   <li>最高优先级 —— 缓存头要在任何可能提交响应的组件之前就位。</li>
     * </ul>
     */
    @Bean
    public FilterRegistrationBean<AssetCacheFilter> assetCacheFilterRegistration() {
        FilterRegistrationBean<AssetCacheFilter> registration =
                new FilterRegistrationBean<>(this);
        registration.addUrlPatterns("/assets/*");
        registration.setDispatcherTypes(EnumSet.of(DispatcherType.REQUEST, DispatcherType.FORWARD));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setName("assetCacheFilter");
        return registration;
    }
}
