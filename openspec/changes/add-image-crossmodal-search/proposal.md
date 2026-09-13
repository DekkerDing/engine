# 图片跨模态检索（"红塔与小花"文本搜图）

## Why

文本向量化检索 MVP 已全链路交付（34/34 任务完成），而项目的原始目标是"上传『红塔与小花』照片 → 用文字模糊搜索"——图片识别 + 跨模态检索在 MVP 中被明确列为 Non-goal 并守护了全部扩展接缝（`EmbeddingModality.IMAGE/CROSS`、`VectorableResource`、registry 的 clip 槽位、`vector_entry.modality` 列）。现在接缝已空守一个周期，是长出图片能力的时候：这也是本工程学习价值最高的一步（CLIP 多模态模型、图文共空间检索、前端多模态交互）。

## What Changes

- **Python 引擎补图片能力**（按 registry.py 注释预留的三步扩展路径）：
  - `registry.py` 的 `clip` 槽位填实：`OFA-Sys/chinese-clip-vit-base-patch16`（512 维，modality=cross，中文场景图文共空间）
  - 新增 `core/vision.py`：CLIP 双编码器——图片编码（整图 → 向量）与查询文本编码（文本 → 与图片同空间的向量）
  - Py4J / stdio 双通道入口各加对应 op（协议纪律不变：JSON 字符串进出）
  - 图片降级语义对齐文本路径：模型不可用 → 确定性哈希向量 + `degraded=true` 显式标志
- **engine-server 摄取管线扩展图片**：
  - 图片上传端点（jpg/png/webp/bmp/gif 格式白名单 + 大小上限 + 魔数校验，防假扩展名）
  - `ImageAssetResource` 实现 `VectorableResource`（domain 仅新增实现类，接口零改动）；图片不切块——一图一资源一向量
  - 复用现有摄取状态机与异步管线；`modality=IMAGE` 的向量写入 `vector_entry`（表结构不动，`modality` 列已在）
- **检索新增跨模态路径**：
  - 文本查询经 **CLIP 文本编码器**（而非 bge）编码 → 对 IMAGE 向量余弦 topK——这是跨模态的核心机制：编码器必须与目标向量同空间
  - 内存索引分组键从"模型维度"升级为 **(modality, model)**：bge 与 chinese-clip 同为 512 维但**不同空间**，纯维度闸门无法防止跨空间污染，此为必须修复的正确性缺口
  - 图片检索仅有语义路（Lucene 全文路对图片无意义）；检索响应携带模态与来源标注，前端按模态渲染不同卡片
- **前端新增两个页面**（与文档页同构，按"模态无关资源"抽象）：
  - 图片管理页：上传（拖拽/多选）、缩略图网格、删除
  - 图片检索页：文本查询 → 命中照片缩略图卡片（相似度分数 + 来源）
- **镜像与部署**：`docker/download_models.py` 增加 chinese-clip 权重预下载（约 +400MB，镜像总量约 3.4GB）；部署手册同步更新模型清单

## Capabilities

### New Capabilities

- `image-ingestion`: 图片摄取管线——上传校验（格式/大小/魔数）、CLIP 图片向量化、modality=IMAGE 持久化、状态机跟踪与缩略图来源可追溯

### Modified Capabilities

- `vector-search`: 新增跨模态检索要求——文本查询命中图片资源；索引与查询按 (modality, model) 分组隔离，禁止跨空间比较；图片检索路径的降级与错误语义
- `python-embedding-engine`: clip 槽位填实——CLIP 双编码器规格、图片/查询文本编码 op、图片降级哈希标志、模型注册表健康可见性
- `frontend-app`: 图片管理页与图片检索页——多模态资源展示、上传交互、命中卡片按模态差异化渲染
- `container-deployment`: 模型权重清单纳入 chinese-clip；镜像体积与下载源（hf-mirror）约束更新

## Impact

- **代码**：
  - `engine-server` domain：新增 `ImageAssetResource`（实现现有接口）；`VectorEntry`/索引侧补 (modality, model) 分组语义
  - `engine-server` infrastructure：图片解析器（校验+元信息）、CLIP 编码调用、检索服务跨模态分支
  - `python/`：`registry.py`、新增 `core/vision.py`、`server_py4j.py`/`server_stdio.py` 各加 op
  - `engine-gateway`：预期零改动（哑管道，multipart 透传已就绪）
  - `frontend/`：新增两页 + API client 扩展
- **API**：新增图片上传/列表/详情/删除端点；检索端点增加模态参数（默认 text 保持向后兼容）
- **依赖**：Python 侧新增 `pillow`；`transformers` 的 `ChineseCLIPModel`（torch 已在依赖树）
- **数据**：`vector_entry` 表零迁移（modality 列已有）；`data/images/` 新目录存图片原件（与 documents/ 同构）
- **部署**：镜像 +400MB；构建期需联网下载 chinese-clip（hf-mirror）

## Non-goals（明确不做）

- 以图搜图（同模态图片→图片检索；CLIP 天然支持，留作后续小增量）
- OCR / 图片内文字提取后走文本链路
- FAISS 向量索引（假设照片库规模为数百级，内存暴力扫 <100ms 足够；`VectorStore` 端口已隔离演进路径）
- 照片库高级管理（相册/标签/EXIF/去重）
- 多用户与鉴权、分布式部署（沿袭 MVP 的 Non-goals）

## Assumptions

- 照片库规模数百级（MVP Open Question 8 的遗留假设，本期按此设计；数万级时另立 FAISS 变更）
- CLIP 模型采用 `OFA-Sys/chinese-clip-vit-base-patch16`：中文查询场景（"红塔与小花"）、512 维投影、hf-mirror 可下载；design 阶段可调整选型

## Related Future Work（本 change 之外的方向，探索阶段识别）

按优先级记录，各自另立 change：长跑稳定性（Py4J 通道振荡与 Windows 临时端口耗尽，gateway 日志实证）、Docker 部署实际验收（本机未装 Docker，镜像从未真实构建——本 change 的镜像变更同样待其验收）、检索质量评估集与阈值标定、模型切换按 (modality, model) 共存体验、SSE 进度推送。
