package io.github.dekkerding.engine.interfaces.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 前端静态资源服务配置 —— 缓存策略的唯一事实源（openspec single-jar-deployment
 * 「前端静态资源与 SPA 回退」「静态资源缓存头策略」）。
 *
 * <p>【缓存策略 · 为什么两条规则就够】
 * <pre>
 * /assets/**   文件名带内容哈希（index-3f9a2c.js）→ 内容变文件名必变
 *              → max-age=365d + immutable（头由 {@link AssetCacheFilter} 打，
 *                 Spring 5.3 的 CacheControl 无 immutable()），浏览器连协商请求都不发；
 * index.html   不带哈希且必须始终最新（它负责引用新的哈希资产名）
 *              → no-cache：可以用缓存，但每次用前要向服务校验。
 * </pre>
 * 这就是"哈希资产永久缓存 + 入口文件永不过期"的标准 SPA 缓存组合，
 * 发布新版后用户刷新一次即可拿到新资产，不需要清缓存。
 *
 * <p>【教学注释 · 三段 handler 与优先级】同一个资源 mapping 内部，
 * Ant 风格 pattern 按具体度排序：/assets/** 与 /index.html 比 /** 具体，
 * 先命中——所以带哈希资产拿长缓存、入口页拿 noCache、其余路径落进
 * /** 的 {@link SpaFallbackResolver} 做 SPA 回退。而 @RequestMapping 控制器
 * 的优先级高于一切资源 handler，API 流量根本不会走到这里（控制器映射
 * 直接带 /api 前缀，如 /api/documents——这正是合体后前端路由与 API
 * 同端口不冲突的根基：两者命名空间天然分离）。
 */
@Configuration
public class WebStaticConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 带内容哈希的构建产物：路径映射只管"从哪找文件"；缓存头由 AssetCacheFilter 统一打
        // （Spring 5.3 的 CacheControl 无 immutable()，此处 setCacheControl 反而会覆盖 Filter 的头）
        registry.addResourceHandler("/assets/**")
                .addResourceLocations("classpath:/static/assets/");

        // 入口文件：每次校验（ETag/Last-Modified 协商），保证发布后用户能拿到新版引用
        registry.addResourceHandler("/index.html", "/favicon.ico")
                .addResourceLocations("classpath:/static/")
                .setCacheControl(CacheControl.noCache());

        // 兜底静态映射 + SPA 回退（public/ 杂项资源存在则原样返回；
        // 前端路由如 /search 回退 index.html）。回退产物本身也是 HTML 入口 → 同样 noCache
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .setCacheControl(CacheControl.noCache())
                .resourceChain(true)
                .addResolver(new SpaFallbackResolver());
    }
}
