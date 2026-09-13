# embed-go-toolbox — 技术设计

## Context

单 JAR 合体（single-jar-consolidation，已完成 28/28）是基线：`:8090` 唯一端口、单进程单容器、Java 承接全部外部请求。仓库已有「外部语言引擎随单 JAR 提供」的成熟先例——`engine-server/python/`（模块资产、src 外、Gradle 编排入 jar、子进程 + 通道抽象 + Failover）。本变更把 Go 按同一模式引入：动机与场景勘测见 `proposal.md`，行为契约见 `specs/go-toolbox-engine/spec.md`。

关键现状锚点：

- 通道智慧蓝本：`infrastructure/python/StdioChannel.java`（JSON 行协议、常驻读线程、毒丸、串行 call、stdout 专线）与 `PythonProcessLauncher.java`（classpath 解压 + 拉起）
- S1 计算点：`infrastructure/search/InMemoryVectorIndex.java:111-145`（单线程余弦扫 + 全排序，空间闸门语义在此）
- S2 缺口：全库无 `MessageDigest` 调用——上传无内容寻址
- 构建蓝本：`engine-server/build.gradle:78-90`（packagePython）与 `:96-173`（前端任务链）；`Jenkinsfile` 五阶段

## Goals / Non-Goals

**Goals:**

- Go 引擎以「方法注册表 + JSON 行协议」通用框架承接工具方法——新增方法零协议改动（通用性）
- 业务代码只依赖 domain 端口，`engine.go.enabled` 装配期二选一，Java 现役实现即兜底（灵活性）
- 两个场景真实落地：S2 文件哈希（高 IO）、S1 并行向量扫描（高计算）
- 教学质量：Go 代码满【教学注释】，每个文件头带「Go vs Java 对照」块

**Non-Goals:**

- 不做 Go 承接外部请求/网关（方向反转的结论，见 D1；旧 Go 网关已退役）
- **文件哈希与上传内容寻址去重（原 S2 蓝图）延后为独立变更**——合并手写代码时确认场景集合为 text/vector/hashing 降级三组，S2 不在本变更
- 不做运行时热切换/FailoverChannel 式双通道（YAGNI，v2 再议）
- 不做 ANN/量化/ mmap 共享内存（先正确、再 measurable——vector.* 只做「并行暴力扫」，索引副本走 stdio 复制）
- 不迁移 PDF/DOCX 解析与 Lucene 全文（生态在 Java，迁移是倒退）

## Decisions

### D1 · 方向反转：Java 门面 + Go 引擎（否决另两案）

探索阶段三案对照结论：A「Go 子进程侧车反代」双进程全套复活且一跳仍在；B「Go c-shared + JNI 进程内」线程模型冲突、双 GC 同进程、桥接代码量 ∝ API 面，换来的只是消除已不存在的跳。**C（本设计）**：Java 继续当门面（入口零变动、契约零风险），Go 作为嵌入引擎做计算——与 python/ 先例同构。

### D2 · 通道：stdio JSON 行（否决 UDS/命名管道/Py4J 式 socket）

继承 `StdioChannel` 全部智慧：零 socket（Windows 开发机/Linux Docker 行为一致）、常驻读线程 + `BlockingQueue` 交接、进程退出毒丸 `__PROCESS_EXITED__`、`synchronized call()` 串行化（吞吐由批量接口消化）、stdout 是协议专线日志走 stderr。Go 引擎启动是毫秒级（无模型加载，对比 Python 的 10-30s），lazy 拉起成本可忽略。

### D3 · 协议帧与方法注册表

帧格式与 `server_stdio.py` 同构：请求 `{"id":n,"method":"...","params":{...}}` → 响应 `{"id":n,"result":{...}}` 或 `{"id":n,"error":{"code":...,"message":...}}`。Go 侧核心是一张 `map[string]Handler` 注册表（新增工具 = 新增一个包 + 一行注册）。方法命名空间以手写代码为准：`text.chunk` / `text.tokenize` / `text.keywords` / `vector.search` / `vector.insert` / `vector.delete` / `vector.similarity` / `hashing.generate` / `sys.ping` / `sys.shutdown` / `sys.stats`。Java 侧通道是类型化薄接口：`send(method, Map) → GoProtocol.Response`（DTO 解析集中在 `GoProtocol`，各业务门面自行 `extractResult`）。**大负载编码**：向量数据用 base64 编码的 float32 little-endian 字节（JSON 数组序列化 512 维 float 慢且体积 4-5 倍）。

### D4 · S1 索引副本同步（本设计含金量最高的决策）

**问题**：打分向量在 JVM 堆里，Go 扫描需要数据。**否决**「每查询传全矩阵」（万级×512维 = 每查询 MB 级，荒谬）与「mmap 共享内存」（跨平台 + 双运行时内存语义复杂，v2 再议）。**采纳副本同步**：

```
JVM InMemoryVectorIndex                Go vscan（副本）
  replace(docId, entries) ──vscan.index.replace──▶ 追加/替换进连续矩阵
  remove(docId)          ──vscan.index.remove ──▶ 逻辑删除（generation 标记）
  启动                    ──vscan.index.replace×N─▶ 全量灌入
  查询                    ──vscan.query(向量,topK,过滤)──▶ 并行扫 → topK
```

- Go 侧存储：`[]float32` 一维连续大数组 + 侧表（entryId→行号、docId→行集合、空间标签）——cache 友好，本身是「Go slice 内存布局 vs Java 对象数组」的教学点
- **一致性边界（写进 spec）**：复制命令先于摄取事务返回发出；查询串行于 call() 互斥锁之后，天然线性一致
- 逻辑删除 + 压缩：remove 标记后由后续 replace/定期压缩回收行——v1 先「标记跳过」，压缩留给 v2（YAGNI）

### D5 · S1 并行扫描与结果一致性

- 分片：按行数把矩阵切成 ~GOMAXPROCS 片，每 goroutine 扫一片产出局部 top-K（小顶堆或部分排序），主 goroutine 归并局部 top-K → 全局 top-K（`sync.WaitGroup` 汇聚）
- **与 Java 结果一致性**：点积求和顺序会导致浮点尾差——归并排序以 `(score 容差 1e-6, entryId 字典序)` 作最终定序，保证与 Java 实现的命中集合与顺序可逐条对拍
- 空间闸门：同 (sourceType, modelKey) + 维度匹配才打分；「同模态全不匹配 → 400 + 修复指引」的判定在 Java 适配层完成（错误文案与 `InMemoryVectorIndex.spaceGateError` 逐字对齐）

### D6 · 进程生命周期

- 拉起时机：`engine.go.enabled=true` 时应用启动即拉起（eager）——Go 启动毫秒级，换取「健康立即可见」；首帧 `sys.ping` 握手确认就绪
- 关闭：`@PreDestroy` 发 `sys.shutdown` 帧并等待进程退出（超时强杀）；stdin EOF 同效
- 崩溃：读线程收到 EOF → 投毒丸 → 在途 call 立即失败；后续 call 报「引擎不可用」；`goToolbox` 健康 DOWN；重启应用即恢复（v1 不自动重启子进程）

### D7 · 装配与端口（两级开关）

- **第一级 `engine.go.enabled`（默认 false）**：`@ConditionalOnProperty` 控制 Go 通道三件套（Launcher/Channel）是否装配——false 时零 Bean 零进程零解压，spec「默认零变化」由此保证（手写初版的无条件 `@Component` 是启动炸弹，合并时已修）
- **第二级 `go-toolbox` Profile**：`GoToolboxProvider`（`@Profile` + enabled 双条件）接管 TEXT 模态 EmbeddingProvider——`EmbeddingProviderRegistry` 对同模态重复注册启动即炸，故降级模式须以 Profile 让 python provider（failover/py4j/stdio）让位，两级缺一不可
- domain 不新增「Go」概念：`GoToolboxProvider` 实现既有 `EmbeddingProvider` 端口（降级哈希向量），文本/向量工具经门面方法直接暴露给需要的应用服务
- InMemoryVectorIndex 保持现役：Go 开时它仍维护 JVM 侧索引（副本同步的源头 + 对拍基准 + 关闭态兜底），不做删除

### D8 · 构建编排（双轨二进制定位）

- `engine-server/golang/`：`go.mod`（module engine/gotoolbox，Go 1.21+，零三方依赖）+ `cmd/toolbox/main.go` + `internal/`（router、protocol、hashing、vscan、teaching 头注释）
- Gradle：`buildGoToolbox`（Exec 两次：`GOOS=windows/amd64`、`GOOS=linux/amd64`；`onlyIf` 源码指纹变化才重编——镜像前端 fingerprint 模式；本地无 Go 工具链且开关关闭时允许跳过）→ `packageGolang`（Copy → `build/golang-pack/golang/<platform>/`）→ `sourceSets` 注册 → 随 bootJar 入 `classpath:/golang/`
- 运行时：`GoProcessLauncher` 按 `os.name/os.arch` 选 `<platform>/toolbox(.exe)` 解压到 `./go-runtime/` 执行（镜像 `PythonProcessLauncher`）
- Jenkinsfile：节点要求注释 +Go；`GOPROXY=https://goproxy.cn` 环境变量（与 NPM_REGISTRY 同一国内加速策略；零依赖下仅 `go` 工具链自身需要网络）
- Docker/entrypoint 零改动；jar 体积 +约 15-20MB（两个平台静态二进制）

### D9 · 教学注释规约

Go 文件头统一结构：包职责一句话 →【教学注释】Go 概念块（每个新 Go 概念锚定 Java 对应物：goroutine↔Thread、channel↔BlockingQueue、interface 隐式实现↔implements、error 值↔异常、defer↔try-finally、包模型↔类层次）。Java 侧新类沿用仓库既有【教学注释】惯例。/docs 两篇新文档含「Go 速成路线图」章节（按本变更代码走读顺序编排）。

### D10 · 与并行手写代码的合并决策（正典 = `infrastructure/go/`）

实施期间维护者并行手写了 `infrastructure/go/`（GoChannel/GoStdioChannel/GoProcessLauncher/GoProtocol/GoToolboxProvider）与任务清单落地的 `infrastructure/golang/` 形成撞车。合并裁决：**手写包为正典**（类型化协议 + 业务门面更完整、更贴维护者意图），任务产物并入——通道硬化（`poll(timeout)` 免忙等、`EngineException` 纪律、迟到帧丢弃语义、writer 判空）、classpath 生产轨定位链、装配门控默认关、全部单测随迁。`infrastructure/golang/` 删除。Go 侧 `internal/text`（分块/分词/关键词）、`internal/hashing`（哈希向量）为手写成果直接采用；其中 `appendChunk` 的 byte 硬切存在中文 UTF-8 截断风险（代码注释自认"近似"），合并时修正为 rune 感知硬切——spec 的中文正确性硬约束优先于性能近似。

## Risks / Trade-offs

- [CI 重新需要 Go 工具链（single-jar 刚删掉的成本回归）] → 明知故犯：为学习目标付出的代价，proposal 已显式承认；零三方依赖把网络面降到最小
- [副本同步让 S1 写路径多一次 stdio 复制，摄取吞吐略降] → 复制是增量（一个文档一批块）、毫秒级；学习规模无感；这是「跨进程计算引擎」的固有税，设计上用 D4 的一致性边界换正确性
- [浮点不一致导致对拍失败] → D5 的定序规则 + 1e-6 容差；自测含双实现对拍用例
- [Go 学习曲线导致代码质量波动] → stdlib-only + 教学注释 + `-race` 常开；场景少而精（两个），不铺开
- [jar 体积 +15-20MB] → 接受；镜像本地轨影响一次性
- [Go 子进程在 Windows 开发机的防病毒/路径问题] → 解压目录 `./go-runtime/` 加入 .gitignore；文档记录白名单排查步骤

## Migration Plan

1. 合入即默认 `engine.go.enabled=false`——部署零感知，回滚 = 保持关闭
2. 验证期在测试环境开 true 跑对拍自测（双实现一致性 + 上传秒传 + 健康段）
3. 无数据迁移：哈希列新建（首次摄取时回填），索引副本每次启动重建

## Open Questions

两项均已随实施落定（8.4 回写）：

- ~~`goToolbox` 健康段除 `status` 外的具体指标字段~~ → **已落定：`version` / `vectorCount` / `tools`（DOWN 态另带 `lastError`）**——全部来自 `sys.stats` 单次真实往返（微秒级纯内存读，无新增 Go 侧状态），兑现"按易得性取用"；uptime/请求计数未取（须引擎新增计数器，违背易得性原则）。值对象 `domain/model/engine/GoToolboxStatus.java`，探活实现 `GoStdioChannel.status()`（6.1）
- ~~Go 版本下限是否钉 1.21 还是放宽 1.18~~ → **已落定：钉 1.21**（`golang/go.mod` 的 `go 1.21` 指令）；节点实际 go1.27.1（1.1 实测）满足。放宽 1.18 否决——`slices` 等标准库增强 1.21 起齐备，教学价值成立且下限不构成部署负担（本机/CI 均高于下限）
