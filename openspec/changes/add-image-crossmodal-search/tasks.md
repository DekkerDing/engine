# tasks.md — 图片跨模态检索实施任务

> 依赖顺序：1→2→(3∥4)→5→6→7。验收方式写在每个任务内。
> 前置提醒：任务 1.1 是全变更的可行性闸门——chinese-clip 下载失败时先解决镜像源再动代码。

## 1. Python 引擎图片能力

- [x] 1.1 可行性闸门：验证 `OFA-Sys/chinese-clip-vit-base-patch16` 可从 hf-mirror 下载并本地加载，对一张真实照片编码出 512 维向量；`requirements.txt` 增加 `pillow` 并验证 `pip install` 成功
- [x] 1.2 `registry.py` 填实 clip 槽位（模型名/512/cross）；验证 `python -c` 调 `describe()` 显示 clip 规格而非 null
- [x] 1.3 新增 `core/vision.py`：CLIP 双编码器（图片编码 + 查询文本编码），逐条对齐 `embeddings.py` 纪律——惰性加载（首次请求才加载模型）、模型不可用降级为确定性哈希向量 + degraded 标志、同图重复编码结果一致；手测同一图片编码两次向量一致
- [x] 1.4 `server_py4j.py` 与 `server_stdio.py` 各加图片编码与 CLIP 查询文本编码两个 op（JSON 字符串进出）；验证 `echo 编码请求 | python server_stdio.py` 行协议手测通过，损坏图片数据返回明确错误而非崩溃
- [x] 1.5 `PythonProtocol.java` 同步新增响应 record 与通道方法；验证编译通过且 `channelIT` 既有用例不回归

## 2. 空间隔离与存储

- [x] 2.1 新增 `ImageAssetResource` 实现 `VectorableResource`（sourceType="image"、asText()=存储路径）；单测验证三个方法契约
- [x] 2.2 `InMemoryVectorIndex` 闸门从「维度相等」升级为「(sourceType, modelKey) 匹配」；单测覆盖三个场景：混合库（text/bge + image/clip 同为 512 维）下双向检索互不越界、目标空间为空且库内有其它空间时抛 400、存量纯文本库行为与升级前一致
- [x] 2.3 `SqliteVectorStore` 验证 image 行读写（零表迁移）；单测验证 sourceType="image" 行的写入、按文档删除与启动全量加载

## 3. 摄取管线与 Provider 注册

- [x] 3.1 新增 `ClipEmbeddingProvider`（modality=CROSS、modelKey="clip"，共用既有 PythonChannel）与 application 层按模态路由的 provider 注册表；验证 Spring 上下文双 provider 并存、按模态查找正确
- [x] 3.2 图片上传用例：格式白名单（jpg/png/webp/bmp/gif）+ 魔数校验 + 20MB 上限 + 原件落 `data/images/` + 复用状态机异步摄取（一图一向量、modality=IMAGE 入库、degraded 透传）；集成测试上传真实图片到达 INDEXED 且恰有一条向量，假扩展名与超限文件被拒
- [x] 3.3 图片管理用例：分页列表（状态过滤）、详情（模型/维度/降级/失败原因）、删除（向量+原件+索引联动清理）；测试删除后检索不再命中

## 4. 跨模态检索

- [x] 4.1 检索服务模态路由：`modality=text` 走既有 bge+全文混合路径（行为不变）；`modality=image` 查询经 CLIP 文本编码 → 仅对 IMAGE 向量语义 topK（单路直通，不调全文索引）；`min-vector-score` 按 modality 拆分配置；集成测试混合库存量下「红塔」文本命中已摄取的红塔照片且分数降序
- [x] 4.2 回归验证：不带 modality 参数的检索与文本 MVP 行为逐字段一致（既有客户端零改动）；响应携带模态标注、耗时与 degraded

## 5. REST 接口层

- [x] 5.1 `ImageController`：POST /images（multipart 上传）、GET /images（分页）、GET /images/{id}、DELETE /images/{id}、GET /images/{id}/file（原图访问，Content-Type 按魔数）；curl 冒烟五端点含 4xx 场景（假扩展名/超限/不存在 ID）
- [x] 5.2 `SearchController` 增加 modality 请求参数（缺省 text）；curl 验证缺省行为回归一致、image 模态返回图片命中与分数

## 6. 前端

- [x] 6.1 API client 扩展：images 端点封装、检索请求 modality 字段、图片命中类型定义；验证后端停机时图片页报统一错误不崩溃
- [x] 6.2 图片管理页：多选/拖拽上传、缩略图网格（lazy loading）+ 状态轮询推进、删除二次确认、上传拒绝原因提示；手测上传后状态自动推进至已索引
- [x] 6.3 图片检索页：查询输入 → 命中缩略图卡片（相似度分数降序、可查看原图）、空结果态、降级横幅；命中卡片按 sourceType 与文本片段卡片差异化渲染
- [x] 6.4 导航与路由整合（/images、/search/images 入口）；回归手测文档管理页与语义检索页行为与现状一致

## 7. 部署与收尾

- [x] 7.1 `docker/download_models.py` 增加 chinese-clip 预下载；验证本地 `docker build -f docker/Dockerfile` 模型层包含两个模型权重（若本机 Docker 仍不可用，记录为待 Docker 验收 change 的存量项）
- [x] 7.2 docs 更新：architecture.md 扩展路径章节转为现状描述、deployment.md 模型清单/镜像体积/资源建议（4GB→6GB 舒适线）、learning-path.md 补 vision.py 导览
- [x] 7.3 端到端验收：干净数据目录启动 → 上传多张「红塔与小花」照片 → 文本"红塔"检索命中 → 删除照片后不再命中 → 全程文本检索/文档管理无回归；验证 degraded 场景（断网重启后上传）标志透出到前端
