# tasks — 照片语义检索升级

## 1. Python 引擎：mock 标注器与重排器

- [x] 1.1 `registry.py` 新增 `vlm`（mock 填实，标记 mock 实现）与 `reranker`（BAAI/bge-reranker-base，CPU）槽位；验证：引擎健康查询返回两槽位且 vlm 如实标注 mock
- [x] 1.2 新增 `core/annotate.py` 确定性 mock 标注器（caption 优先 / 文件名派生 / jieba 分词提 tags / 中心词截断提 subject）；验证：同输入两次调用输出一致的单测（含带/不带 caption、时间戳文件名三类样本）
- [x] 1.3 新增 `core/rerank.py` 交叉编码器封装（惰性加载、加载失败显式错误、候选上限校验）；验证：单测覆盖打分有序返回、超上限拒绝、不可用显式报错三场景
- [x] 1.4 `server_py4j.py` 与 `server_stdio.py` 各加 `annotate` / `rerank` 两 op（JSON 字符串进出）；验证：双通道对同一输入产出一致结果的对照测试

## 2. Java 协议与领域端口

- [x] 2.1 `PythonProtocol` 增标注与重排的请求/响应 record；验证：编译通过 + record 字段与 Python 侧 JSON 键一一对应
- [x] 2.2 domain 层新增 `AnnotationProvider` / `RerankProvider` 端口及经 PythonChannel 的实现（沿 ClipEmbeddingProvider 模式）；验证：单测 mock channel 校验协议分派与错误映射
- [x] 2.3 图片资产实体加标注五字段（subject / description / tags / annotationMocked / annotationError）+ 持久化 `ALTER TABLE ADD COLUMN`×5；验证：存量库启动无迁移错误、旧行字段为 NULL、新行可读写

## 3. 摄取管线：标注阶段与双向量

- [x] 3.1 摄取状态机插入标注阶段（解析后、向量化前），标注失败不阻塞入库；验证：标注器抛异常时图片仍入库且 annotationError 记录原因的状态机测试
- [x] 3.2 标注文本「主题。描述」经 bge 编码写入 vector_entry（sourceType=image, modelKey=text-embedding-zh）；验证：双向量入库后 (image, clip) 与 (image, bge) 两空间各可命中
- [x] 3.3 `POST /images` 增可选 `caption` 参数；验证：带 caption 上传后详情返回派生标注且 mocked=true 的接口测试
- [x] 3.4 图片列表/详情/命中响应透出标注字段；验证：API 测试断言字段存在与存量图（NULL）占位语义

## 4. 检索：双路召回与重排

- [x] 4.1 图片模态检索升级双路召回（CLIP 像素路 + 描述文本路）RRF 融合，单路空退化直通；验证：仅描述命中、仅像素命中、双路命中三类用例的检索服务测试
- [x] 4.2 融合后 topK 送重排（query × 候选text），按重排分数取 topN；无标注候选殿后；验证：构造重排分序与召回序不同的用例断言序位变化
- [x] 4.3 重排降级：重排器不可用/显式关闭/候选全无标注 → 直通 + reranked=false + 原因标注；验证：mock 重排器报错时检索不失败的测试
- [x] 4.4 配置面落地（rerank.enabled / candidates、描述路阈值）+ `/search` 请求参数覆盖与响应新字段（旧字段不动）；验证：缺省参数下旧行为回归测试全绿
- [x] 4.5 三空间隔离专项测试：(text,bge) / (image,bge) / (image,clip) 混库下双向检索互不越界；验证：混合库存量测试用例通过

## 5. 批量导入

- [x] 5.1 `POST /images/batch`（多文件 + captions 映射 + 单请求上限校验，逐文件预校验记明细）；验证：50 文件含 3 不合格 → 47 受理 3 记因的接口测试
- [x] 5.2 异步导入任务（内存态、并发度 2、24h 完成态清理）+ `GET /images/import-tasks/{taskId}` 进度/明细；验证：任务执行中轮询可见进度递增、失败明细含文件名与原因
- [x] 5.3 千级导入实测（或 100 张缩样推算）：进度可见、导入期间检索可用、导入后双路检索命中；验证：实测记录于验证文档

## 6. 前端与部署

- [x] 6.1 React 图片列表/详情/命中卡片展示标注与 mocked 徽标、"未拆分"占位；验证：三类卡片视觉验收（mock/真实占位/未拆分）
- [x] 6.2 React 批量导入入口与进度反馈（轮询任务端点）；验证：批量上传后进度条推进、失败明细展示
- [x] 6.3 `docker/download_models.py` 增 bge-reranker-base；部署手册同步模型清单与内存建议（+1.1GB 权重 / 运行惰性）；验证：脚本 dry-run 列出目标模型
- [x] 6.4 端到端验证：caption="红色的小花" 上传 → 查询"红色的小花" → 命中含描述与重排分数；记录于 verification.md
