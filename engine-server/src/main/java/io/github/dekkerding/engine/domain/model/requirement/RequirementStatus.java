package io.github.dekkerding.engine.domain.model.requirement;

/**
 * 需求状态机 —— 需求工厂聚合根的生命周期。
 *
 * <pre>
 * DRAFT ──submit(闸门通过)──▶ SUBMITTED ──export──▶ EXPORTED
 *   ▲                            │
 *   └────── 保存编辑(任何状态回草稿) ──┘   编辑同时把全部工件标记过期(stale)
 * </pre>
 *
 * <p>【教学注释 · 为什么 EXPORTED 允许回到 EXPORTED】重复导出不算状态流转
 * （刷新导出时间即可）；同样 EXPORTED 后允许再次渲染（版本号递增），不需要先编辑。
 */
public enum RequirementStatus {
    DRAFT, SUBMITTED, EXPORTED;

    /** 状态机合法流转表：非法组合在运行期被拒绝（fail-fast，好过带病写入） */
    public boolean canTransitionTo(RequirementStatus next) {
        switch (this) {
            case DRAFT:     return next == SUBMITTED || next == DRAFT;
            case SUBMITTED: return next == EXPORTED || next == DRAFT;
            case EXPORTED:  return next == EXPORTED || next == DRAFT;
            default:        return false;
        }
    }
}
