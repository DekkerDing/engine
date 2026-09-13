# reqforge — Go 工具箱引擎规约（gotoolbox spec）

> 面向：后端开发、运维 | 版本：1.0（embed-go-toolbox 变更落地） | 日期：2026-09-13
>
> 本文是 Go 嵌入引擎的**行为契约**：协议帧格式、方法注册表（逐方法参数/响应
> 键名）、副本同步语义、开关矩阵、生命周期与故障语义。设计与动机见
> [gotoolbox-design](file:///F:/workspace/engine/docs/reqforge-gotoolbox-design.md)。
> 键名即合同——Go 端 struct tag 与 Java 端 `GoProtocol`/门面组装必须逐字一致，
> 改键名 = 破坏性变更，须两侧同步。

---

## 1. 协议帧（stdio JSON 行）

传输：子进程 stdin/stdout，一行一个 JSON 帧（`\n` 分隔）；stderr 专线日志。
**stdout 每行必须是合法响应帧**——引擎日志/panic 输出走 stderr，不得混流。

```
请求:  {"id":<int>,"method":"<namespace.method>","params":{...}}
响应:  {"id":<同请求>,"result":{...}}
错误:  {"id":<同请求或0>,"error":{"code":<int>,"message":"..."}}
```

id 纪律：Java 侧单调递增；响应 id < 当前期望 = 迟到帧（上一次超时被放弃的
响应）→ 丢弃并 warn，绝不误配给下一个调用；坏 JSON 帧 → error 帧且 id=0。

### 错误码表

| code | 语义 | 场景 |
|------|------|------|
| 1001 | 方法不存在 | method 不在注册表；进程不退出，后续调用正常 |
| 1002 | 参数/内部错误 | 参数缺失、维度不匹配、JSON 解析失败、handler panic（recover 后翻译） |

超时与崩溃（Java 侧 `GoStdioChannel` 纪律）：单次调用上限
`engine.go.stdio-timeout-seconds`（默认 60s）；进程退出 → 读线程投毒丸
`__PROCESS_EXITED__` → 在途/后续调用立即失败（"引擎已退出"语义），不无限阻塞；
健康段 DOWN；应用重启即恢复（v1 不自动重启子进程）。

---

## 2. 方法注册表（11 方法）

Go 侧 `map[string]Handler`（`golang/internal/router/`），新增方法 = 新增包 +
一行注册，协议层零改动。命名空间：`sys.*` / `text.*` / `hashing.*` / `vector.*`。

### sys.*（生命周期）

| 方法 | 参数 | result 键 | 语义 |
|------|------|-----------|------|
| `sys.ping` | `{}` | `status:"pong"`, `version` | 握手/探活 |
| `sys.shutdown` | `{}` | `status:"bye"` | 优雅退出（退出码 0；stdin EOF 同效） |
| `sys.stats` | `{}` | `engine`, `version`, `vector_count`, `tools`(字典序) | 运行指标（vector_count = 副本真实行数） |

### text.*（与 Java/Python 对拍的纯计算）

| 方法 | 参数 | result 键 | 语义 |
|------|------|-----------|------|
| `text.chunk` | `text`, `target_size`, `overlap_sentences`（另有身份键 `document_id`） | `chunks`(块列表), `count` | 句子对齐滑窗分块；块边界**不得**切在多字节 UTF-8 字符中间（中文硬约束，rune 感知硬切） |
| `text.tokenize` | `text`, `mode`（`search`/`exact`/缺省全量） | `tokens` | 中文按字/英文按词；search 生成 bigram，exact 去重单字 |
| `text.keywords` | `text`, `top_k` | `keywords:[{term, weight}]` | TF 频次 Top-K，weight 归一到 [0,1] |

### hashing.generate（降级向量化）

| 方法 | 参数 | result 键 | 语义 |
|------|------|-----------|------|
| `hashing.generate` | `text`, `dim`, `normalize` | `vector`, `dim`, `degraded:true` | sha256 确定性伪向量；同文本同维度逐元素一致（跨进程确定）；normalize=true 时 L2=1（点积=余弦） |

`go-toolbox` Profile 下作为 TEXT EmbeddingProvider：`embedBatch` 与单条
`hashing.generate` 同参逐元素一致；向量 modelKey=`go-hash-degraded`、
dim=512、degraded=true 贯穿存储与展示。

### vector.*（索引副本 + 并行检索）

| 方法 | 参数 | result 键 | 语义 |
|------|------|-----------|------|
| `vector.insert` | `document_id`, `entries:[{doc_id, chunk_idx, vector, source_type, model_key}]` | `inserted`, `total` | 按 documentId 幂等整体替换 |
| `vector.delete` | `document_id` | `removed`(条数), `total` | 删除该文档全部条目 |
| `vector.similarity` | `vector_a`, `vector_b` | `score` | 两向量余弦（预归一化点积） |
| `vector.search` | `query`, `top_k`, `source_type`?, `model_key`?（空过滤键缺席） | `hits:[{document_id, chunk_index, score}]`, `count` | 并行分片扫；命中只回三元组（回表在 Java 主索引） |

> 【命名差异须知】insert 条目用 `doc_id`/`chunk_idx`（复用 `vector.Entry`
> 存储 tag），search 命中用 `document_id`/`chunk_index`——历史沿革，两侧
> 已按此实现钉死，消费方以本文为准。

---

## 3. 副本同步语义（vector.* 一致性边界）

- **同步复制**：摄取链路 `InMemoryVectorIndex.replace/remove` 在本地更新后、
  方法返回前完成副本复制——「复制先于摄取返回」。摄取完成即可经
  `vector.search` 命中新块。
- **副本失败容忍**：复制失败只 warn 不上抛（副本是加速器不是真相），摄取
  永不因副本失败；缺失部分由检索本地轨兜底。
- **启动灌入**：preload 的 `index.replace` 循环天然带复制——重启后副本回满。
- **检索三态降级**（Go 轨 → 本地轨）：通道异常 / Go 空结果（区分不了真空与
  未同步）/ 回表 miss（副本有主索引没有）→ 一律本地扫描重算。空间闸门判定
  单源在本地轨：同模态全不匹配 → 400 + 修复指引，文案与关闭态逐字一致。
- **定序合同（双实现对拍前提）**：量化分数（`round(score*1e6)` 格点）降序 →
  documentId 字典序 → chunkIndex 升序。Java `topK()` 与 Go `sortHits()`
  逐字同款；同查询同 topK 同过滤，命中集合与顺序恒等、分数容差 1e-6。
- **空间过滤**：`(source_type, model_key)` + 维度匹配才打分；维度不匹配的
  条目跳过（数学硬校验）。

---

## 4. 开关矩阵（两级装配门控）

| `engine.go.enabled` | `go-toolbox` Profile | 通道/Replica | 降级向量化 | 检索路由 | 健康段 |
|--------------------|----------------------|--------------|------------|----------|--------|
| false（默认） | 任意 | 不装配（零 Bean 零进程零解压） | 否 | 本地扫 | `N/A` |
| true | 无 | 装配 | 否（Python provider 接管） | Go 轨优先 + 本地兜底 | `UP`/`DOWN` |
| true | 有 | 装配 | 是（Go 哈希接管 TEXT，python provider 让位） | 同上 | `UP`/`DOWN` |
| false | 有 | 不装配 | **装配失败快败**（Provider 依赖通道） | — | — |

- false = 默认零变化：API 响应/检索结果/健康 JSON（除 `goToolbox` 段自身）
  与无本变更时一致，不拉起 Go 子进程。
- 开关切换只动配置：业务代码零改动，重启生效。
- 同模态重复 provider 注册启动即炸——降级模式必须以 Profile 让位，两级缺一不可。

---

## 5. 健康段契约

`GET /api/system/health` 的 `data.goToolbox`：

```json
{ "enabled": true, "status": "UP", "version": "0.2.0",
  "vectorCount": 0, "tools": ["hashing.generate", "..."], "lastError": null }
```

- 关闭态：`{"enabled": false, "status": "N/A"}`（显式呈现，非缺席）。
- UP 定义：通道存活**且** `sys.stats` 真实往返成功（isAlive 只证读线程在）。
- **不参与整体 status 判定**（加速器语义：DOWN 时检索降级本地扫，服务仍正确）。
- 其余既有键与整体判定规则不变；前端忽略未知键（TS 类型只声明四键）。

---

## 6. 构建与部署契约

- 工程位于 `engine-server/golang/`（模块资产，src 外）；Go 1.21+；
  **零三方依赖**（`go list -m all` 仅自身）——`GOPROXY=off` 可全量构建。
- bootJar 产物内 `classpath:/golang/` 同时存在 `windows-amd64/toolbox.exe`
  与 `linux-amd64/toolbox`（双平台交叉编译，`CGO_ENABLED=0` 静态链接）。
- 本机无 Go 工具链：构建 warn 跳过不炸（jar 缺 golang/，运行时须本机 Go 走
  `go run` 开发轨或保持关闭态）；`-PskipGoBuild` 显式关闭。
- 二进制定位链（运行时）：`engine.go.binary` 直指 → `ENGINE_GO_BINARY` 环境变量
  → `./golang/toolbox(.exe)` 直用 → `./golang/cmd/toolbox/main.go` 走 `go run`
  开发轨 → jar 内 `classpath:/golang/<platform>/` 解压到 `./go-runtime/<platform>/`
  （生产轨，大小比对防重复解压；非 Windows 自动 chmod +x）。
- Jenkins：Package 阶段经依赖链自动完成 Go 构建打包；节点要求 JDK8 + Node +
  Go 1.21+；`GOPROXY=https://goproxy.cn`（防御性，零依赖下不触网）。
- Docker 镜像与 entrypoint 零改动：仍单 JAR 单进程单容器单端口 8090，
  linux 二进制在容器内由解压轨落地。

---

## 7. 与 spec delta 对照（验证清单）

| spec delta Requirement | 本文章节 |
|------------------------|----------|
| 通道协议与通用方法派发 | §1 协议帧 + §2 注册表 |
| 进程生命周期与故障语义 | §1 错误码/超时/毒丸 + §6 定位链 |
| 配置开关与默认零变化 | §4 开关矩阵 |
| 工具方法——文本处理 text.* | §2 text.* 表 |
| 工具方法——向量索引与检索 vector.* | §2 vector.* 表 + §3 副本同步 |
| 工具方法——哈希向量降级 hashing.generate | §2 hashing 表 |
| 构建编排与单 JAR 契约 | §6 构建与部署 |
| 健康聚合加性扩展 | §5 健康段 |
