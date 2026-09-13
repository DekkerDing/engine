# image-annotation 规格（图片语义拆分，mock 先行）

## Purpose

为每张入库图片生成结构化语义标注（主题、描述、标签），使图片拥有可解释、可检索、可重排的文本语义身份；本期以确定性 mock 实现串通全链路，并为真实视觉语言模型预留无破坏替换接缝。

## ADDED Requirements

### Requirement: 结构化语义拆分

系统 SHALL 对每张通过摄取管线入库的图片产出结构化标注，字段至少包含：主题（subject，短语级）、描述（description，自然语言句子）、标签（tags，词列表）。标注结果 MUST 持久化于图片资产元数据，并经图片列表、详情与检索命中响应透出。

#### Scenario: 上传即拆分

- **WHEN** 一张图片完成上传并通过校验进入摄取管线
- **THEN** 管线在向量化之前产出该图片的结构化标注（subject / description / tags）并持久化，图片详情可读取到三个字段

#### Scenario: 标注字段在检索命中中透出

- **WHEN** 一次图片检索命中某张照片
- **THEN** 命中项携带该照片的 subject 与 description，前端可直接渲染

### Requirement: mock 标注的确定性与显式标志

本期标注实现 SHALL 为确定性 mock：同一输入（图片字节与文件名）MUST 产出完全相同的标注结果。所有 mock 产出的标注 MUST 携带显式 `mocked=true` 标志，并全链路透传至 API 响应与前端展示；真实标注器上线后 `mocked=false`。系统 MUST NOT 将 mock 标注伪装为真实识别结果。

#### Scenario: 同图同标注

- **WHEN** 同一图片文件（同字节、同文件名）被标注两次
- **THEN** 两次产出的 subject / description / tags 完全一致

#### Scenario: mocked 标志透传

- **WHEN** mock 标注器产出标注并经 API 透出
- **THEN** 响应携带 `mocked=true` 标志，前端据此展示"模拟识别"提示

### Requirement: 上传时的描述覆盖入参

上传端点 SHALL 接受可选的描述入参（caption）：提供时 mock 标注器 MUST 直接采纳该文本构造标注（描述=入参原文，主题与标签由该文本派生），使 mock 阶段的语义链路可被调用方控制与验证；未提供时按确定性规则从文件名派生。该入参语义在真实 VLM 上线后保持为"人工修正覆盖"。

#### Scenario: 带 caption 上传

- **WHEN** 上传一张照片并携带 caption="红色的小花"
- **THEN** 该照片的标注 description 为"红色的小花"，subject 与 tags 从该文本派生，且 mocked=true

#### Scenario: 不带 caption 上传

- **WHEN** 上传一张照片且不携带 caption
- **THEN** 标注由文件名按确定性规则派生，mocked=true，不报错

### Requirement: 标注失败的降级语义

标注阶段 SHALL 不阻塞图片入库：标注失败（或标注器不可用）时图片 MUST 仍完成存储与像素路向量化，标注字段置空并记录失败原因；检索与重排对无标注图片按既有降级纪律处理（单路召回、不参与重排文本打分）。

#### Scenario: 标注失败不丢图

- **WHEN** 标注器对某图片抛出异常
- **THEN** 该图片仍完成存储与 CLIP 向量化，标注字段为空，资产详情可见标注失败原因

### Requirement: 真实 VLM 替换接缝

系统 SHALL 以注册槽位 + 统一协议操作的方式隔离标注器实现：mock 与未来真实 VLM 在引擎注册表、通道协议与 Java 侧路由中占同一接缝。替换实现 MUST NOT 改变标注的字段契约（subject / description / tags / mocked）与既有持久化结构。

#### Scenario: 槽位可换实现

- **WHEN** 注册表中 vlm 槽位从 mock 实现切换为真实 VLM 实现
- **THEN** 摄取管线、API 字段与持久化结构无需改动，仅 mocked 标志变为 false
