# 文本向量化检索 MVP（全链路打通：Python 引擎 → Java DDD 服务 → 聚合网关 → React 前端）

## Why

用户现有一个 Java 向量处理程序，检索不准确、效率不高，且没有一个能"点开就看懂全链路"的可视化载体；同时用户是只会一点 Java 的新手，希望以本工程为载体系统掌握 Python 后端开发与 React 前端工程化。仓库中已存在此前搭建的骨架（Gradle 多模块、engine-server 的 DDD 四层、Python 双通道引擎、gateway 的构建脚本与配置），但**业务主链路（摄取→向量化→检索）、网关 Java 实现、React 前端、工程文档四块全部缺失**——本变更把这四块补齐，交付一个上传文档即可向量化检索、页面可直接点击体验全流程的 MVP，并为后期的图片识别 + 跨模态检索（"红塔与小花"照片模糊搜索）守护好模块化扩展点。

## What Changes

- **补全 engine-server 业务主链路**（application 用例编排 + interfaces REST API）：
  - 文档上传 → 解析（txt/pdf/docx，解析器已有）→ 分块（TextChunker 已有）→ Python 向量化（双通道已有，本期增加 Py4J→stdio 主备自动回退）→ SQLite 向量入库（缺 `SqliteVectorStore`）→ 状态机跟踪（UPLOADED→PARSED→VECTORIZED→INDEXED / FAILED）
  - 混合检索：向量语义检索（余弦）+ Lucene 全文检索（SmartCN 中文分词，索引实现缺失）融合排序，命中片段高亮
  - 新增 REST：文档上传/列表/详情/删除、检索、向量化进度查询（轮询，SSE 作为可选增强）
- **实现 engine-gateway Java 侧三件事**（配置和构建脚本已备好，代码为零）：
  - 服务 React 静态资源 + SPA 路由回退 index.html
  - `/api/**` 反向代理到 `127.0.0.1:8081`（剥前缀、透传 SSE 流式响应、透传错误状态码）
  - 健康联动：探测 server `/actuator/health` + Python 引擎状态，聚合为 `/api/system/health`
- **新建前端工程**（源码位于 `engine-gateway/frontend/`——维护在 Java 模块内，npm 构建产物经既有 Gradle 任务嵌入 gateway jar、与其 Java 代码同 jar 提供服务）：
  - Vite + React + TypeScript + React Router；标准化布局（顶栏 + 侧边导航 + 内容区栅格），统一页面风格
  - 页面：系统仪表盘（全链路健康/调用链示意）、文档管理（上传/列表/状态）、语义检索（查询/命中高亮/相似度展示）——让用户"点一页看懂整体调用流程"
- **产出 docs/ 工程文档**（教学定位，全中文）：架构总览与调用链、前后端代码规范（命名/分层/错误码/页面布局与样式约定）、部署手册（含 Python 环境前提）、分阶段学习路径
- **守护图片扩展点（本期只预留、不实现图片功能）**：`EmbeddingProvider` 按模态注册制、`EmbeddingModality.IMAGE/CROSS`、`registry.py` 的 `clip` 槽位、`VectorableResource` 抽象——本期任何实现不得破坏这些接缝；图片识别 + 跨模态检索作为独立后续变更
- **环境与启动**：补全一键启动/体检脚本（JDK8 + Node + Python 三件套检查，npm/gradle 编排启动）
- **Docker 单容器部署**：一个容器内含 JRE 8 + Python（含 pip 依赖与模型权重），entrypoint 编排双 Java 进程（gateway :8090 对外 + server :8081 容器内）与 Python 子进程；**运行时镜像不含 Node/npm**（前端产物已内嵌 gateway jar，页面跳转由编译后的 JS 在浏览器执行——构建期/运行期分离）；data 目录 volume 持久化。两个并立 Dockerfile：`Dockerfile`（本地 `gradlew build` 产物 → 运行时镜像，日常迭代）与 `Dockerfile.full`（多阶段从源码全构建，可复现发布）；模型权重（bge + MiniLM 约 600MB）构建期从 hf-mirror 预下载烘进镜像，部署离线可用

**Non-goals（明确不做）**：图片/CLIP 向量化、照片库管理（后续独立变更）；多用户/鉴权；分布式/多机部署；JDK 或 Spring Boot 升级（锁定 1.8 / 2.6.14）；更换通信架构为网络 RPC（保持进程内调用）。

## Capabilities

### New Capabilities

- `document-ingestion`: 文档摄取管线——上传校验、格式解析、分块、Python 向量化、SQLite 持久化、状态机与失败可追溯
- `vector-search`: 混合检索——查询向量化、语义余弦检索、Lucene 全文检索、融合排序、命中片段高亮与相似度返回
- `python-embedding-engine`: Python 向量化引擎规格化——真实模型加载/降级哈希的显式 `degraded` 标志、批量编码、双模型注册切换（bge-small-zh-v1.5 默认 / MiniLM 备选）、双通道（Py4J 主 / stdio 回退）协议、模型注册表（含图片槽位预留）
- `gateway-routing`: 聚合网关——静态资源与 SPA 回退、`/api/**` 反向代理（SSE 透传）、健康聚合、唯一流量入口
- `frontend-app`: React 前端应用——标准化布局与路由、文档管理页、语义检索页、系统仪表盘、统一 API 客户端与错误处理
- `container-deployment`: 容器化部署——单容器双 Java 进程 + Python 子进程编排、运行时镜像（无 Node）、双 Dockerfile 策略、模型权重内置、数据卷持久化

### Modified Capabilities

（无——`openspec/specs/` 当前为空，本变更为首个正式规格化的变更）

## Impact

- **代码**：
  - `engine-server`：新增 application 层用例服务、interfaces 层 Document/Search 控制器、infrastructure 层 `SqliteVectorStore` + Lucene 索引实现；domain 层原则上零改动（若 SQLite/Lucene 实现暴露端口缺口，仅允许新增端口方法）
  - `engine-gateway`：新增 ProxyController/静态资源与 SPA 回退/健康聚合的全部 Java 实现
  - `frontend` 工程：全新创建于 `engine-gateway/frontend/`（Vite + React + TS + Router + Ant Design 5 + API client + 构建配置；`node_modules`/`dist` 不进 git）
  - `python/`：目录整体迁移至 `engine-server/python/`，registry 双模型槽位填实（默认 bge-small-zh-v1.5）、通道入口微调；embeddings/协议主体不动；`packagePython`/`PythonProcessLauncher` 的路径探测同步调整
  - `docs/`：全新目录（架构/规范/部署/学习路径四份起步）
- **API**：新增约 8~10 个 REST 端点（`/documents`、`/search`、`/system/health` 等），统一 `ApiResponse` 信封与错误码
- **依赖**：Java 侧依赖已就位零新增；前端新增 npm 依赖树（React 18 / Vite 5 / TS / Router / Ant Design 5）
- **构建**：复用已写好的 gateway 前端构建任务链（npmInstall → buildFrontend → copyFrontendDist，含源码指纹跳过）与 server 的 packagePython 打包
- **部署前提**：Docker 主路径——目标机仅需 Docker，单容器含 JRE 8 + Python + 依赖 + 模型权重（镜像约 3GB），`docker run -p 8090:8090 -v ...:/app/data` 即完成部署；原生路径（预装 JDK 8 + Python）保留于部署手册。构建期机器需 JDK 8 + Gradle + Node 22 + Python 3.13（本地构建轨）

## Decisions（探索阶段已拍板）

1. **文本 embedding 模型**：双模型同时注册、配置切换。`BAAI/bge-small-zh-v1.5`（512 维）为**默认**（直接回应"不准确"的痛点），`paraphrase-multilingual-MiniLM-L12-v2`（384 维）保留为备选；registry 两个槽位都填实，Java 侧配置选默认键。
2. **Python 通道**：双通道融合——Py4J（本机环回 socket）为默认主通道，stdio（纯管道）为自动回退备通道；备通道**惰性拉起**（主通道故障时才启动，不常驻双 Python 进程，避免 torch 模型双份内存）；两条通道状态均在系统健康页可见。
3. **前端 UI**：Ant Design 5 + 布局骨架（顶栏/侧边栏/内容栅格）手写 CSS Grid——组件风格统一靠 AntD，布局定位原理靠手写讲透。
4. **"坐标定位"**：确认为"标准化页面布局的栅格/定位体系"（全站统一的栏宽、间距、定位规则），作为前端代码规范的一部分落进 docs。
5. **多语言源码归属（方案二：模块内、src 外）**：Python 与前端源码作为所属模块的资产维护在模块目录内、`src` 之外——`python/` 迁移至 `engine-server/python/`（Python 引擎是 server 的资产），前端工程位于 `engine-gateway/frontend/`（页面是 gateway 的资产）。一个 IDEA 工程一棵树看全所有源码；`src/main` 保持纯 JVM 源码语义不被 node_modules 污染；构建产物照旧嵌入各自 jar（前端 dist 与 gateway Java 代码同在 `engine-gateway.jar`）。配套防御措施（进 design）：`.gitignore` 覆盖 `node_modules`/`dist`、Gradle 文件扫描/指纹计算排除 `node_modules` 与 `dist`。
6. **部署产物形态**：两个 jar——`engine-server.jar`（内嵌 Python 脚本）+ `engine-gateway.jar`（内嵌前端 dist）。保持进程隔离、独立重启能力；不合并为单 jar。
7. **容器形态**：单 Docker 容器承载全部运行时（JRE 8 + Python + 双 jar + 模型）；运行时镜像**不含 Node/npm**——前端在构建期编译为静态 HTML/JS/CSS 内嵌 jar，页面跳转与接口调用逻辑由编译后 JS 在浏览器执行，SPA 回退由 gateway 承担；entrypoint 负责启停编排与健康等待。
8. **镜像构建双轨**：`Dockerfile`（本地构建 jar 后 COPY，快速迭代）与 `Dockerfile.full`（多阶段容器内全构建，可复现发布）并立为一等公民，分别验收。
9. **模型权重内置**：bge-small-zh-v1.5 + MiniLM 约 600MB 构建期从 hf-mirror 预下载烘进镜像——部署完全离线、首启即快；接受总镜像约 3GB 的体积。

## Open Questions

（以下尚未拍板，可在 design 阶段或实施中决策）

5. **部署目标机 Python 环境策略**：Docker 单容器（镜像自带 Python + 依赖 + 模型）已成为部署主路径；原生裸机部署（目标机预装 JDK 8 + Python + pip 依赖）保留为 docs/部署手册 中的备选路径。原"是否研究 Windows embeddable Python 随包分发"问题随之降级为非目标（Docker 已覆盖该诉求）。
10. **Docker 构建的验收环境**：本机当前未安装 Docker（`docker` 命令不存在）。`docker build`/`docker run` 的实际验收需要先安装 Docker Desktop（Windows 11 可用，需开启 WSL2），或推迟到有容器环境的部署目标机进行——本期至少保证 Dockerfile/entrypoint 通过语法评审与 docs 逐步手册化，实际构建验收方式待定。
6. **MVP 上传格式范围**：txt/docx/pdf 三格式解析器均已实现，是否全部开放？还是 MVP 先开放 txt/docx、pdf 观察后再开？
7. **向量化进度反馈方式**：轮询（简单可靠）先行，SSE 流式推送（依赖已引入）作为本变更内可选增强还是推迟到后续变更？
8. **照片库规模**（为后期图片变更预研，不阻塞本期）：照片量级是数百还是数万+？决定后期向量检索用内存暴力扫还是 FAISS。本变更的 `VectorStore` 端口设计需两者都可演进。
9. **学习文档的深度**：代码内教学注释（现状风格）+ docs/ 四份文档是否足够？是否还需要逐步操作手册（step-by-step 复刻指南）？
