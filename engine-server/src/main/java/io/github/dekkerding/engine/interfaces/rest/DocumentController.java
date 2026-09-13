package io.github.dekkerding.engine.interfaces.rest;

import io.github.dekkerding.engine.application.DocumentApplicationService;
import io.github.dekkerding.engine.domain.exception.EngineException;
import io.github.dekkerding.engine.domain.model.document.Document;
import io.github.dekkerding.engine.domain.model.document.DocumentStatus;
import io.github.dekkerding.engine.interfaces.rest.dto.DocumentDetailResponse;
import io.github.dekkerding.engine.interfaces.rest.dto.DocumentSummary;
import io.github.dekkerding.engine.interfaces.rest.dto.PageResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 文档接口 —— 上传 / 列表 / 详情 / 删除 四个入口。
 *
 * <p>【教学注释 · Controller 的克制】每个方法三行以内：接参数 → 调应用服务 → 包响应。
 * 校验细节在应用层、状态流转在领域层、异常翻译在 GlobalExceptionHandler——
 * Controller 薄到"看方法名就知道这个接口干什么"，这是分层架构的验收标准。
 *
 * <p>【REST 语义】
 * <pre>
 * POST   /documents        上传（multipart：file 字段）→ 201 语义上这里统一 200 + code=0
 * GET    /documents        分页列表（?status=&page=&size=）
 * GET    /documents/{id}   详情（含分块与模型信息）
 * DELETE /documents/{id}   删除（联动清理向量/索引/文件）
 * </pre>
 */
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private static final Logger log = LoggerFactory.getLogger(DocumentController.class);

    private static final int MAX_PAGE_SIZE = 100;

    private final DocumentApplicationService documentService;

    public DocumentController(DocumentApplicationService documentService) {
        this.documentService = documentService;
    }

    /** 上传受理：校验与建档同步完成（毫秒级），重活全部异步，立即返回可轮询的文档记录 */
    @PostMapping
    public ApiResponse<DocumentSummary> upload(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw EngineException.badRequest("上传文件为空（multipart 字段名必须是 file）");
        }
        String filename = file.getOriginalFilename();
        if (filename == null || filename.trim().isEmpty()) {
            throw EngineException.badRequest("上传文件缺少文件名");
        }
        try {
            Document document = documentService.upload(filename, file.getBytes());
            return ApiResponse.ok(new DocumentSummary(document));
        } catch (IOException e) {
            throw EngineException.internal("读取上传内容失败: " + filename, e);
        }
    }

    /**
     * 分页列表。status 可选（合法枚举值过滤）；page 从 0 起；size 默认 20 上限 100
     * （不设上限的 size 是自画像 DoS：一个 size=999999 就能打爆内存）。
     */
    @GetMapping
    public ApiResponse<PageResult<DocumentSummary>> list(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        DocumentStatus statusFilter = parseStatus(status);
        int safeSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        int safePage = Math.max(0, page);

        List<DocumentSummary> items = new ArrayList<>();
        for (Document document : documentService.listDocuments(statusFilter)) {
            items.add(new DocumentSummary(document));
        }
        return ApiResponse.ok(PageResult.of(items, safePage, safeSize));
    }

    @GetMapping("/{id}")
    public ApiResponse<DocumentDetailResponse> detail(@PathVariable String id) {
        return ApiResponse.ok(new DocumentDetailResponse(documentService.getDocumentDetail(id)));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) {
        documentService.deleteDocument(id);
        return ApiResponse.ok(null);
    }

    /** status 参数翻译：传非法值直接 400（比静默忽略友好——前端能及早发现拼写错误） */
    private DocumentStatus parseStatus(String status) {
        if (status == null || status.trim().isEmpty()) {
            return null;
        }
        try {
            return DocumentStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw EngineException.badRequest(
                    "无效的状态过滤值: " + status + "（合法: PENDING/PARSING/CHUNKING/VECTORIZING/COMPLETED/FAILED）");
        }
    }
}
