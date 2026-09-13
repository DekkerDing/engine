package io.github.dekkerding.engine.interfaces.rest;

import io.github.dekkerding.engine.application.SearchApplicationService;
import io.github.dekkerding.engine.application.dto.SearchResult;
import io.github.dekkerding.engine.interfaces.rest.dto.SearchRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;

/**
 * 检索接口 —— 混合检索（语义 + 全文）的唯一入口。
 *
 * <p>【REST 语义】
 * <pre>
 * POST /search   {"query": "人工智能算法", "topK": 10}
 *   → 200 code=0 data{items[...], total, tookMs, degraded, cached}
 *   → 400 code=1xxx（空查询 / topK 超限 / 维度闸门——信封 message 区分具体原因）
 * </pre>
 *
 * <p>【教学注释 · 为什么查询用 POST 不用 GET】检索是"提交一段可能很长的自然语言
 * 并触发计算"的动作：GET 把查询塞 URL 会撞 URL 长度上限、进访问日志、进浏览器历史
 * （敏感查询场景这三点都是问题）。语义上有副作用（缓存写入）+ 体感上是"提交"，
 * POST 是更诚实的选择——REST 的动词看语义，不看"能不能用 GET 实现"。
 */
@RestController
@RequestMapping("/search")
public class SearchController {

    private final SearchApplicationService searchService;

    public SearchController(SearchApplicationService searchService) {
        this.searchService = searchService;
    }

    /**
     * 混合检索：@Valid 在进方法体前完成请求体校验（空查询直接 400 信封）。
     * modality 缺省 = text（既有客户端零改动）；"image" 走双路召回+重排。
     * rerank/rerankCandidates 缺省 = 服务端配置（spec：缺省启用、可逐请求覆盖）。
     */
    @PostMapping
    public ApiResponse<SearchResult> search(@Valid @RequestBody SearchRequest request) {
        return ApiResponse.ok(searchService.search(request.getQuery(), request.getTopK(),
                request.getModality(), request.getRerank(), request.getRerankCandidates()));
    }
}
