package io.github.dekkerding.engine.interfaces.rest.dto;

import io.github.dekkerding.engine.application.ImageImportTask;

import java.util.ArrayList;
import java.util.List;

/**
 * 批量导入任务响应 DTO —— 受理回执与进度轮询共用（photo-semantic-search）。
 *
 * <p>【字段契约】status 只有 RUNNING/COMPLETED（文件级失败是明细不是任务失败——
 * 部分失败不阻断，spec）；files 明细含拒绝（REJECTED）与终态（COMPLETED/FAILED）
 * 条目，受理后入队文件在到达终态前不出现在明细里。
 */
public class ImageImportTaskResponse {

    private final String taskId;
    private final String status;      // RUNNING / COMPLETED
    private final int total;          // 入队数（不含拒绝）
    private final int processed;      // 已到终态数
    private final int succeeded;
    private final int failed;
    private final int rejected;
    private final String createdAt;
    private final String finishedAt;  // 未完成为 null
    private final List<FileItem> files;

    public ImageImportTaskResponse(ImageImportTask task) {
        this.taskId = task.getId();
        this.status = task.isFinished() ? "COMPLETED" : "RUNNING";
        this.total = task.getTotal();
        this.processed = task.getProcessed();
        this.succeeded = task.getSucceeded();
        this.failed = task.getFailed();
        this.rejected = task.getRejected();
        this.createdAt = task.getCreatedAt().toString();
        this.finishedAt = task.getFinishedAt() == null ? null : task.getFinishedAt().toString();
        this.files = new ArrayList<>();
        for (ImageImportTask.FileResult result : task.getResults()) {
            this.files.add(new FileItem(result));
        }
    }

    public static class FileItem {
        private final String filename;
        private final String state;   // REJECTED / COMPLETED / FAILED
        private final String imageId;
        private final String reason;
        private final boolean duplicateSuspected;

        public FileItem(ImageImportTask.FileResult result) {
            this.filename = result.getFilename();
            this.state = result.getState().name();
            this.imageId = result.getImageId();
            this.reason = result.getReason();
            this.duplicateSuspected = result.isDuplicateSuspected();
        }

        public String getFilename() { return filename; }
        public String getState() { return state; }
        public String getImageId() { return imageId; }
        public String getReason() { return reason; }
        public boolean isDuplicateSuspected() { return duplicateSuspected; }
    }

    public String getTaskId() { return taskId; }
    public String getStatus() { return status; }
    public int getTotal() { return total; }
    public int getProcessed() { return processed; }
    public int getSucceeded() { return succeeded; }
    public int getFailed() { return failed; }
    public int getRejected() { return rejected; }
    public String getCreatedAt() { return createdAt; }
    public String getFinishedAt() { return finishedAt; }
    public List<FileItem> getFiles() { return files; }
}
