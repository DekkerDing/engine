# python-embedding-engine 规格（mock 标注器与重排器增量）

## ADDED Requirements

### Requirement: vlm 注册槽位与 mock 标注器

引擎注册表 SHALL 新增 `vlm` 槽位，本期填实为确定性 mock 标注器：输入图片路径（及可选覆盖描述），输出结构化标注（subject / description / tags / mocked=true）。mock 标注器 MUST 确定性（同路径同文件名同输出）、MUST NOT 依赖网络与模型下载、MUST 在引擎健康信息中如实报告自身为 mock 实现。

#### Scenario: mock 标注确定性

- **WHEN** 对同一图片路径调用 mock 标注两次
- **THEN** 两次输出 JSON 完全一致，且 mocked=true

#### Scenario: 注册表健康可见

- **WHEN** 查询引擎模型注册表状态
- **THEN** vlm 槽位报告 mock 实现与确定性说明，不伪装为真实模型

### Requirement: 结构化标注操作

双通道（Py4J 与 stdio）SHALL 各提供结构化标注操作：入参含图片存储路径与可选覆盖描述，出参为标注 JSON 字符串（协议纪律不变：JSON 字符串进出）。覆盖描述存在时输出直接由其构造；否则由文件名确定性派生。

#### Scenario: 双通道标注行为一致

- **WHEN** 分别经 Py4J 与 stdio 通道对同一图片请求标注
- **THEN** 两次返回的标注 JSON 一致（含 mocked 标志）

#### Scenario: 带覆盖描述的标注

- **WHEN** 标注入参携带覆盖描述"红色的小花"
- **THEN** 返回 description="红色的小花"，subject 与 tags 由该文本确定性派生

### Requirement: reranker 注册与重排操作

引擎注册表 SHALL 新增 `reranker` 槽位，填实为交叉编码器重排模型（bge-reranker-base，CPU 推理，惰性加载）。双通道 SHALL 各提供重排操作：入参为查询文本与候选文本列表（有序），出参为等长相关性分数列表（顺序与入参一致）。重排器不可用（模型加载失败）时 MUST 返回显式错误由调用方降级，MUST NOT 返回伪造分数。

#### Scenario: 重排打分有序返回

- **WHEN** 对查询"红色的小花"与三条候选描述请求重排
- **THEN** 返回三个分数，与候选顺序一一对应，分数可比较排序

#### Scenario: 重排器不可用显式报错

- **WHEN** 重排模型加载失败时收到重排请求
- **THEN** 操作返回显式错误标志与原因，调用方可据此走不重排降级

### Requirement: 重排的规模约束

重排操作 SHALL 接受候选数上限约束：单次调用候选数超过上限时 MUST 明确报错（由调用方分批），MUST NOT 静默截断。上限值在注册表/配置中可查。

#### Scenario: 超上限拒绝

- **WHEN** 单次重排请求携带超过上限的候选
- **THEN** 返回明确错误与上限值，不产生部分结果
