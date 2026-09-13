package io.github.dekkerding.engine.domain.repository;

import io.github.dekkerding.engine.domain.model.image.ImageAnnotation;

/**
 * 图片语义拆分端口（按槽位可插拔）—— vlm 槽位在领域侧的投影。
 *
 * <p>【依赖倒置落地】摄取管线只依赖本接口；infrastructure 层的
 * ChannelAnnotationProvider 实现它（内部走 PythonChannel → Python 子进程的
 * vlm 槽位）。测试可注入假实现（返回固定标注），领域逻辑不碰 Python。
 *
 * <p>【mock 先行的替换接缝】本期实现是确定性 mock（mocked=true 显式透传）；
 * 真实 VLM（GLM-4V / qwen-vl / 本地模型）到位后新增/替换实现类即可，
 * 本接口与 {@link ImageAnnotation} 字段契约零改动。
 *
 * <p>【错误语义】标注失败抛异常（EngineException 族），由调用方决定降级
 * （图片仍入库走像素路，标注字段置空并记录原因——spec：标注失败不阻塞入库）。
 */
public interface AnnotationProvider {

    /**
     * 对单张图片产出结构化标注。
     *
     * @param imagePath 图片存储路径（存储层以 uuid 重命名原件；真实 VLM 消费像素）
     * @param filename  调用方的原始文件名（上传时的名字）——mock 的文件名派生用它，
     *                  用存储路径派生只会得到无语义的 uuid 片段
     * @param caption   可选的调用方覆盖描述（上传入参）；null/空串 = 未提供
     * @return 结构化标注（mock 实现恒带 mocked=true）
     */
    ImageAnnotation annotate(String imagePath, String filename, String caption);
}
