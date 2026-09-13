# python-embedding-engine 规格（图片跨模态检索增量）

## ADDED Requirements

### Requirement: CLIP 双编码器注册与启用

模型注册表 SHALL 填实 clip 槽位：注册中文图文共空间模型（模型名、维度、modality=cross），与既有文本模型并列。注册表查询（健康/状态端点）SHALL 反映 clip 已启用及其规格；未启用槽位保持 null 可见。

#### Scenario: 注册表可见 clip 规格

- **WHEN** 系统健康或状态查询模型注册表
- **THEN** clip 槽位显示模型名、维度与 cross 模态，而非 null

### Requirement: 图片编码

引擎 SHALL 提供图片编码能力：输入图片数据（路径或字节），输出 L2 归一化的 CLIP 图像向量。同一图片的重复编码 MUST 产生一致向量（确定性）。

#### Scenario: 图片编码确定性

- **WHEN** 同一张图片先后编码两次
- **THEN** 两次输出的向量一致

#### Scenario: 无法解码的图片明确报错

- **WHEN** 输入的图片数据损坏或无法解码
- **THEN** 引擎返回明确错误而非崩溃或输出无意义向量

### Requirement: 跨模态查询文本编码

引擎 SHALL 提供面向图片空间的查询文本编码：输入查询文本，经同一 CLIP 模型的文本编码器输出与图片向量同空间的归一化向量。

#### Scenario: 查询向量与图片向量可比

- **WHEN** 对查询文本（经 CLIP 文本编码）与已摄取图片（经 CLIP 图像编码）计算余弦相似度
- **THEN** 相似度落在有意义区间，语义相关图片得分高于无关图片

### Requirement: 图片路径降级语义

当 CLIP 模型不可用时，图片编码与查询文本编码 SHALL 降级为确定性哈希向量并标记 `degraded=true`，与文本路径的降级纪律一致；MUST NOT 无标志地输出假向量。

#### Scenario: 模型不可用时图片编码降级

- **WHEN** CLIP 模型加载失败（离线且无缓存）时请求图片编码
- **THEN** 引擎返回哈希向量且标记 degraded=true，进程不崩溃

### Requirement: 双通道图片能力一致

Py4J 主通道与 stdio 备通道 SHALL 提供一致的图片编码与查询文本编码能力，协议纪律（JSON 字符串进出、stdio 行协议）MUST 保持不变。主备切换后图片能力 SHALL 照常可用。

#### Scenario: 回退 stdio 后图片编码照常

- **WHEN** Py4J 主通道故障，FailoverChannel 切换到 stdio 备通道
- **THEN** 图片编码与跨模态查询编码的调用照常完成，结果语义一致
