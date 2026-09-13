package io.github.dekkerding.engine.domain.service.renderer;

import io.github.dekkerding.engine.domain.model.requirement.RequirementIR;
import io.github.dekkerding.engine.domain.model.requirement.RenderTarget;

import java.util.Map;

/**
 * 渲染器端口 —— IR → md 工件的"编译器后端"。
 *
 * <p>【教学注释 · 端口契约的确定性纪律】实现必须保证：
 * 同一 IR + 同一 templateVersion ⇒ 返回的 Map 内容字节级相同
 * （有序、无时间戳、无随机源）——这是 spec-rendering「渲染确定性」的落点，
 * 单测用固定 IR 断言全文字符串即可守住。
 *
 * <p>新增渲染目标 = 新增实现类 + RenderTarget 枚举项，不改既有任何代码。
 */
public interface SpecRenderer {

    /** 本渲染器服务的目标（应用层按此路由） */
    RenderTarget target();

    /** 模板版本（随实现演进递增，写入工件行以追溯） */
    String templateVersion();

    /** 渲染：返回 {相对路径: 文件内容} 的有序映射（openspec 多文件 / vibecoding 单文件） */
    Map<String, String> render(RequirementIR ir);
}
