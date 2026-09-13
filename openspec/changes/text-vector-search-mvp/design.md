# design.md — 文本向量化检索 MVP 技术设计

## Context

仓库现状（见 proposal.md - Why）：engine-server 的 DDD 领域层与 Python 双通道基础设施已就绪、gateway 仅有构建脚本与配置、前端与文档为零。本设计覆盖四块补齐方案：摄取/检索主链路、网关实现、React 前端、Docker 部署。硬约束：JDK 1.8（无虚拟线程、Lucene≤8.x、Caffeine≤2.x）、Spring Boot 2.6.14、Python 3.13（torch 2.13 cpu）、Node 22、Windows 开发机（GBK 默认编码）、部署目标机无 Docker（暂）。

## Goals / Non-Goals

**Goals:**
- 六个 capability 的行为规格全部可落地、可验收（对应 specs/）
- 模块边界清晰：每个 capability 可独立演进（图片扩展零破坏）
- 全链路教学可读：代码注释 + docs 讲清"为什么这么选"

**Non-Goals:**
- 不引入消息队列/分布式：摄取异步用进程内线程池，规模到万级块前够用
- 不做向量索引库（FAISS/Milvus）：MVP 用 SQLite BLOB + 内存余弦暴力扫，端口设计保证后期可换
- 不做鉴权/多租户；不做 SSE 进度推送（本期轮询，网关透传能力已预留）
- 不做模型微调/重排（rerank）：检索质量优先靠 bge 模型与 RRF 融合

## Decisions

### D1. 目录归属（方案二）与迁移连锁
`python/` → `engine-server/python/`，前端工程建于 `engine-gateway/frontend/`。**连锁改动点（任务清单单列防漏）**：
- `packagePython` 的 `pythonSrcDir`：`rootProject.projectDir/python` → `projectDir/python`
- `PythonProcessLauncher` 开发态探测路径：`../python` → `../src/../python` 修正为 `engine-server/python`（bootRun 工作目录在 engine-server/ 下时为 `./python`，与 jar 生产态 `./python-runtime` 并列探测）
- gateway `build.gradle` 的 `ext.frontendDir` 同理改 `projectDir/frontend`
- `.gitignore` 增加 `node_modules/`、`frontend/dist/`；Gradle 文件扫描一律 exclude（指纹函数已排除，pack 类任务照做）

### D2. 向量检索：SQLite BLOB + 内存余弦 + 维度闸门
- 存储沿用 SQLite（`vector_entry` 表：document_id、chunk_seq、text、vector BLOB(float32 小端)、dim、model、modality、degraded）。BLOB 一次读入内存，启动时预加载进 `float[][]`，查询走归一化点积（向量入库前已 L2 归一化，点积即余弦）
- **维度闸门**：检索入口校验查询向量 dim 与库内 dim 一致，不一致返回明确错误（spec 已定）；为后期"多模型共存"留演进：按 model 分组加载
- 备选否决：FAISS（Python 侧另起索引与"Java 主导检索"的架构冲突、且万级以下无收益）；Lucene KNN（8.x 的 8 维上限不够用）

### D3. 混合融合：RRF（Reciprocal Rank Fusion）
`score(d) = Σ 1/(k + rank_i(d))`，k=60。选 RRF 而非分数加权归一的原因：语义分数（余弦 0~1）与 BM25 分数（无界）分布不可比，加权法需调参且不稳；RRF 只用排名，零调参、对分布不敏感，是业界混合检索默认起点。两路各取 top-50 进融合，输出去重后按来源标注。

### D4. 摄取异步与状态机
- 上传接口：校验 → 存文件 → 建 Document(UPLOADED) 记录 → 提交线程池（固定 2 线程，JDK8 下够用且省内存）→ 立即返回 ID
- 工作线程：解析 → 分块（TextChunker 已有）→ 分批 embed（批 16）→ 批量入库 → 更新 Lucene → 状态推进；任一步异常 → FAILED + 原因落库
- 进度查询：轮询 `GET /documents/{id}`（含 status、chunkCount、vectorizedCount）；SSE 推迟（网关流式透传已按 spec 实现，后续加推送零架构改动）

### D5. 双通道回退状态机（主 Py4J → 备 stdio，惰性拉起）
```
调用 embed → try 主通道(健康? 直接用 : 快速探测)
              └─ 失败(超时/进程死) → 标记主通道降级 → 惰性拉起 stdio 子进程 → 重试一次
                                     └─ 成功 → 继续服务，健康页显示"当前: stdio(回退)"
主通道周期探测恢复 → 切回（备进程销毁，不双开）
```
实现落点：`infrastructure/python` 内新增通道选择器（组合两个 PythonChannel，非继承）；互斥锁串行化切换（JDK8 无虚拟线程， embed 本就批量串行）。否决"常驻双通道热备"：torch 模型双份内存（约 +1.5GB）不划算。

### D6. 网关实现（OkHttp 单客户端 + 三组件）
- 反代：`ProxyController` 通配 `/api/**`，剥前缀转发；响应用 `InputStream` 流式写出（天然支持 SSE/chunked 透传）；连接 2s/读 60s（配置已就位）
- SPA 回退：静态资源 handler 之后的 fallback controller 返回 `classpath:/static/index.html`；带 hash 的 `/assets/**` 设长缓存头，index.html 设 no-cache
- 健康聚合：`@Scheduled` 10s 探测 server `/actuator/health`（连续 3 次失败才 DOWN），透传其返回中的 Python 引擎段

### D7. 前端架构
- 栈：Vite 5 + React 18 + TypeScript + React Router 6 + Ant Design 5（babel 导入按需加载控制包体）+ axios
- 目录：`frontend/src/{api/（统一客户端+信封解析）, layouts/（手写 CSS Grid 骨架）, pages/{dashboard,documents,search}, components/（高亮片段/状态徽标等共享件）, hooks/（轮询）}`
- 布局规范（坐标定位）：CSS Grid `grid-template: "top top" 56px "side main" 1fr / 208px 1fr`；间距体系 4/8/16/24；AntD token 统一主色——规范细则落 docs/frontend-standards.md
- 开发态：vite devServer proxy `/api` → `:8090`；生产态构建产物全相对路径（Vite 默认 `base: './'` 改为 `/`，由网关同源承载）

### D8. Docker 双轨与 entrypoint
- `Dockerfile`（本地轨）：`eclipse-temurin:8-jre` + apt 装 python3/pip → COPY 两 jar + entrypoint.sh → pip 装依赖 + 模型预下载脚本（HF_ENDPOINT=hf-mirror，产物落 `/app/models`，运行时 `SENTENCE_TRANSFORMERS_HOME` 指向它）
- `Dockerfile.full`（全构建轨）：stage1 `gradle:8-jdk8` + Node22 构建两 jar；stage2 同上运行时层
- `entrypoint.sh`：后台起 server → 轮询其 `/actuator/health` 就绪 → 起 gateway（前台托管）；`trap` SIGTERM 转发两 Java 进程；任一退出 → `wait -n` 触发全组退出（spec：不静默带病运行）
- 数据卷：`/app/data`（SQLite、上传文档、Lucene 索引）；`/app/models` 建议挂卷以便权重复用
- 基础镜像选 `eclipse-temurin:8-jre-jammy`：仍维护、含 glibc（torch cpu 轮子兼容；alpine 的 musl 装不了 torch）

### D9. API 信封与错误码
`ApiResponse{code, message, data}`：code=0 成功；错误码分段（1xxx 参数、2xxx 领域、3xxx Python 下游、5xxx 系统），`GlobalExceptionHandler` 已有骨架按段扩展。前端客户端按 code 统一 toast。

### D10. 图片扩展守护（本期零实现，仅约束）
所有新表/新接口必须携带 `modality` 字段或参数；`VectorStore` 端口方法签名不得耦合 TEXT 语义；前端页面区块按"模态无关的文档/照片资源"抽象命名（docs 中记录），后期 CLIP change 以新增 provider + 新页面块方式进入。

## Risks / Trade-offs

- [内存暴力扫在 10万+ 块时延迟劣化] → 端口已隔离（VectorStore），后期换 FAISS/分层索引不动应用层；MVP 规模（千~万块）实测预期 <100ms
- [bge 模型对短查询的语义漂移] → RRF 融合天然兜底字面命中；检索页展示来源标注便于人工判读
- [Py4J 环回端口 25335 被占] → 端口已可配置；被占时启动失败信息列出候选修复动作（现有失败信息规范）
- [双模型注册后用户切模型但忘重建库] → 维度闸门拦截 + 错误信息明确指引（spec 场景已定）
- [镜像 ~3GB，弱网拉取慢] → 依赖层与模型层分层缓存；docs 写国内 registry 加速建议
- [本机无 Docker，Dockerfile 无法实机验收] → 本期以语法评审 + 手册化交付；实机验收列 Open Questions 待环境就绪
- [Windows 开发机 GBK 陷阱反复出现] → 构建脚本已统一 UTF-8；docs 列"常见乱码排查表"；CI 型检查脚本可后补
- [AntD 5 与 React 18 严格模式的小冲突] → 教学项目先用非严格模式起步，docs 记录原因与后续升级路径

## Migration Plan

1. 纯增量上线：无存量数据迁移；SQLite schema 由 `DatabaseMigrator` 版本化（V2 新增 vector_entry 表等）
2. 回滚 = 回退 jar/镜像 tag；数据卷向后兼容（新增列可空）
3. 部署顺序：server 先起、gateway 后起（entrypoint 已编排）；裸机路径见 docs/deployment.md

## Open Questions

- Docker 实机验收环境何时就绪（本机装 Docker Desktop 或提供 Linux 主机）——不阻塞 Dockerfile 编写与手册
- 照片库规模（百/千/万级）——影响后期图片变更的向量检索选型，本期仅约束端口可演进
- docs 是否需要追加"逐步复刻手册"（step-by-step 从零重建）——实施末期按进度决定，不阻塞任务分解
