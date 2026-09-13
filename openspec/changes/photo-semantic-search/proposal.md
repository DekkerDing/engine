# 照片语义检索升级（拆分标注 + 双路召回 + 重排 + 批量导入）

## Why

跨模态搜图已交付（"红塔与小花"文本搜图端到端可用），但图片在系统里只有**像素向量**、没有**语义身份**：没有"主题:花 / 描述:红色的小花"这样的结构化文本，因此（a）检索结果无法向用户解释"为什么命中"，（b）cross-encoder 重排无文本可打分——重排与语义拆分存在硬依赖。同时用户本地有千到数千张待导入照片，现有单张上传 API 不堪此规模。本期把「上传→拆分→向量化→召回→重排」整条流程串通，识别环节以**确定性 mock 先行**，真实 VLM 后补。

## What Changes

- **图片语义拆分（mock 先行）**：上传/批量导入管线新增"标注"阶段，产出结构化 `{主题, 描述, 标签}`；本阶段实现为确定性 mock（可选 `caption` 入参直接采纳，否则从文件名派生），**必须显式携带 `mocked=true` 标志**全链路透传（对齐既有 degraded 纪律）；registry 预留 `vlm` 槽位与协议 op，真实 VLM 到位后只换实现不动协议
- **图片第二向量（描述文本路）**：拆分出的"主题+描述"经 bge 文本编码写入 `vector_entry`，形成 image 资源的**双向量**（CLIP 像素路 + 描述文本路）；复用现有 (sourceType, modelKey) 空间隔离闸门，新增 image-描述空间
- **检索新增重排阶段**：`/search` 在召回（双路 RRF 融合）之后增加 cross-encoder 重排——`bge-reranker-base` 对 `query × 图片描述文本` 打分取 topN；图片无描述文本（mock 前存量/拆分失败）时重排退化为直通并显式标注 `reranked=false`
- **批量导入**：新增批量上传端点（multipart 多文件，单请求上限）+ 异步导入任务（任务 id / 进度 / 成功失败明细），复用现有摄取状态机与异步线程池；Flutter 端与脚本可断点续传式分批调用
- **元数据持久化**：图片资产表扩展结构化标注字段（subject / description / tags / annotation_mocked / annotation_source），列表与详情 API 透出
- **React 前端最小同步**：图片列表/详情/命中卡片展示主题与描述、mocked 标志、重排标注（完整移动端体验归 Flutter 变更）

## Capabilities

### New Capabilities

- `image-annotation`: 图片语义拆分——输入图片（及可选 caption 覆盖），输出结构化 {主题, 描述, 标签}；mock 实现的确定性与显式 mocked 标志；真实 VLM 的替换接缝（registry 槽位 / 协议 op / Java 侧 Provider 路由）

### Modified Capabilities

- `image-ingestion`: 批量导入端点与异步导入任务（进度/明细/限速）；摄取管线增加标注阶段与元数据持久化；上传 API 增加可选 `caption` 参数
- `python-embedding-engine`: registry 新增 `vlm`（mock 填实）与 `reranker`（bge-reranker-base）槽位；双通道新增结构化标注 op 与重排 op；mock 标注的确定性纪律
- `vector-search`: 图片检索升级为双路召回（CLIP 像素路 + 描述文本路）RRF 融合，其后新增重排阶段；重排的触发条件、候选数上限、降级语义（无描述文本→直通）；响应携带重排分数与标注
- `frontend-app`: 图片管理页/检索页展示主题、描述、mocked 与 reranked 标注；批量导入的入口与进度反馈（最小实现）

## Impact

- **代码**：
  - `python/`：新增 `core/annotate.py`（mock 标注器）与 `core/rerank.py`（bge-reranker 封装，惰性加载）；`registry.py` 加 `vlm` / `reranker` 槽位；`server_py4j.py` / `server_stdio.py` 各加 2 个 op；`requirements.txt` 无新增第三方库（reranker 走 sentence-transformers 交叉编码器）
  - `engine-server` domain：图片资产模型加标注字段；标注器端口（`AnnotationProvider`）与重排器端口（`RerankProvider`）
  - `engine-server` application/infrastructure：摄取管线插标注阶段；检索服务加双路召回与重排分支；批量导入应用服务
  - `engine-server` interfaces：`ImageController` 加批量端点与导入任务查询端点，上传加 `caption` 参数；`SearchController` 响应扩展
  - `frontend/`：列表/详情/命中卡片字段展示 + 批量导入入口
- **API**：`POST /images` 增可选参数；新增 `POST /images/batch`、`GET /images/import-tasks/{taskId}`；`POST /search` 响应新增字段（向后兼容，旧字段不动）
- **依赖**：模型新增 `BAAI/bge-reranker-base`（约 1.1GB fp32 / CPU 推理）；`docker/download_models.py` 与部署手册同步
- **数据**：图片资产表加列（标注五字段）——SQLite `ALTER TABLE ADD COLUMN`，存量行标注字段为 NULL（视为"未拆分"，检索走单路）
- **性能**：重排为同步 CPU 推理，候选上限与延迟预算在 design 阶段标定（预期 topK≤20 时秒级内）
- **后续**：Flutter 移动端另立变更（`lifeCloud_app`），依赖本变更的 API

## Non-goals（明确不做）

- 真实 VLM 接入（GLM-4V / qwen-vl / 本地 VLM）——本期只做 mock 与接缝，真模型到位后另立变更换实现
- Flutter 端（另立变更 `flutter-photo-client`，工程 `F:\workspace\lifeCloud_app`）
- 以图搜图、OCR、EXIF 解析、照片去重、相册管理
- 多用户与鉴权、分布式部署（沿袭前两个变更的 Non-goals）
- 描述文本的人工编辑界面（API 字段本期只读透出，编辑能力后补）

## Assumptions

- 照片规模一千到数千张：内存向量索引仍够用（暴力扫毫秒级），瓶颈在导入吞吐与 Python 标注/编码串行度——批量导入按并发受控设计
- `bge-reranker-base` 在 CPU 上单对延迟约 50-100ms：重排仅作用于融合后 topK（≤20），总延迟预算 ≤2s；不满足则降候选数而非换模型（design 标定）
- mock 标注的演示路径依赖 `caption` 入参：无 caption 时从文件名派生的描述检索价值有限，属预期行为（真实识别接入后消除）
