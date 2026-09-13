package io.github.dekkerding.engine.domain.model.requirement;

/**
 * 渲染目标 —— 同一 RequirementIR 的多目标产物枚举（design D4"编译器后端"）。
 *
 * <p>新增目标 = 新增一个 SpecRenderer 实现，不改表单、不改 IR、不改既有渲染器。
 */
public enum RenderTarget {
    OPENSPEC("openspec"),
    VIBECODING("vibecoding");

    private final String value;

    RenderTarget(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    /** 请求参数翻译：非法值 400（fail-fast，比静默默认友好——前端能及早发现拼写错误） */
    public static RenderTarget fromValue(String value) {
        for (RenderTarget target : values()) {
            if (target.value.equalsIgnoreCase(value)) {
                return target;
            }
        }
        throw new IllegalArgumentException("无效的渲染目标: " + value + "（合法: openspec/vibecoding）");
    }
}
