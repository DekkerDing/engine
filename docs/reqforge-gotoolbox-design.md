# reqforge — Go 工具箱引擎设计（gotoolbox）

> 面向：后端开发 | 版本：1.0（embed-go-toolbox 变更落地） | 日期：2026-09-13
>
> 本文是仓库第四栈（Go）的架构与设计文档：Go 语言作为**嵌入计算引擎**集成进
> engine-server——Java 门面承接全部外部请求，Go 子进程承接高 CPU 工具方法。
> 行为契约见 [gotoolbox-spec](file:///F:/workspace/engine/docs/reqforge-gotoolbox-spec.md)，
> 上手走读见 [learning-path](file:///F:/workspace/engine/docs/learning-path.md) 的 Go 章节。

---

## 1. 这是什么：一句话与一条先例

**一句话**：单 JAR 里除了 Java（业务）与 Python（真实模型向量化），现在还有 Go——
承接三组「降级/加速」场景：

| 场景组 | Go 方法 | Python 在场时的角色 | 语义 |
|--------|---------|---------------------|------|
| text.* | `text.chunk` / `text.tokenize` / `text.keywords` | 对照实现 | 与 Java/Python 结果对拍一致的纯计算 |
| hashing.* | `hashing.generate` | 降级替身 | Python 不可用时确定性哈希向量兜底（degraded=true） |
| vector.* | `vector.insert` / `vector.delete` / `vector.search` / `vector.similarity` | 无关（索引在 JVM） | 检索的并行加速轨 |

**一条先例**：本设计与 `engine-server/python/` 的引入完全同构——模块资产
（`engine-server/golang/`，src 外）、Gradle 编排入 jar、子进程 + 通道抽象。
理解了 python 轨，go 轨只是换了一种语言与两处决策（见 D4/D5）。

---

## 2. 架构图

```
                    ┌────────────────────────────────────────────────┐
                    │  engine-server (:8090) — 唯一进程、唯一端口      │
   HTTP /api/**     │                                                |
  ─────────────────▶│  interfaces → application → domain ← infra      │
                    │                          ▲            │         │
                    │  摄取链路                 │            ▼         │
                    │  DocumentApplicationService ─▶ EmbeddingProvider │
                    │        │                    (go-toolbox Profile │
                    │        │                     下= GoToolboxProvider)
                    │        ▼                          │             │
                    │  InMemoryVectorIndex ──────────────┤             │
                    │  （JVM 真相/工作集）                │             │
                    │   │ replace/remove ──同步复制──▶ GoVectorReplica │
                    │   │ search ◀──三元组命中回表──── （只认第一级开关）│
                    │   │（空结果/异常/回表 miss ⇒ 降级本地扫描）        │
                    │   ▼                                │             │
                    │  SQLite（真相库）  Lucene（全文）    │ stdio      │
                    └────────────────────────────────────┼─────────────┘
                                                         │ JSON 行协议
                                                         ▼
                              ┌──────────────────────────────────┐
                              │  toolbox（Go 子进程，常驻）        │
                              │  stdin  ◀─ 请求帧 {"id","method","params"}
                              │  stdout ─▶ 响应帧 {"id","result"|"error"}
                              │  stderr ─▶ 日志（横幅/panic）       │
                              │                                  │
                              │  router: map[string]Handler 注册表 │
                              │  ├─ text.*    分块/分词/关键词      │
                              │  ├─ hashing.* 确定性哈希向量        │
                              │  ├─ vector.*  副本索引 + 并行扫描   │
                              │  └─ sys.*     ping/shutdown/stats  │
                              └──────────────────────────────────┘
```

读图三个要点：

1. **请求只进 Java**：Go 不监听任何端口、不见任何 HTTP——它是被 stdin 驱动的
   计算引擎（D1 方向反转）。
2. **InMemoryVectorIndex 是枢纽**：既是真相工作集，又是副本同步的源头、Go 轨
   与本地轨的路由点、对拍基准——四个角色一个类（见 `InMemoryVectorIndex.search`
   四参版的路由注释）。
3. **降级路径永远在**：Go 轨任何一环失败（通道异常/空结果/回表 miss）都落回
   JVM 本地扫描——正确性的最终裁决在 Java 侧，Go 只是加速器。

---

## 3. 关键设计决策（D1–D10）

以下决策全文见 `openspec/changes/embed-go-toolbox/design.md`，此处为落地后的
定稿摘要 + 代码锚点。

### D1 · 方向反转：Java 门面 + Go 引擎

否决「Go 侧车反代」（双进程复活）与「c-shared + JNI」（双 GC 同进程、桥接量 ∝
API 面）。Java 继续当门面（入口零变动），Go 作为嵌入引擎做计算——与 python/
先例同构。旧 Go 网关已在单 JAR 合体时退役，本变更不复活它。

### D2 · 通道：stdio JSON 行

继承 `StdioChannel` 全部智慧：零 socket（Windows/Linux 行为一致）、常驻读线程 +
`BlockingQueue`、进程退出毒丸 `__PROCESS_EXITED__`、`synchronized call()`
串行化、stdout 专线日志走 stderr。Go 启动毫秒级（对比 Python 模型加载 10-30s），
eager 拉起成本可忽略。实现：`infrastructure/go/GoStdioChannel.java`。

### D3 · 协议帧与方法注册表

帧格式与 `server_stdio.py` 同构。Go 侧核心是 `map[string]Handler` 注册表
（`golang/internal/router/`）——新增工具 = 新增一个包 + 一行注册。Java 侧
`GoProtocol` 集中 DTO 解析，各门面自行 `extractResult`。向量大负载用 base64
float32 little-endian 编码（JSON 数组慢且体积 4-5 倍）。

### D4 · 索引副本同步（含金量最高的决策）

打分向量在 JVM 堆里，Go 扫描需要数据。否决「每查询传全矩阵」（MB 级/查询）与
「mmap 共享内存」（跨平台复杂，v2 再议），采纳**副本同步**：

- 摄取时 `InMemoryVectorIndex.replace/remove` 在本地更新（持锁）后、返回前，
  **同步**复制进 Go（锁外通道 IO）——「复制先于摄取返回」的一致性边界；
  副本失败只 warn 不上抛（副本是加速器不是真相，缺失部分由检索本地轨兜底）。
  实现：`InMemoryVectorIndex.replicateToGo`。
- 启动灌入零新代码：preload 的 `index.replace` 循环天然带复制。
- Go 侧存储：`map[docId][]indexedEntry` 分组 + 预归一化打分向量
  （`golang/internal/vector/index.go`）——点积即余弦。

### D5 · 并行扫描与结果一致性

分片并行（goroutine × ~GOMAXPROCS，WaitGroup 归并局部 top-K），但**定序合同**
才是对拍的前提：分数量化（`math.Round(score*1e6)` 整数格点）降序 →
documentId 字典序 → chunkIndex 升序。Java `topK()` 与 Go `sortHits()` 逐字同款
（`|a-b|<ε` 容差比较不满足传递性，喂给排序是未定义行为——量化是浮点工程标准手法）。
空间闸门判定单源留在 Java 本地轨：Go 空结果经本地轨重算，闸门 400 文案与
关闭态逐字一致。实现：`golang/internal/vector/search.go`（`SearchParallel`
三阶段：持锁快照采集 → 分片无锁计算 → 归并）。

### D6 · 进程生命周期

enabled=true 应用启动即拉起（eager）+ 首帧 `sys.ping` 握手；`@PreDestroy` 发
`sys.shutdown`（超时强杀）；崩溃 = 读线程 EOF → 毒丸 → 在途/后续调用快败，
`goToolbox` 健康 DOWN，重启应用即恢复（v1 不自动重启子进程）。

### D7 · 两级装配门控

| 层级 | 开关 | 控制什么 | 关闭时 |
|------|------|----------|--------|
| 第一级 | `engine.go.enabled`（默认 false） | 通道三件套（Launcher/Channel）+ GoVectorReplica | 零 Bean 零进程零解压，行为与基线一致 |
| 第二级 | `go-toolbox` Profile + enabled | GoToolboxProvider（降级向量化） | Python provider 复位接管 |

两级缺一不可：只开 Profile 没通道会装配失败（Provider 依赖通道）；只开 enabled
是合法态（场景二 vector.* 加速独立启用，向量化仍走 Python）。健康段
`goToolbox` 只认第一级——探活实现者是 `GoStdioChannel`（而非挂第二级的
GoToolboxProvider），场景二独立启用时健康照样 UP。

### D8 · 构建编排（双平台交叉编译）

`build.gradle` 的 `buildGoToolbox`（GOOS=windows/linux amd64 交叉编译，
`CGO_ENABLED=0` 静态链接、`-trimpath` 可复现、源码指纹未变跳过、无工具链
warn 作废不炸构建）→ `packageGolang`（Copy → `build/golang-pack/golang/<平台>/`）
→ sourceSets 注册 → 随 bootJar 入 `BOOT-INF/classes/golang/`。运行时
`GoProcessLauncher` 解压到 `./go-runtime/<platform>/` 执行。零三方依赖
（`go list -m all` 只有自身），`GOPROXY=off` 可全量构建。

### D9 · 教学注释规约

Go 文件头统一结构：包职责一句话 →【教学注释】Go 概念块（每个新概念锚定 Java
对应物）。本文第 5 节的速成路线图即按此规约组织。

### D10 · 合并裁决：手写 `infrastructure/go/` 为正典

实施期间维护者并行手写的 `infrastructure/go/` 与任务清单落地的
`infrastructure/golang/` 撞车。裁决：手写包为正典（类型化协议 + 业务门面更
完整），任务产物并入（通道硬化、classpath 生产轨、装配门控默认关、单测随迁），
`infrastructure/golang/` 删除。Go 侧 `internal/text`、`internal/hashing` 为
手写成果直接采用，`appendChunk` 的 byte 硬切修正为 rune 感知硬切
（spec 中文正确性硬约束优先于性能近似）。

---

## 4. 组件地图

### Go 侧（`engine-server/golang/`，零三方依赖，Go 1.21+）

| 路径 | 职责 | 教学点 |
|------|------|--------|
| `cmd/toolbox/main.go` | 入口：装配 router → 注册方法 → 协议循环 | main 包与三步装配 |
| `internal/protocol/` | 帧结构体 + JSON 编解码 | struct tag、error 值 vs 异常 |
| `internal/router/` | `map[string]Handler` 注册表 | 接口隐式实现 vs implements |
| `internal/engine/engine.go` | 门面：全部方法注册 + sys.stats | 方法命名空间、包组织 |
| `internal/text/` | 分块/分词/关键词 | rune vs byte（中文 UTF-8） |
| `internal/hashing/` | 确定性哈希向量 | sha256、值域映射、归一化 |
| `internal/vector/index.go` | 分组副本 + 预归一化 | map delete、切片深拷贝陷阱 |
| `internal/vector/search.go` | 并行分片扫描 + 量化定序 | goroutine、WaitGroup、闭包传参 |

### Java 侧（`infrastructure/go/`，D10 正典）

| 类 | 职责 |
|----|------|
| `GoProcessLauncher` | 二进制定位链（直指 → golang/ 直用 → go run 开发轨 → classpath 解压轨） |
| `GoStdioChannel` | stdio 通道（毒丸/串行/迟到帧丢弃）+ 健康探活（GoToolboxStatusQuery 实现） |
| `GoProtocol`（`go/protocol/` 子包） | 帧 DTO 与解析（键名契约单源） |
| `GoToolboxProvider` | 降级向量化门面（第二级门控，实现 EmbeddingProvider） |
| `GoVectorReplica` | 副本同步门面（第一级门控，vector.* 四方法） |
| `InMemoryVectorIndex` | 路由/闸门/兜底/对拍基准（infrastructure/search，四个角色一个类） |

---

## 5. Go 速成路线图（按代码走读顺序）

> 前提：你会 Java。每一站都是「先看 Go 代码，再看右侧 Java 对应物」，
> 每站 30-60 分钟。全部走完 ≈ 1.5 天，你就能独立给这个引擎加方法。

### 第 0 站 · 心智转换（15 分钟）

| Go | Java | 仓库样例 |
|----|------|----------|
| 包 = 目录，import 即可见 | class + package + classpath | `golang/internal/router/router.go` |
| 首字母大写 = public | `public` 修饰符 | `router.Register` vs `register` |
| `err` 返回值 | try/catch 异常 | `golang/internal/protocol/` 各函数 |
| `defer` | try-finally | `cmd/toolbox/main.go` |
| 无类，struct + 方法 | class | `internal/vector/index.go` 的 `Index` |

### 第 1 站 · 入口与协议循环：`cmd/toolbox/main.go` + `internal/protocol/`

最小的完整闭环：读一行 JSON → 找 Handler → 写一行 JSON。看懂
`json.Marshal/Unmarshal` 与 struct tag（`json:"id"`），对照 Java 的
Jackson 注解。注意 Go 的「未知方法不退出、坏帧 id=0 应答」错误纪律。

### 第 2 站 · 注册表：`internal/router/`

`map[string]Handler` 一张表 = Spring 的 `@RequestMapping` 方法表。
新增方法零协议改动的秘密就在这：写个函数，注册一行。

### 第 3 站 · 注册全家福：`internal/engine/engine.go`

所有方法的一站式样：每个 handler 的参数结构体、边界校验、错误翻译
（panic 转 1002 帧）。看 `vectorSearchParams` 的 tag 与 Java `GoProtocol`
的 DTO —— 跨语言契约必须钉死键名。

### 第 4 站 · 中文处理：`internal/text/`

Go 的 `string` 是不可变 byte 序列——中文一个字 3 字节，`len()` 不是字数、
`s[i]` 是半个字。`[]rune(s)` 才是 Java `String.charAt` 的对应物。
分块器 `appendChunk` 的 rune 感知硬切是本站主菜（对照 Java `TextChunker`）。

### 第 5 站 · 确定性哈希：`internal/hashing/`

sha256 → 分桶 → [-1,1) 映射 → L2 归一化。纯算法站，无新语言概念，
但它是 hashing.generate 降级模式的实现体（对照 Java 侧无此实现——降级
专属 Go）。

### 第 6 站 · 副本索引：`internal/vector/index.go`

`map[docId][]indexedEntry` 分组 + 预归一化打分向量。两个 Go 陷阱在注释里：
map delete 是内置函数（不是方法）；GetAllEntries 的切片共享底层数组必须
深拷贝（对照 Java 数组拷贝习惯）。

### 第 7 站 · 并行扫描：`internal/vector/search.go` ⭐ 全文档最重要的一站

三阶段：持锁快照采集 → goroutine 分片无锁计算（局部 top-K）→ WaitGroup
归并。对照 Java：WaitGroup ≈ CountDownLatch；goroutine 显式传参 vs Java
闭包捕获循环变量；「局部 top-K 并集包含全局 top-K」的论证在注释里。
量化定序 `sortHits` 与 Java `InMemoryVectorIndex.topK()` 逐字对齐——
两份实现读懂一份，另一份免费。

### 第 8 站 · 回到 Java：通道与路由

按此顺序：`GoProcessLauncher`（定位链五级）→ `GoStdioChannel`（毒丸/
串行/迟到帧）→ `GoVectorReplica`（副本门面）→ `InMemoryVectorIndex`
（路由 + 三态降级 + 空间闸门单源）。这一站回答「Go 的计算如何被 Java
安全地使用」。

### 毕业考

给引擎加一个 `text.length`（返回 rune 数与 byte 数）方法：Go 侧一个函数 +
一行注册 + 一个单测；Java 侧 GoProtocol 加 DTO + 门面加方法。全程不需要
动协议循环——这就是 D3 注册表设计的验收方式。

---

## 6. 构建与部署速查

```bash
# 构建（含 Go 双平台交叉编译，产物入 jar）
./gradlew :engine-server:bootJar
unzip -l engine-server/build/libs/engine-server.jar | grep golang/   # 双平台断言

# 本机无 Go 工具链时：warn 跳过不炸构建（jar 缺 golang/，运行时走 go run 轨需本机 Go）
./gradlew :engine-server:buildGoToolbox -PskipGoBuild               # 显式关闭

# 运行（生产轨：任意目录纯 jar，自动解压 ./go-runtime/<platform>/）
java -jar engine-server.jar --spring.profiles.active=go-toolbox --engine.go.enabled=true

# 离线构建证明（零三方依赖）
GOPROXY=off ./gradlew :engine-server:buildGoToolbox
```

健康观测：`GET /api/system/health` 的 `goToolbox` 段
（`{enabled, status: N/A|UP|DOWN, version, vectorCount, tools, lastError}`），
不参与整体 status 判定（加速器语义：DOWN 时检索自动降级本地扫）。

---

## 7. 与既有文档的关系

| 文档 | 本次变化 |
|------|----------|
| `reqforge-gotoolbox-spec.md` | 新增：协议帧、方法注册表、副本同步、开关矩阵 |
| `learning-path.md` | 增补：Go 章节（按本文第 5 节路线图展开） |
| `reqforge-dev-guide.md` | 改写：构建需 Go、开关说明、常见排查 |
| `architecture.md` | 不变（Go 是基础设施实现细节，DDD 分层无感知） |
