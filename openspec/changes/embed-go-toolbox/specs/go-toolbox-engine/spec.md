# go-toolbox-engine — Go 嵌入引擎能力规约（delta）

## Purpose

让 engine-server 单 JAR 内嵌一个 Go 计算引擎：Java 继续承接全部外部请求（单 JAR 合体架构不变），高 IO / 高计算的工具方法（文件哈希、并行向量扫描）由 Go 子进程承接，运行时可开关、Java 实现永远兜底。

## ADDED Requirements

### Requirement: 通道协议与通用方法派发

系统 SHALL 通过子进程 stdin/stdout 的 JSON 行协议与 Go 引擎通信：请求帧携带 `id`/`method`/`params`，响应帧携带同 `id` 的 `result` 或 `error`；引擎内部以方法注册表派发，新增工具方法 MUST NOT 要求协议层改动。stdout MUST 仅承载协议帧（引擎日志走 stderr，不得混流）。

#### Scenario: 正常调用一问一答
- **WHEN** Java 侧发起 `{id:1, method:"hash.file", params:{path:"..."}}`
- **THEN** 收到 `{id:1, result:{...}}`，id 与请求一致

#### Scenario: 未知方法报错
- **WHEN** 调用注册表中不存在的方法名
- **THEN** 收到同 id 的 error 响应（明确"方法不存在"语义），进程不退出，后续调用继续可用

#### Scenario: 引擎日志不得污染协议
- **WHEN** 引擎处理请求期间产生日志输出
- **THEN** 日志全部出现在 stderr，stdout 每行仍是合法 JSON 响应帧

### Requirement: 进程生命周期与故障语义

系统 SHALL 在首次使用前从 jar 的 `classpath:/golang/` 解压当前平台（`os.name`/`os.arch` 匹配）二进制到本地运行时目录并拉起子进程；子进程意外退出时，在途/后续调用 MUST 立即失败并给出明确错误（不得无限阻塞），健康端点 MUST 反映引擎不可用；应用重启后引擎随首次使用自动恢复。调用 MUST 支持超时，超时即失败返回。

#### Scenario: 引擎进程被杀
- **WHEN** Go 子进程运行中被外部杀死，随后发起工具调用
- **THEN** 调用快速失败并携带"引擎不可用"语义的错误，`/api/system/health` 的 `goToolbox` 段状态为 DOWN

#### Scenario: 调用超时
- **WHEN** 一次工具调用超过配置的超时上限未返回
- **THEN** 调用方在超时时刻收到超时错误，不永久阻塞

### Requirement: 配置开关与默认零变化

`engine.go.enabled` SHALL 默认为 `false`；为 `false` 时系统行为（API 响应、检索结果、健康 JSON 除 `goToolbox` 段自身外）MUST 与无本变更时逐字节一致。为 `true` 时业务代码 MUST NOT 感知实现切换——同一 domain 端口，按配置装配 Go 适配器或 Java 现役实现。

#### Scenario: 默认关闭时行为不变
- **WHEN** 未改动任何配置启动应用并执行上传、检索、健康检查
- **THEN** 全部响应与引入 Go 引擎之前一致，Go 子进程不拉起（或仅健康探测不参与业务）

#### Scenario: 开关切换只动配置
- **WHEN** `engine.go.enabled` 从 `false` 改为 `true` 并重启
- **THEN** 业务代码零改动，工具方法调用改由 Go 引擎承接

### Requirement: 工具方法——文件流式哈希（S2）

系统 SHALL 提供文件 SHA-256 哈希工具方法：对流式读取计算（内存占用 MUST 有界，与文件大小无关）；MUST 支持一次一批多文件的并行哈希；文件路径 MUST 校验落在允许的目录（上传根目录）内，越界路径 MUST 拒绝并报错。

#### Scenario: 大文件流式哈希
- **WHEN** 对一个远超可用内存的文件请求哈希
- **THEN** 成功返回正确 SHA-256，进程内存占用保持有界

#### Scenario: 批量并行哈希
- **WHEN** 一次请求包含 N 个文件路径
- **THEN** 返回全部文件的哈希结果，处理期间多核并行

#### Scenario: 路径越界拒绝
- **WHEN** 请求的文件路径经规范化后位于上传根目录之外
- **THEN** 返回明确的安全拒绝错误，不读取该文件

### Requirement: 上传内容寻址去重（S2 业务接入）

启用 Go 引擎时，文档上传链路 SHALL 先计算文件内容哈希：库内已存在同哈希文件时 MUST 跳过重复摄取（复用既有内容），否则走正常摄取并存哈希。该行为 MUST 可通过现有去重/摄取语义验证，不改变其余上传契约（大小限制、类型校验等）。

#### Scenario: 重复文件秒传
- **WHEN** 同一文件内容第二次上传
- **THEN** 系统跳过解析与向量化，直接关联既有内容，上传显著快于首次

#### Scenario: 不同内容不误伤
- **WHEN** 上传内容不同的同名文件
- **THEN** 按新文档正常摄取

### Requirement: 工具方法——并行余弦 top-K 扫描（S1）

启用 Go 引擎时，语义检索的向量扫描 SHALL 由 Go 引擎承接：Go 侧维护向量索引副本（打分向量存储与查询向量同维、同 (模态, 模型) 空间过滤语义与 Java 实现一致）；副本 MUST 与 Java 侧索引保持同步——启动全量灌入，摄取/删除时增量复制，复制完成后立即可检索。同查询、同 topK、同过滤条件下，返回结果集（命中集合与排序）MUST 与 Java 实现一致（浮点分数容差内）；空间闸门语义（同模态存在但模型/维度不匹配时报 400 并给出修复指引）MUST 保持。

#### Scenario: 结果一致性
- **WHEN** 同一查询向量分别经 Java 实现与 Go 引擎检索
- **THEN** 两者的命中集合与顺序一致，分数差异在浮点容差内

#### Scenario: 摄取后立即可检索（副本同步）
- **WHEN** 新文档摄取完成（写入返回）后立刻发起检索
- **THEN** 新文档的块已参与本次检索（副本复制先于摄取完成返回）

#### Scenario: 空间闸门语义保持
- **WHEN** 库内同模态向量因编码模型切换而与查询不可比
- **THEN** 返回 400 与修复指引，与 Java 实现的报错行为一致

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
