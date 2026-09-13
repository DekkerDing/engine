# go-toolbox-engine — Go 嵌入引擎能力规约（delta）

## Purpose

让 engine-server 单 JAR 内嵌一个 Go 计算引擎：Java 继续承接全部外部请求（单 JAR 合体架构不变），高 CPU 的工具方法（文本处理、向量检索、哈希向量降级）由 Go 子进程承接，运行时可开关、默认零变化；文件哈希与上传内容寻址去重（原 S2）保留为后续独立变更。

## ADDED Requirements

### Requirement: 通道协议与通用方法派发

系统 SHALL 通过子进程 stdin/stdout 的 JSON 行协议与 Go 引擎通信：请求帧携带 `id`/`method`/`params`，响应帧携带同 `id` 的 `result` 或 `error`；引擎内部以方法注册表派发，新增工具方法 MUST NOT 要求协议层改动。stdout MUST 仅承载协议帧（引擎日志走 stderr，不得混流）。Java 侧通道 SHALL 以单一通用调用（方法名 + 参数 Map → 类型化响应）暴露全部方法。

#### Scenario: 正常调用一问一答
- **WHEN** Java 侧发起 `{"id":1,"method":"text.tokenize","params":{...}}`
- **THEN** 收到 `{"id":1,"result":{...}}`，id 与请求一致

#### Scenario: 未知方法报错
- **WHEN** 调用注册表中不存在的方法名
- **THEN** 收到同 id 的 error 响应（code=1001 明确"方法不存在"语义），进程不退出，后续调用继续可用

#### Scenario: 引擎日志不得污染协议
- **WHEN** 引擎处理请求期间产生日志输出
- **THEN** 日志全部出现在 stderr，stdout 每行仍是合法 JSON 响应帧

### Requirement: 进程生命周期与故障语义

系统 SHALL 在启用时拉起 Go 引擎子进程（二进制定位链：显式配置 → 环境变量 → 本地构建产物 → 开发态 `go run` → jar 内 `classpath:/golang/<platform>/` 解压），启动后以 `sys.ping` 握手确认就绪；子进程意外退出时，在途/后续调用 MUST 立即失败并给出明确错误（不得无限阻塞），健康端点 MUST 反映引擎不可用；应用重启后引擎随启动恢复。调用 MUST 支持超时，超时即失败返回。

#### Scenario: 引擎进程被杀
- **WHEN** Go 子进程运行中被外部杀死，随后发起工具调用
- **THEN** 调用快速失败并携带"引擎已退出"语义的错误，`/api/system/health` 的 `goToolbox` 段状态为 DOWN

#### Scenario: 调用超时
- **WHEN** 一次工具调用超过配置的超时上限未返回
- **THEN** 调用方在超时时刻收到超时错误，不永久阻塞；其后紧邻的下一次调用若引擎已空闲则正常返回（被放弃调用的迟到响应帧 MUST 被丢弃而非错配）

### Requirement: 配置开关与默认零变化

`engine.go.enabled` SHALL 默认为 `false`；为 `false` 时系统行为（API 响应、检索结果、健康 JSON 除 `goToolbox` 段自身外）MUST 与无本变更时一致，且 MUST NOT 拉起 Go 子进程。为 `true` 时装配 Go 通道并按需调用。附加的 `go-toolbox` Spring Profile SHALL 启用"降级向量化"：Go 的哈希向量 provider 接管 TEXT 模态（python provider 让位），用于无 Python 模型环境。

#### Scenario: 默认关闭时行为不变
- **WHEN** 未改动任何配置启动应用并执行上传、检索、健康检查
- **THEN** 全部响应与引入 Go 引擎之前一致，无 Go 子进程被拉起

#### Scenario: 开关切换只动配置
- **WHEN** `engine.go.enabled` 从 `false` 改为 `true` 并重启
- **THEN** 业务代码零改动，工具方法调用改由 Go 引擎承接

#### Scenario: 降级模式接管向量化
- **WHEN** 以 `go-toolbox` Profile（且 `engine.go.enabled=true`）启动并摄取文档
- **THEN** 向量化由 Go 哈希向量 provider 产出（带 degraded 标志），摄取与检索链路正常完成，TEXT 模态不出现重复 provider 注册

### Requirement: 工具方法——文本处理（text.*）

Go 引擎 SHALL 提供 `text.chunk`（句子对齐滑动窗口分块，参数 targetSize/overlap 与 Java 端配置语义一致）、`text.tokenize`（中文按字/英文按词切分，`search` 模式生成 bigram、`exact` 模式去重、默认全量）、`text.keywords`（TF 频次统计 Top-K，权重归一到 [0,1]）。`text.chunk` 的块边界 MUST NOT 切在多字节 UTF-8 字符中间（中文正确性硬约束）。

#### Scenario: 中文分块不产生乱码
- **WHEN** 对含中文长句（无标点、需硬切）的文本执行 `text.chunk`
- **THEN** 每个块的首尾都是完整字符，无 UTF-8 截断乱码

#### Scenario: 分块与 Java 实现一致
- **WHEN** 同一文本分别经 Java 分块器与 `text.chunk`
- **THEN** 块数量、块文本、重叠语义一致（对拍测试覆盖）

#### Scenario: 分词模式语义
- **WHEN** 对 `"红塔在公园"` 分别以 `search` 与 `exact` 模式执行 `text.tokenize`
- **THEN** `search` 返回 bigram（["红塔","塔在","在公","公园"]），`exact` 返回去重单字

### Requirement: 工具方法——向量索引与检索（vector.*）

启用 Go 引擎时，`vector.search`（查询向量 + topK + 空间过滤）、`vector.insert`/`vector.delete`（按 documentId 增删）、`vector.similarity`（两向量余弦）SHALL 可用：Go 侧维护向量索引副本（连续存储、按 (source_type, model_key, dimension) 空间过滤），副本 MUST 与 Java 侧索引保持同步——启用期摄取/删除时增量复制，复制先于摄取完成返回。同查询、同 topK、同过滤条件下，`vector.search` 返回结果集（命中集合与排序）MUST 与 Java 内存索引实现一致（浮点分数容差内）。

#### Scenario: 摄取后立即可检索（副本同步）
- **WHEN** 启用 Go 引擎期间新文档摄取完成（写入返回）后立刻发起检索
- **THEN** 新文档的块已参与本次检索（副本复制先于摄取完成返回）

#### Scenario: 结果一致性
- **WHEN** 同一查询向量、topK、过滤条件分别经 Java 内存索引与 `vector.search`
- **THEN** 命中集合与顺序一致，分数差异在浮点容差（1e-6）内

#### Scenario: 按文档删除联动
- **WHEN** 删除某文档后发起 `vector.search`
- **THEN** 该文档的块不再出现在命中中

### Requirement: 工具方法——哈希向量降级（hashing.generate）

`hashing.generate` SHALL 从文本产生确定性伪向量：同文本同维度 MUST 产出逐字节一致的结果（跨平台/跨进程确定，字节序明确）；支持 `normalize` 参数（L2 归一化，归一后点积=余弦）；结果 MUST 带 `degraded=true` 标志。作为 `go-toolbox` Profile 下的 TEXT EmbeddingProvider 时，embedBatch MUST 与 `hashing.generate` 单条语义一致。

#### Scenario: 确定性
- **WHEN** 同一文本分两次（或两个进程）执行 `hashing.generate`
- **THEN** 两次结果向量逐元素相等

#### Scenario: 归一化语义
- **WHEN** 以 `normalize=true` 执行 `hashing.generate`
- **THEN** 结果向量 L2 模长为 1（容差内），点积即余弦

### Requirement: 构建编排与单 JAR 契约

Go 工程 SHALL 位于 `engine-server/golang/`（模块资产，src 外）；构建 MUST 产出 windows-amd64 与 linux-amd64 双平台二进制并随 bootJar 打入 `classpath:/golang/`；Go 构建 MUST 零三方依赖（仅标准库），在无网络环境可完成；Jenkins 流水线 SHALL 在 Package 阶段自动完成 Go 构建与打包；Docker 镜像与 entrypoint MUST 零改动（仍单 JAR 单进程单容器单端口 8090）。

#### Scenario: 离线构建
- **WHEN** 在无外网环境执行 Gradle 构建（Go 源码已就位）
- **THEN** Go 二进制编译与 jar 打包成功，不发生任何网络下载

#### Scenario: jar 内含双平台二进制
- **WHEN** bootJar 构建完成
- **THEN** jar 的 `classpath:/golang/` 下同时存在 windows-amd64 与 linux-amd64 二进制

#### Scenario: 流水线全绿
- **WHEN** Jenkins 流水线执行（节点具备 JDK8 + Node + Go）
- **THEN** Frontend → Test → Package（含 Go 构建）→ Image 全部通过，镜像行为与本地构建一致

### Requirement: 健康聚合加性扩展

`/api/system/health` 的 `data` 下 SHALL 新增可选键 `goToolbox`（状态与关键运行指标），其余既有键与整体 `status` 判定规则保持不变；前端 MUST 忽略未知键，三卡片渲染不受影响。

#### Scenario: 健康 JSON 前向兼容
- **WHEN** 前端请求 `/api/system/health`
- **THEN** 响应含 `goToolbox` 键，既有 `server`/`engine`/`documents` 键结构与语义不变，前端正常渲染
