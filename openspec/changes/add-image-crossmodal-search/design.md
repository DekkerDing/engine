# design — 图片跨模态检索

## Context

文本 MVP 已交付完整链路（摄取→bge 向量化→混合检索），且为图片预留了全部接缝（见 proposal「Why」与 `docs/architecture.md` 第 6 节）。与本设计直接相关的现状：

- **端口**：`EmbeddingProvider`（`modality()` / `modelKey()` / `dimension()` / `embedBatch()`）——注册制设计，注释明示图片扩展 = 新增 `ClipEmbeddingProvider` 并存注册、按模态路由
- **资源抽象**：`VectorableResource`（`resourceId()` / `sourceType()` / `asText()`）——注释明示 IMAGE 模态的 `asText()` 返回图片路径
- **存储**：`VectorEntry` 已带 `sourceType`（text|image）/ `modelKey` / `chunkIndex`（图片固定 0）/ `text`（冗余展示列）；`vector_entry` 表混存设计已备，零迁移
- **索引**：`InMemoryVectorIndex.search(query, topK, sourceType)` 已有 sourceType 过滤参数，但闸门条件是「维度相等」——**bge 与 chinese-clip 同为 512 维时闸门放行，跨空间污染**（本设计必须修复的正确性缺口）
- **Python**：`registry.py` 的 `clip` 槽位为 None；注释给出扩展三步（填槽位 / `core/vision.py` / 双通道入口加 op）
- **管线**：文档摄取的状态机、异步线程池、降级纪律（degraded 标志透传）、双通道回退均可复用

## Goals / Non-Goals

**Goals:**

- 「红塔与小花」文本搜图端到端可用：上传图片 → CLIP 向量化 → 文本查询命中照片
- 向量空间隔离的正确性：按 (sourceType, modelKey) 分组，杜绝同维异空间的错误比较
- 与文本能力并存且零干扰：既有文档/检索行为与 API 完全不变
- 复用既有管线与纪律（状态机、异步、降级标志、双通道、JSON 协议），新代码沿既有 DDD 分层落位

**Non-Goals:**

- 以图搜图、OCR、FAISS、照片库高级管理（见 proposal Non-goals）
- 不修改 `EmbeddingProvider` / `VectorableResource` 等既有 domain 接口签名（只新增实现类；端口缺口仅允许新增方法）
- 不改动 engine-gateway（哑管道，multipart 透传与静态资源服务已就绪）
- 不处理 Windows 长跑端口耗尽问题（正交的已知隐患，另立稳定性 change）

## Decisions

### D1 · CLIP 模型：`OFA-Sys/chinese-clip-vit-base-patch16`

中文查询场景（"红塔与小花"）首选中文 CLIP；512 维投影；`transformers` 原生 `ChineseCLIPModel/Processor`（torch 已在依赖树，仅新增 `pillow`）；hf-mirror 可下载；与 registry.py 注释中的预留示例一致。

**备选与否决**：`openai/clip-vit-base-patch32`（英文为主，中文查询效果差）；`chinese-clip-vit-large-patch16`（体积约 3 倍，数百照片规模不值得）；M-CLIP 系多语模型（社区维护分散）。有趣的副作用：它恰好与 bge 同为 512 维，成为 D4 空间隔离的活教材。

### D2 · Java 侧：双 Provider 并存 + 按模态路由

新增 `ClipEmbeddingProvider`（`@Component`，`modality()=CROSS`，`modelKey()="clip"`）与既有 `ChannelEmbeddingProvider`（TEXT）并存，共用同一 `PythonChannel`（不拉第二个 Python 进程）。application 层新增轻量注册表（Spring 注入 `List<EmbeddingProvider>`，按目标模态查找）——沿用端口注释预留的注册制路径，拒绝 if-else。

`embedBatch` 的分派：入参 `ImageAssetResource` → Python 图片编码 op（传存储路径，Python 读像素）；入参轻量查询资源 → CLIP 文本编码 op。查询文本编码不新增端口方法：包一个单元素 resource 走 `embedBatch` 即可。

**备选与否决**：改造 `ChannelEmbeddingProvider` 支持双模态——违反「一个 provider 一个模态」的既有设计意图，且把两条降级/维度语义搅在一起。

### D3 · 跨模态检索路径：编码器必须与目标空间同源

检索请求新增 `modality` 参数（默认 `text`）：

- `modality=text`：查询经 bge 编码 → sourceType="text" 过滤 → 语义+全文混合检索（现状不变）
- `modality=image`：查询经 **CLIP 文本编码器**编码 → sourceType="image" 过滤 → **仅语义路**（Lucene 对图片无意义），RRF 融合退化为单路直通，不因缺全文命中而报错

这是跨模态的核心机制：查询向量必须与候选向量同空间，因此 image 模态绝不能复用 bge 编码查询。

### D4 · 空间隔离闸门升级：维度 → (sourceType, modelKey)

`InMemoryVectorIndex` 的候选过滤与闸门条件从「维度 == 查询维度」收紧为「(sourceType, modelKey) == 查询所属空间」。维度只是空间的弱代理（512 巧合即穿证），`(sourceType, modelKey)` 才是空间的身份证。错误语义沿袭既有纪律：目标空间有候选→正常检索；目标空间为空但库内有其它空间→400 明确报「不能比」而非装作「没找到」。

**兼容性**：存量文本向量（sourceType="text"，modelKey=text-embedding-zh）在新闸门下行为与旧闸门等价（同模型文本检索本就同维同类），既有客户端零感知。

### D5 · 图片摄取：复用文档管线，独立 REST 资源

- `ImageAssetResource` 实现 `VectorableResource`（`sourceType()="image"`，`asText()`=存储路径）
- 「解析」= 魔数校验 + 宽高/格式元信息；不切块（一图一资源，chunkIndex=0）
- 状态机、异步线程池、失败追溯全部复用；`vector_entry.text` 冗余存文件名（命中列表展示用）
- 原件存 `data/images/`（与 `data/documents/` 同构）；删除联动清理向量+原件+缓存
- REST：独立 `/images` 端点集（上传/列表/详情/删除 + **`GET /images/{id}/file` 原图访问端点**——缩略图渲染需要浏览器可寻址的图片 URL；gateway 反代 `/api` 前缀即可，零改动）

**备选与否决**：泛化 `/documents` 承载图片——资源语义混浊（分块/全文路等文档概念对图片无意义），前端与 API 都更难理解。

### D6 · Python 侧：对齐文本引擎的全部纪律

- `registry.py`：clip 槽位填实（`OFA-Sys/chinese-clip-vit-base-patch16`，512，"cross"）
- 新增 `core/vision.py`：CLIP 双编码器（图片/文本），**惰性加载**（首次图片请求才加载，避免纯文本使用场景白付 ~400MB 内存）、降级哈希 + degraded 标志、同图编码确定性——逐条对齐 `embeddings.py` 的既有纪律
- `server_py4j.py` / `server_stdio.py` 各加两个 op：图片编码、CLIP 查询文本编码；协议仍是 JSON 字符串进出
- `PythonProtocol.java` 同步加对应 record；`requirements.txt` 增 `pillow`

### D7 · 前端：与文档页同构的两个新页面

图片管理页（上传多选/拖拽、缩略图网格、状态、删除二次确认）与图片检索页（查询→缩略图卡片+相似度分数）。命中卡片按 `sourceType` 差异化渲染（图片卡片 vs 文本片段卡片）。API client 扩展图片端点；布局沿袭全站标准化体系。

### D8 · 配置：图片检索独立阈值

`min-vector-score` 按 modality 拆分配置（CLIP 与 bge 的分数分布不同，共用阈值必然一头失准）；图片阈值默认值实施中标定（CLIP 余弦分布经验上低于 bge，预期 0.2 左右起步）。

## Risks / Trade-offs

- [Python 进程内存增至 ~1.6GB（bge + CLIP 共存）] → CLIP 惰性加载（D6）；资源建议从 4GB「舒适」提为 6GB，部署手册同步
- [同维异空间污染（512 巧合）] → D4 闸门 + 专项测试：混合库存量下双向检索互不越界
- [chinese-clip 在 hf-mirror 的可得性未验证] → 实施第一步即验证下载；失败则先解决镜像源再动代码，降级路径只能作为兜底而非验收态
- [图片检索增加 Python 调用频率，可能加速触发已知的长跑端口耗尽隐患] → 本变更不修（正交）；开发机短跑验证不受影响；常驻运行等稳定性 change
- [原图直出做缩略图，网格加载慢] → 数百张规模 + 浏览器 lazy loading 先行；卡顿成为现实问题再引入生成缩略图（记录于 Open Questions）
- [gif 等动图编码语义模糊] → 取首帧，文档明示

## Migration Plan

1. `vector_entry` 表**零迁移**（sourceType/modelKey 列已在；新行 sourceType="image"）
2. `data/images/` 首启自动创建；`requirements.txt`、`download_models.py`、配置项随代码走
3. Docker：重建镜像（模型层 +400MB，其余层缓存命中）
4. 回滚：直接回滚代码——存量文本向量与数据不动，文本链路无感；`data/images/` 目录保留无害

## Open Questions

- 图片检索 `min-vector-score` 默认值（实施中标定，不改变结构）
- 缩略图是否需要服务端生成（先原图 + lazy loading，卡了再做）
- 上传图片是否附带可选描述文本（存 `text` 列改善命中列表可读性；不影响向量——CLIP 只吃像素；实施时顺手定）
