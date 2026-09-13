package io.github.dekkerding.engine.interfaces.web;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * /api 前缀重写过滤器 —— 单 JAR 合体后替代原网关反向代理的"剥前缀"职责
 * （openspec single-jar-deployment「/api 前缀重写」）。
 *
 * <p>【问题】前端与 APP 的既有契约是 {@code :8090/api/**}，而控制器映射是
 * {@code /system/**}、{@code /documents/**}……双进程时代由网关 ReverseProxy
 * 剥掉 /api 再转发 :8081；合体后同进程没有"转发"可言，改为在请求进入
 * DispatcherServlet **之前**把 URI 前缀剥掉——控制器、actuator、静态资源
 * 全部零改动。
 *
 * <p>【关键 · Wrapper 必须同时覆写两个方法】Spring MVC 5.3 的路径解析
 * （ServletRequestPathUtils）以 {@code getRequestURI()} 为主，但部分组件
 * （welcome page、servlet path 相关的 mapping）读 {@code getServletPath()}——
 * 只覆写其一会出现部分 mapping 失效的诡异现象。
 *
 * <p>【不动的东西】
 * <pre>
 * getQueryString()   不覆写——默认透传，?a=1&b=2 原样到达控制器
 * 路径编码           getRequestURI() 返回原始编码串，剥前缀是纯字符串操作，
 *                    %E6%B5%8B 之类的百分号转义零漂移
 * 非 /api 路径        /actuator/**、/assets/**、SPA 路由原样放行
 * </pre>
 *
 * <p>【精确 /api 的行为】剥后为空串——DispatcherServlet 找不到映射返回 404，
 * 与原网关 StripPrefix 行为等价（前端不会发起裸 /api 请求）。
 */
@Configuration
public class ApiPrefixRewriteFilter {

    /** 合体前的对外 API 前缀（保留原样，控制器映射不带它） */
    static final String API_PREFIX = "/api";

    @Bean
    public FilterRegistrationBean<OncePerRequestFilter> apiPrefixRewriteFilterRegistration() {
        FilterRegistrationBean<OncePerRequestFilter> registration =
                new FilterRegistrationBean<>(new PrefixStripFilter());
        // 最先执行：后续所有 Filter 与 DispatcherServlet 看到的已是剥好的路径。
        // 与 assetCacheFilter 同为 HIGHEST_PRECEDENCE，但 URL 模式（/api/* vs /assets/*）
        // 不相交，语义互不影响
        registration.addUrlPatterns("/api/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setName("apiPrefixRewriteFilter");
        registration.setDispatcherTypes(javax.servlet.DispatcherType.REQUEST,
                javax.servlet.DispatcherType.FORWARD,
                javax.servlet.DispatcherType.ASYNC);
        return registration;
    }

    /**
     * 剥前缀的本体。注册处已限定 /api/* 模式，doFilter 里只处理
     * {@code /api}（精确，StripPrefix 等价行为）与 {@code /api/...} 两种形态。
     */
    static class PrefixStripFilter extends OncePerRequestFilter {

        @Override
        protected void doFilterInternal(HttpServletRequest request,
                                        HttpServletResponse response,
                                        FilterChain chain) throws IOException, ServletException {
            String uri = request.getRequestURI();
            String rewritten;
            if (API_PREFIX.equals(uri)) {
                // 精确 /api → 空串（DispatcherServlet 404，等价原网关）
                rewritten = "";
            } else if (uri.startsWith(API_PREFIX + "/")) {
                rewritten = uri.substring(API_PREFIX.length());
            } else {
                // /apiXX 等误前缀命中 url-pattern 但语义上不属于 /api 前缀——原样放行
                chain.doFilter(request, response);
                return;
            }
            chain.doFilter(new StrippedRequest(request, rewritten), response);
        }
    }

    /** 同时覆写 getRequestURI() 与 getServletPath() 的包装请求（见类注释【关键】） */
    static class StrippedRequest extends HttpServletRequestWrapper {

        private final String rewrittenPath;

        StrippedRequest(HttpServletRequest request, String rewrittenPath) {
            super(request);
            this.rewrittenPath = rewrittenPath;
        }

        @Override
        public String getRequestURI() {
            return rewrittenPath;
        }

        @Override
        public String getServletPath() {
            return rewrittenPath;
        }
    }
}
