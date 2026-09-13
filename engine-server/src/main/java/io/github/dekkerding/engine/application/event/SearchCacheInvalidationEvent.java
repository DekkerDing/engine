package io.github.dekkerding.engine.application.event;

/**
 * 检索缓存失效事件 —— 摄取数据变更时由 {@code DocumentApplicationService} 发布。
 *
 * <p>【为什么需要它】检索结果带短窗缓存（5 分钟）；但文档删除/新摄取会改变库内容，
 * 不通知的话，用户"删完文档立刻搜索"会拿到缓存的陈旧命中（spec 场景"删除后不再命中"
 * 明确禁止）。事件解耦：文档服务只管"数据变了"这一事实，谁关心谁监听——
 * 检索服务监听后清空缓存，未来任何新缓存/新索引也可自行订阅，发布方零改动。
 *
 * <p>【教学注释 · 领域事件的应用事件版】严格 DDD 里事件定义在 domain 层、进程内同步分发；
 * 这里用 Spring 的 {@code ApplicationEventPublisher} 进程内总线（教学项目够用，
 * 且不引入额外抽象）。事件对象本身不可变，只携带"发生了什么"。
 */
public final class SearchCacheInvalidationEvent {

    /** 引发失效的文档 ID（日志/排查用；失效策略本身是全量清空——窗口仅 5 分钟，选择性失效复杂度不值） */
    private final String documentId;
    /** 变更类型：INGESTED（新数据可检索）/ DELETED（数据已移除） */
    private final ChangeType changeType;

    public enum ChangeType { INGESTED, DELETED }

    public SearchCacheInvalidationEvent(String documentId, ChangeType changeType) {
        this.documentId = documentId;
        this.changeType = changeType;
    }

    public String getDocumentId() { return documentId; }
    public ChangeType getChangeType() { return changeType; }
}
