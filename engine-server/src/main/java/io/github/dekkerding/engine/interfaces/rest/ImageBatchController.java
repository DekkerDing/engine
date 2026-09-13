package io.github.dekkerding.engine.interfaces.rest;

import io.github.dekkerding.engine.application.ImageImportApplicationService;
import io.github.dekkerding.engine.application.ImageImportTask.FileResult;
import io.github.dekkerding.engine.domain.exception.EngineException;
import io.github.dekkerding.engine.interfaces.rest.dto.ImageImportTaskResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * 图片批量导入接口 —— photo-semantic-search 任务 5.1/5.2（design D5）。
 *
 * <p>【REST 语义】
 * <pre>
 * POST /images/batch                受理（multipart：files 多值字段 + 可选 captions 多值字段，
 *                                   与文件逐位对应；缺省全部无 caption）
 *                                  → 200 code=0 {taskId, 明细}（受理即回，不等执行）
 * GET  /images/import-tasks/{id}   进度/明细轮询（完成态保留 24h，过期 404）
 * </pre>
 *
 * <p>【教学注释 · 受理与执行的分离】受理是毫秒级同步（校验+建档），执行是分钟级
 * 异步（标注+双向量化）——批量接口的响应时延必须按受理算，否则千张导入会撞 HTTP
 * 超时。进度轮询是"客户端拉"的模式，比 SSE/WebSocket 简单一个量级，对分钟级
 * 导入场景足够（Flutter 端 1-2 秒一拉）。
 */
@RestController
@RequestMapping("/api/images")
public class ImageBatchController {

    private static final Logger log = LoggerFactory.getLogger(ImageBatchController.class);

    private final ImageImportApplicationService importService;

    public ImageBatchController(ImageImportApplicationService importService) {
        this.importService = importService;
    }

    /**
     * 受理批量导入：整单防线（文件数/体量）在应用层，逐文件预校验不合格记明细不入队。
     * captions 提供时必须与 files 逐位等长（spec：与文件一一对应，缺省全部无）。
     */
    @PostMapping("/batch")
    public ApiResponse<ImageImportTaskResponse> uploadBatch(
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam(value = "captions", required = false) List<String> captions) {
        if (files == null || files.isEmpty()) {
            throw EngineException.badRequest("批量导入必须包含至少一个文件（multipart 字段名必须是 files）");
        }
        if (captions != null && captions.size() != files.size()) {
            throw EngineException.badRequest(String.format(
                    "captions 数与文件数不符: %d != %d（必须逐位对应，缺省请整个字段不传）",
                    captions.size(), files.size()));
        }
        List<ImageImportApplicationService.BatchFile> batch = new ArrayList<>(files.size());
        for (int i = 0; i < files.size(); i++) {
            MultipartFile file = files.get(i);
            String filename = file.getOriginalFilename();
            if (filename == null || filename.trim().isEmpty()) {
                throw EngineException.badRequest("第 " + (i + 1) + " 个文件缺少文件名");
            }
            try {
                String caption = captions == null ? null : normalize(captions.get(i));
                batch.add(new ImageImportApplicationService.BatchFile(
                        filename, file.getBytes(), caption));
            } catch (IOException e) {
                throw EngineException.internal("读取上传内容失败: " + filename, e);
            }
        }
        ImageImportApplicationService.Acceptance acceptance = importService.submitBatch(batch);
        return ApiResponse.ok(new ImageImportTaskResponse(acceptance.getTask()));
    }

    /** 进度/明细轮询：任务端点（内存态任务，完成 24h 后过期 404） */
    @GetMapping("/import-tasks/{taskId}")
    public ApiResponse<ImageImportTaskResponse> task(@PathVariable String taskId) {
        return ApiResponse.ok(new ImageImportTaskResponse(importService.getTask(taskId)));
    }

    /** 空白 caption 归一为 null（= 未提供），与 AnnotationProvider 入参契约对齐 */
    private String normalize(String caption) {
        return caption == null || caption.trim().isEmpty() ? null : caption.trim();
    }
}
