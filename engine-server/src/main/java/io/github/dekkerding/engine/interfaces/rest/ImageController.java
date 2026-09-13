package io.github.dekkerding.engine.interfaces.rest;

import io.github.dekkerding.engine.application.ImageApplicationService;
import io.github.dekkerding.engine.domain.exception.EngineException;
import io.github.dekkerding.engine.domain.model.image.ImageAsset;
import io.github.dekkerding.engine.domain.model.image.ImageStatus;
import io.github.dekkerding.engine.domain.service.ImageFormatGuard;
import io.github.dekkerding.engine.interfaces.rest.dto.ImageDetailResponse;
import io.github.dekkerding.engine.interfaces.rest.dto.ImageSummary;
import io.github.dekkerding.engine.interfaces.rest.dto.PageResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 图片接口 —— 上传 / 列表 / 详情 / 原图 / 删除 五个入口。
 *
 * <p>【REST 语义】
 * <pre>
 * POST   /images            上传（multipart：file 字段 + 可选 caption 文本字段）
 *                           → 200 code=0（与文档上传同款受理语义）
 * GET    /images            分页列表（?status=&page=&size=）
 * GET    /images/{id}       详情（含模型/维度/降级原因/语义标注）
 * GET    /images/{id}/file  原图二进制（Content-Type 按魔数——不信扩展名）
 * DELETE /images/{id}       删除（向量+原件+记录联动清理）
 * </pre>
 *
 * <p>【教学注释 · /file 端点为什么绕过 ApiResponse 信封】信封是 JSON 的约定，
 * 二进制流塞不进去（Base64 会膨胀 33% 且白白多一次编解码）。原图直接走
 * ResponseEntity&lt;Resource&gt;，这是 REST 里"信封只包结构化数据"的常见例外。
 */
@RestController
@RequestMapping("/images")
public class ImageController {

    private static final Logger log = LoggerFactory.getLogger(ImageController.class);

    private static final int MAX_PAGE_SIZE = 100;

    private final ImageApplicationService imageService;
    private final ImageFormatGuard formatGuard = new ImageFormatGuard();

    public ImageController(ImageApplicationService imageService) {
        this.imageService = imageService;
    }

    /**
     * 上传受理：白名单+魔数+大小校验同步完成，标注+双向量化异步，立即返回可轮询的图片记录。
     *
     * @param caption 可选的覆盖描述（multipart 文本字段）：mock 标注器直接采纳为标注，
     *                使"上传 → 语义拆分 → 向量化"链路可被调用方控制验证；缺省时
     *                标注走文件名派生（spec：caption 优先）
     */
    @PostMapping
    public ApiResponse<ImageSummary> upload(@RequestParam("file") MultipartFile file,
                                            @RequestParam(value = "caption", required = false) String caption) {
        if (file == null || file.isEmpty()) {
            throw EngineException.badRequest("上传文件为空（multipart 字段名必须是 file）");
        }
        String filename = file.getOriginalFilename();
        if (filename == null || filename.trim().isEmpty()) {
            throw EngineException.badRequest("上传文件缺少文件名");
        }
        try {
            ImageAsset imageAsset = imageService.upload(filename, file.getBytes(), normalizeCaption(caption));
            return ApiResponse.ok(new ImageSummary(imageAsset));
        } catch (IOException e) {
            throw EngineException.internal("读取上传内容失败: " + filename, e);
        }
    }

    /** 空白 caption 归一为 null（= 未提供），与 AnnotationProvider 的入参契约对齐 */
    private String normalizeCaption(String caption) {
        return caption == null || caption.trim().isEmpty() ? null : caption.trim();
    }

    /** 分页列表。status 可选；page 从 0 起；size 默认 20 上限 100（与文档列表同一防线） */
    @GetMapping
    public ApiResponse<PageResult<ImageSummary>> list(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        ImageStatus statusFilter = parseStatus(status);
        int safeSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        int safePage = Math.max(0, page);

        List<ImageSummary> items = new ArrayList<>();
        for (ImageAsset imageAsset : imageService.listImages(statusFilter)) {
            items.add(new ImageSummary(imageAsset));
        }
        return ApiResponse.ok(PageResult.of(items, safePage, safeSize));
    }

    @GetMapping("/{id}")
    public ApiResponse<ImageDetailResponse> detail(@PathVariable String id) {
        return ApiResponse.ok(new ImageDetailResponse(imageService.getImageDetail(id)));
    }

    /**
     * 原图访问：Content-Type 按文件实际字节（魔数）判定，不信任扩展名——
     * 上传时魔数已过守卫，这里再验一次是纵深防御（磁盘文件可能被运维误替换）。
     */
    @GetMapping("/{id}/file")
    public ResponseEntity<FileSystemResource> file(@PathVariable String id) {
        ImageAsset imageAsset = imageService.getImageDetail(id).getImageAsset();
        Path stored = Paths.get(imageAsset.getStoredPath());
        if (!Files.exists(stored)) {
            // 记录在库、文件丢失（误删 data 目录等）——数据不一致要在边界上如实暴露
            throw EngineException.internal("图片原件缺失（存储路径无文件）: " + id, null);
        }
        try (InputStream in = Files.newInputStream(stored)) {
            byte[] head = new byte[16];
            int read = in.read(head);
            byte[] actual = Arrays.copyOf(head, Math.max(read, 4));
            String extension = formatGuard.validateAndNormalize(imageAsset.getFilename(), actual);
            return ResponseEntity.ok()
                    .contentType(mediaTypeOf(extension))
                    // inline 让浏览器内联预览；文件名用规范化扩展名（原件本就以它落盘）
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "inline; filename=\"" + imageAsset.getId() + "." + extension + "\"")
                    .body(new FileSystemResource(stored.toFile()));
        } catch (IOException e) {
            throw EngineException.internal("读取图片原件失败: " + id, e);
        }
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) {
        imageService.deleteImage(id);
        return ApiResponse.ok(null);
    }

    /** status 参数翻译：传非法值直接 400（与文档列表同一策略） */
    private ImageStatus parseStatus(String status) {
        if (status == null || status.trim().isEmpty()) {
            return null;
        }
        try {
            return ImageStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw EngineException.badRequest(
                    "无效的状态过滤值: " + status + "（合法: PENDING/VECTORIZING/COMPLETED/FAILED）");
        }
    }

    /** 扩展名 → MIME（白名单六种，与 ImageFormatGuard 一致） */
    private MediaType mediaTypeOf(String extension) {
        switch (extension) {
            case "jpg":
            case "jpeg": return MediaType.IMAGE_JPEG;
            case "png":  return MediaType.IMAGE_PNG;
            case "gif":  return MediaType.IMAGE_GIF;
            default:     return MediaType.APPLICATION_OCTET_STREAM; // webp/bmp 无常量，按二进制流放行
        }
    }
}
