package io.github.dekkerding.engine.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 批量导入任务 —— 内存态的执行进度载体（design D5：不持久化，重启丢任务不丢图片）。
 *
 * <p>【并发模型】受理（HTTP 线程）与执行（导入工作线程 × concurrency）跨线程读写：
 * 计数用原子类、明细用 COW 列表、终态用 volatile——快照读永远一致（可能落后，
 * 但轮询语义本就是"进度可见"而非"实时精确"）。
 *
 * <p>【明细语义】results 含<b>全部</b>文件：预校验不合格记 REJECTED（不入队），
 * 其余入队后在终态时回填 COMPLETED/FAILED；疑似重复（同名同大小存量）仅标注
 * 提示不阻断（design D5：去重属 Non-goal）。
 */
public class ImageImportTask {

    /** 单文件在任务里的状态（REJECTED 是受理期结论，其余是管线终态回填） */
    public enum FileState { REJECTED, COMPLETED, FAILED }

    public static class FileResult {
        private final String filename;
        private final FileState state;
        private final String imageId;   // REJECTED 时为 null
        private final String reason;    // REJECTED/FAILED 时非空
        private final boolean duplicateSuspected;

        public FileResult(String filename, FileState state, String imageId,
                          String reason, boolean duplicateSuspected) {
            this.filename = filename;
            this.state = state;
            this.imageId = imageId;
            this.reason = reason;
            this.duplicateSuspected = duplicateSuspected;
        }

        public String getFilename() { return filename; }
        public FileState getState() { return state; }
        public String getImageId() { return imageId; }
        public String getReason() { return reason; }
        public boolean isDuplicateSuspected() { return duplicateSuspected; }
    }

    private final String id;
    private final Instant createdAt;
    /** 入队文件数（不含 REJECTED——它们从未进入管线） */
    private final int total;
    private final AtomicInteger processed = new AtomicInteger();
    private final List<FileResult> results = new CopyOnWriteArrayList<>();
    private volatile Instant finishedAt;

    public ImageImportTask(String id, int total, Instant createdAt) {
        this.id = id;
        this.total = total;
        this.createdAt = createdAt;
    }

    /** 受理期：记录一个文件结论（REJECTED 立即落明细；入队文件由 recordOutcome 回填终态） */
    void addResult(FileResult result) {
        results.add(result);
    }

    /** 执行期：一个入队文件到达终态（processed 与 finished 判定只在此处推进） */
    void recordOutcome(String filename, String imageId, FileState state, String reason,
                       boolean duplicateSuspected) {
        results.add(new FileResult(filename, state, imageId, reason, duplicateSuspected));
        processed.incrementAndGet();
        if (processed.get() >= total) {
            finishedAt = Instant.now();
        }
    }

    public String getId() { return id; }
    public Instant getCreatedAt() { return createdAt; }
    public int getTotal() { return total; }
    public int getProcessed() { return processed.get(); }
    public boolean isFinished() { return finishedAt != null; }
    public Instant getFinishedAt() { return finishedAt; }

    /** 明细快照（REJECTED 先落、入队文件按完成序；线程安全副本） */
    public List<FileResult> getResults() {
        return Collections.unmodifiableList(new ArrayList<>(results));
    }

    public int getSucceeded() {
        int n = 0;
        for (FileResult r : results) {
            if (r.state == FileState.COMPLETED) {
                n++;
            }
        }
        return n;
    }

    public int getFailed() {
        int n = 0;
        for (FileResult r : results) {
            if (r.state == FileState.FAILED) {
                n++;
            }
        }
        return n;
    }

    public int getRejected() {
        int n = 0;
        for (FileResult r : results) {
            if (r.state == FileState.REJECTED) {
                n++;
            }
        }
        return n;
    }
}
