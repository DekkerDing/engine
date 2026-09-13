# 架构总览（architecture）

> 本文回答四个问题：**请求怎么流动**（调用链全景）、**代码怎么组织**（DDD 分层与依赖方向）、**模块边界在哪**（各模块/语言资产归属）、**跨模态图片检索怎么工作**（第 6 节）。
> 阅读顺序建议：第 1 节建立全局图景 → 第 3 节理解 server 内部 → 第 6 节理解跨模态与扩展约束。

---

## 1. 系统调用链全景

> 单 JAR 合体（single-jar-consolidation 变更后）：一个 fat JAR、一个 Java 进程、一个 8090 端口
> 承载全部入口能力。原 Go 网关职能（静态资源、SPA 回退、缓存头、健康聚合、/api 前缀）
> 已内化为 engine-server 的 `interfaces.web` 三件套与控制器前缀映射。

### 1.1 检索请求（读路径）

```
浏览器（React SPA）
  │  POST /api/search {"query": "红塔"}
  ▼
engine-server (:8090，唯一进程、唯一端口)  ──────────────────────────
  │  interfaces: SearchController        参数校验（@Valid，@RequestMapping("/api/search")）
  │  application: SearchApplicationService
  │      ├─ Caffeine 缓存查询（命中直接返回）
  │      ├─ EmbeddingProvider.embedTexts(query)      ──┐ 查询向量化
  │      ├─ InMemoryVectorIndex.topK(...)              │ 语义路（余弦）
  │      ├─ LuceneFullTextIndex.search(...)            │ 全文路（SmartCN）
  │      └─ RRF 融合 + 高亮偏移 + 去重 + 来源标注      ┘
  ▼
Python 引擎（server 的子进程，环回 25335 / stdio）
  │  bge-small-zh-v1.5 批量编码（512 维）
  ▼
响应沿原路返回：{hits:[{text, score, source, highlights}], took, degraded}
```

### 1.2 摄取请求（写路径）

```
浏览器 ──POST /api/documents (multipart)──▶ server interfaces: DocumentController
  │                                        格式/大小校验（50MB 闸门在应用入口）→ 存文件
  │                                        → 建 Document(UPLOADED) → 立即返回 ID
  ▼ (固定线程池异步)
application: DocumentApplicationService   状态机推进（UPLOADED→PARSED→…→INDEXED / FAILED）
  ├─ CompositeDocumentParser  txt/docx/pdf 解析
  ├─ TextChunker              ~400 字/块、句子级重叠
  ├─ EmbeddingProvider        批 16 向量化（degraded 标记透传）
  ├─ SqliteVectorStore        向量 BLOB 落库（float32 小端）
  ├─ InMemoryVectorIndex      增量更新内存索引
  └─ LuceneFullTextIndex      全文块写入
```

### 1.3 健康链路

```
浏览器 ◀── GET /api/system/health ── SystemController（进程内直调 SystemQueryService.aggregateHealth()，
                                        实时计算无探测缓存；组装合体信封：
                                        gateway 段恒 UP（段名保留兼容前端三卡片）、server 段同进程自证恒 UP、
                                        engine 段语义化、documents 统计透传；整体判定坍缩为 engine 单维度）
```

---

## 2. 模块与进程拓扑

```
engine/（单 Gradle 工程，一棵 IDEA 树看全所有源码）
├── engine-server/                    # Spring Boot :8090 —— 单 JAR 合体应用
│   ├── src/main/java/...             #   DDD 四层 + interfaces.web 静态三件套（纯 JVM）
│   ├── frontend/                     #   React 前端源码（server 的资产，src 之外）
│   │   └── src/{api,layouts,pages,components,hooks}
│   │       （构建产物经 build/frontend-static 进 jar 的 BOOT-INF/classes/static/）
│   └── python/                       #   Python 引擎源码（server 的资产，src 之外）
│       ├── server_py4j.py            #     Py4J 通道入口（主）
│       ├── server_stdio.py           #     stdio 通道入口（备）
│       └── core/{embeddings,registry,tokenizer}.py
├── docker/                           # 部署资产（Dockerfile×2、entrypoint、模型预下载）
├── Jenkinsfile                       # CI 流水线（Frontend → Test → Package → Image → Publish）
├── docs/                             # 工程文档（本文所在）
└── openspec/                         # 规格与变更管理
```

**多语言源码归属（决策：模块内、src 外）**：Python 与前端是所属模块的资产——
`src/main` 保持纯 JVM 语义不被 node_modules 污染；构建产物照旧嵌入 jar
（python 脚本与前端 dist 均进 `engine-server.jar` 的 `BOOT-INF/classes/`）。
三个嵌套服务（python/frontend/Java 源码）归并为同一种归属模式，仓库根只此一个工程。

**进程树**（部署形态）：

```
entrypoint.sh (exec 让渡 PID 1)
└── java engine-server.jar (:8090)
    └── python server_py4j.py / server_stdio.py   # 随 Java 启停（shutdown hook 优雅终止）
```

---

## 3. engine-server 的 DDD 分层

### 3.1 依赖方向（只能向下，禁止反向）

```
interfaces（REST 适配器 + Web 静态三件套）  ← 外部世界进来的第一站
    │  调用
    ▼
application（用例编排）         ← "上传文档到可检索"这类业务流程
    │  调用
    ▼
domain（领域核心）              ← 纯业务规则，不依赖任何框架
    ▲  实现（实现依赖倒置：domain 定义端口，infrastructure 提供实现）
    │
infrastructure（技术细节）      ← SQLite/Lucene/Python 进程/文件解析
```

**关键规则**：
- domain **不 import** 任何 Spring/SQLite/Lucene/py4j 类型——换掉任一技术设施，domain 一行不改
- 依赖倒置的落点：domain 的 `repository` 包定义端口接口（`EmbeddingProvider`、`VectorStore`、`FullTextIndex`、`DocumentRepository`），infrastructure 提供实现（`ChannelEmbeddingProvider`、`SqliteVectorStore`、`LuceneFullTextIndex`、`SqliteDocumentRepository`）
- interfaces 只做协议转换（HTTP ↔ 用例），不含业务判断

### 3.2 各层职责与代表类

| 层 | 包 | 职责 | 代表类 |
|----|-----|------|--------|
| interfaces | `interfaces.rest` / `interfaces.web` | REST 端点（`/api` 前缀映射）、@Valid 校验、ApiResponse 信封、全局异常 → 错误码；静态资源三段 handler、SPA 回退、缓存头 | `DocumentController` `SearchController` `SystemController` `GlobalExceptionHandler` `WebStaticConfig` `SpaFallbackResolver` `AssetCacheFilter` |
| application | `application` | 用例编排、事务边界、异步线程池、状态机推进、缓存 | `DocumentApplicationService` `SearchApplicationService` `SystemQueryService` |
| domain | `domain.model` / `domain.service` / `domain.repository` | 实体与值对象、分块/向量化规则、端口接口、领域异常 | `Document` `TextChunk` `TextChunker` `EmbeddingProvider`（端口）`EngineException` |
| infrastructure | `infrastructure.*` | SQLite 读写与迁移、内存向量索引、Lucene 全文、Python 双通道、文档解析 | `SqliteVectorStore` `InMemoryVectorIndex` `LuceneFullTextIndex` `FailoverChannel` `CompositeDocumentParser` `PythonProcessLauncher` |

### 3.3 Python 引擎的双通道结构

```
ChannelEmbeddingProvider (infrastructure, EmbeddingProvider 端口的实现)
        │
        ▼
FailoverChannel (@Profile("failover")，通道选择器：组合，非继承)
   ├── primary: Py4jChannel   ← 环回 TCP 25335（主通道）
   └── backup : StdioChannel  ← JSON 行协议 stdio（惰性拉起，故障才启）

状态机：py4j 失败 → 惰性拉起 stdio → 周期探测 py4j 恢复 → 切回并销毁 stdio
       （互斥锁串行化切换；任何时刻只有一个 Python 子进程）
```

- 对上层只暴露一个 `PythonChannel` Bean，通道切换零感知
- 边界协议纪律：跨语言只传 JSON 字符串（两端各自反序列化强类型）
- 降级：真实模型不可用 → 确定性哈希向量 + `degraded=true` 显式标志（禁止无标志假向量）

---

## 4. 原网关职能的内化（interfaces.web 静态三件套）

Go 网关退役后，其四项职能由 engine-server 原生承担（对外契约逐字节不变）：

| 原网关组件 | 内化后的 Java 组件 | 契约要点 |
|-----------|-------------------|----------|
| `handler/proxy.go`（/api 剥前缀反代） | 控制器类级 `@RequestMapping("/api/...")` | API 全在 `/api/**` 命名空间，前端路由物理隔离（D1 终案——Filter 剥前缀会与同名前端路由冲突，实测推翻） |
| `handler/static.go`（静态 + SPA 回退） | `WebStaticConfig` + `SpaFallbackResolver` | `/assets/**`、`/index.html`+`/favicon.ico`（noCache）、`/**` 兜底；`api/`、`actuator/` 前缀防御分支拒绝（404 不吞 HTML） |
| `middleware/cache.go`（缓存头） | `AssetCacheFilter` | `/assets/*` → `public, max-age=31536000, immutable`；入口页 → `no-cache`；`/index.html` 200 直返无 301 |
| `handler/health.go`（周期探测聚合） | `SystemController` 直调 `SystemQueryService` | 合体信封五段（gateway 段名保留兼容前端）；实时计算替代 10s 探测缓存 |

路由命名空间全景与部署验收清单见 [reqforge-single-jar-spec.md](file:///F:/workspace/engine/docs/reqforge-single-jar-spec.md)；
决策过程与被否决方案见 [reqforge-single-jar-design.md](file:///F:/workspace/engine/docs/reqforge-single-jar-design.md)。

---

## 5. 数据流与存储布局

```
data/（可挂卷持久化）
├── engine.db          # SQLite：document 表 + vector_entry 表（vector 存 float32 小端 BLOB）
└── documents/         # 上传原件（删除文档时联动清理）
lucene-index/          # 全文索引目录（SmartCN 分词）

启动时：vector_entry 全量加载进内存 float[][]（L2 已归一化，点积=余弦）
检索时：内存暴力扫 top-k（万级块 <100ms，更大规模换 FAISS——端口已隔离）
```

---

## 6. 跨模态图片检索（已实现：add-image-crossmodal-search 变更）

"上传红塔与小花照片 → 用文字搜到它"已落地。当初为图片预留的接缝全部兑现，没有一处需要回头改表结构：

| 接缝 | 位置 | 兑现情况 |
|------|------|----------|
| 模态枚举 | `domain/model/vector/EmbeddingModality` | `CROSS` 已启用（CLIP 双塔共用空间） |
| 资源抽象 | `domain/model/resource/VectorableResource` | `ImageAssetResource` 实现（sourceType="image"，asText=存储路径） |
| 向量条目 | `VectorEntry` / `vector_entries` 表 | 图片向量直接入库（source_type='image'），表结构未动 |
| 模型注册表 | `python/core/registry.py` | `clip` 槽位填入 `OFA-Sys/chinese-clip-vit-base-patch16`（512 维） |
| 检索端口 | `VectorStore` / `InMemoryVectorIndex` | 闸门升级为 `(sourceType, modelKey)` 空间匹配——双向不越界 |

**跨模态链路**（与文本链路共用哪些、新开哪些）：

```
上传：POST /api/images → ImageFormatGuard（白名单+魔数+20MB）→ 落盘 data/images/
      → image_asset 建档（PENDING）→ 单线程池异步 vectorize
      → ClipEmbeddingProvider（共用 PythonChannel：Py4J 主/stdio 备）
      → 一图一向量入库（VECTORIZING→COMPLETED；degraded 透传）

检索：POST /api/search {modality:"image"} → 查询文本经 CLIP 文本塔编码
      → 仅对 image 空间算余弦（单路直通，不碰全文索引）
      → 命中即缩略图卡片（score=余弦；阈值 0.25 独立于文本的 0.40）
```

**空间键纪律**（本变更最重要的架构不变量）：`VectorEntry.modelKey` 必须等于
provider 的配置键（`text-embedding-zh` / `clip`），而不是 Python 返回的模型名
（`BAAI/bge-small-zh-v1.5`）——检索侧按配置键过滤空间，错键 = 空间闸门拦截。
历史存量错键由 V6 迁移纠错（`DatabaseMigrator.fixVectorSpaceKeys`）。

**扩展纪律**（对后续所有变更生效）：
1. 新表/新接口必须携带 `modality` 字段或参数
2. `VectorStore` 端口签名不得耦合文本语义
3. 前端页面区块按"模态无关的资源"抽象命名（文档/照片同构）
4. 新模态以**新增 EmbeddingProvider 实现**方式进入，不改现有链路
   （CLIP 即范例：`ClipEmbeddingProvider` + `EmbeddingProviderRegistry` 按模态路由）

---

## 7. 关键设计决策速查

| 决策 | 结论 | 详见 |
|------|------|------|
| D1 多语言源码归属 | 模块内、src 外 | 第 2 节 |
| D2 向量检索 | SQLite BLOB + 内存余弦 + 维度闸门 | 第 5 节 |
| D3 混合融合 | RRF（k=60），零调参 | `SearchApplicationService` |
| D4 摄取异步 | 固定 2 线程池 + 状态机轮询 | 第 1.2 节 |
| D5 通道回退 | Py4J 主 → stdio 惰性备 → 自动切回 | 第 3.3 节 |
| D6 入口形态 | 单 JAR 单进程 :8090（网关退役，职能内化） | 第 4 节 + [合体设计](file:///F:/workspace/engine/docs/reqforge-single-jar-design.md) |
| D8 容器 | 单容器单进程、双构建轨、exec PID 1 | `docs/deployment.md` |
