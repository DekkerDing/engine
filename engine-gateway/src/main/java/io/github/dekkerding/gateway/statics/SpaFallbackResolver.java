package io.github.dekkerding.gateway.statics;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;

/**
 * SPA 路由回退解析器 —— 让 /search、/documents 等前端路由直接刷新不 404
 * （spec gateway-routing「SPA 路由回退」）。
 *
 * <p>【问题】React Router 的路由只存在于浏览器内存里：用户在 /search 按
 * F5，浏览器向网关真的发 GET /search——classpath 里并没有这个文件，
 * 没有回退逻辑就是 404。
 *
 * <p>【方案 · 资源链解析器，而非 catch-all 控制器】挂在 {@code /**} 资源
 * handler 的链尾，三个分支：
 * <pre>
 * resourcePath = api/**   → 返回 null（404）。API 未匹配就是 404，不能回退成 HTML
 * 对应静态文件存在          → 原样返回（favicon、public/ 杂项资源走这条）
 * 都不是（就是前端路由）     → 返回 index.html（HTTP 200，浏览器渲染后前端路由接管）
 * </pre>
 *
 * <p>【教学注释 · 为什么不用 catch-all 控制器 forward:/index.html】
 * 这是 Spring MVC 的一个经典陷阱：@RequestMapping 映射的优先级**永远高于**
 * 静态资源 handler（RequestMappingHandlerMapping order=0，SimpleUrlHandlerMapping
 * 排在最后）。catch-all 控制器 {@code @GetMapping("/{p1}")} 会把
 * forward:/index.html 也抢回来——{@code /index.html} 匹配 {@code /{p1}}，
 * 控制器又 forward:/index.html……无限递归直到 StackOverflowError。
 * 而资源链解析器整个逻辑都留在资源 handler 体系内，不存在两套 mapping
 * 抢路由的问题；缓存策略（{@link WebStaticConfig}）也继续单一出口生效。
 */
public class SpaFallbackResolver extends PathResourceResolver {

    /** 前端入口页（构建产物位置：engine-gateway/frontend/dist/index.html → jar 内 static/） */
    private static final String INDEX_HTML = "/static/index.html";

    @Override
    protected Resource getResource(String resourcePath, Resource location) throws IOException {
        // 防御分支：/api/** 理论上到不了这里（ProxyController 的 /api/** 是 controller 映射，
        // 优先级更高，会先认领），但显式拒绝比依赖"优先级恰好正确"更稳——万一代理 mapping
        // 变更，也不至于把 API 404 吞成 HTML 页面
        if (resourcePath.startsWith("api/")) {
            return null;   // null = 资源不存在 → 404
        }

        // 目录型路径（根路径 "" 、任何以 / 结尾的）直接走入口页——避免把 classpath 目录当资源返回
        if (resourcePath.isEmpty() || resourcePath.endsWith("/")) {
            return indexResource();
        }

        // 真实存在的静态文件 → 原样返回（由所在 handler 的缓存策略管缓存头）
        Resource requested = location.createRelative(resourcePath);
        if (!resourcePath.contains("..") && requested.exists() && requested.isReadable()) {
            return requested;
        }

        // 前端路由 → 回退入口页（HTTP 200，不是 404）
        return indexResource();
    }

    private static Resource indexResource() {
        ClassPathResource index = new ClassPathResource(INDEX_HTML);
        return index.exists() && index.isReadable() ? index : null;
    }
}
