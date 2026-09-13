# design — 照片语义检索升级（拆分标注 + 双路召回 + 重排 + 批量导入）

## Context

跨模态搜图已交付：上传→CLIP 像素向量→文本查询命中照片，(sourceType, modelKey) 空间隔离闸门、降级纪律、双通道协议均已就位（见 `add-image-crossmodal-search/design.md`）。与本设计直接相关的现状：

- **向量表零改动即可承载第二向量**：`vector_entry` 已有 sourceType / modelKey / text（冗余展示列）——图片描述路向量 = 新增行 (sourceType="image", modelKey=text-embedding-zh, text=标注文本)，闸门天然隔离，text 列还免费充当重排取文本来源
- **注册表扩展路径**：`registry.py` 的槽位制（text-embedding / clip 已填实）——`vlm` 与 `reranker` 是两个新槽位，对齐注释预留的三步扩展法
- **摄取管线可插阶段**：状态机 + 异步线程池 + 失败追溯均已参数化，标注阶段插在「解析后、向量化前」
- **约束**：JDK 1.8 / Spring Boot 2.6.14 锁死；Python 引擎 CPU-only；照片规模千到数千张；本期识别为 mock（用户决策：流程串通优先）

## Goals / Non-Goals

**Goals:**

- 「上传→拆分(mock)→双向量化→双路召回→RRF→重排→带标注响应」全链路端到端可用
- mock 与真实 VLM 的替换成本 = 换一个槽位实现（协议/字段/持久化零改动）
- 千级照片批量导入可执行、进度可见、部分失败不阻断
- 既有文本检索与跨模态检索行为零回归（旧客户端零感知）

**Non-Goals:**

- 真实 VLM 接入（另立变更）；Flutter 端（另立变更 `flutter-photo-client`）
- 文本模态检索与图片描述空间的跨模态融合（"搜文档顺带搜图"留作后续增量）
- 导入任务持久化与断点续传（内存态任务，重启丢任务不丢图片）
- 标注人工编辑界面

## Decisions

### D1 · mock 标注器：caption 优先 + 文件名派生，槽位隔离

`vlm` 槽位本期填 mock 实现，输入 (图片路径, 可选 caption)，输出 `{subject, description, tags[], mocked: true}`：

- caption 存在 → description = caption 原文；subject = caption 的名词性截断（首个名词短语，规则化：去修饰词取中心词，兜底整句）；tags = 分词后去停用词的实词列表（jieba 已在依赖树）
- caption 缺失 → 从文件名派生：去扩展名/时间戳模式（`IMG_20240101_123456`、`Screenshot_...`）后，剩余语义片段作为 description；无语义片段时 description = "未命名照片（{文件名}）"
- 确定性：纯函数，无随机、无时间依赖；同输入同输出

**备选与否决**：上传后异步补标注（解耦导入速度）——违背"标注先于第二向量"的管线顺序，且 mock 阶段标注成本为零，无异步必要；真实 VLM 到位后若单张秒级慢，再评估异步标注（记录于 Open Questions）。

### D2 · 描述路向量：复用 vector_entry 行模型，不新增表

图片描述文本经既有文本嵌入模型（bge）编码，写入 `vector_entry`：sourceType="image"、modelKey=text-embedding-zh、chunkIndex=0、text=「主题。描述」拼接、resourceId 指向图片资产。效果：

- **空间隔离零改动**：闸门 (sourceType, modelKey) 下，(image, bge) 与 (text, bge) 与 (image, clip) 三空间互不越界——bge 同模型不同 sourceType 是三个空间中的两个，隔离语义由二元组承担
- **重排取文本零改动**：候选行的 text 列即重排输入
- **图片列表/删除联动零改动**：按 resourceId 关联的清理逻辑覆盖两行

**备选与否决**：图片资产表加向量列——破坏"向量统一在 vector_entry"的单一事实源；新表 image_text_vector——同维同源数据的无谓分裂。

### D3 · 重排器：bge-reranker-base 交叉编码器，惰性加载

`reranker` 槽位填 `BAAI/bge-reranker-base`（sentence-transformers `CrossEncoder`，CPU）：输入 (query, candidates[]) → 等长分数列表。惰性加载对齐 vision.py 纪律（首次重排请求才加载，纯导入场景不付 ~1.1GB 内存）。不可用→显式错误→Java 侧降级直通 + `reranked=false`。

**候选规模**：默认 topK=20、硬上限 50（CPU 单对约 50-100ms，20 对 ≈ 1-2s 预算内；50 对为封顶容忍）。超限报错由调用方分批——检索服务层永远不超限（融合后截 topK 再送）。

**备选与否决**：bge-reranker-large（精度增益小、CPU 延迟×3）；colbert 系（部署复杂度高，收益不匹配照片检索场景）；重排放 Python 侧批量化后再异步化（本期同步即达标，异步引入结果一致性复杂度）。

### D4 · 检索编排：双路并行召回 → RRF → 重排

图片模态查询的执行计划：

```
query ──┬─ CLIP 文本编码 → 查 (image, clip) 空间 topK ──┐
        │                                                ├─ RRF(k=60) → topK′
        └─ bge 编码 ────── → 查 (image, bge) 空间 topK ──┘                    │
                                                            有标注候选？──no──▶ 直通返回 reranked=false
                                                                   │yes
                                                                   ▼
                                                    rerank(query × 候选text) → topN 返回
```

- 两路编码串行（复用同一 Python 进程，并行无益于 CPU）；任一路空则单路直通（沿既有"空空间"语义，但注意：双路全空才算库空）
- RRF 沿用文档混合检索的既有常数（k=60），不新造融合参数
- 重排仅对有 text 的候选；无 text 候选按召回序附于重排结果之后（行为确定：标注候选优先、未标注殿后）

**备选与否决**：加权分数融合（两路分数量纲不同，CLIP 与 bge 余弦分布不可公度——RRF 只用序位天然免疫）；描述路独占（放弃像素路则 mock 标注质量直接决定召回上限，双路互为冗余）。

### D5 · 批量导入：受控异步任务，内存态

- `POST /images/batch`：multipart 多文件 + 与文件一一对应的 captions（缺省全部无）；单请求上限（默认 100 文件 / 500MB，可配）；受理即建任务返回 taskId，逐文件预校验（不合格记明细不入队）
- 任务执行：复用现有摄取异步线程池，**导入并发度默认 2**（可配）——千张照片瓶颈在 CLIP+bge CPU 编码（合计约 200-400ms/张），并发 2 下千张约 5-15 分钟，可接受；并发过高会挤压检索请求的 Python 调用
- `GET /images/import-tasks/{taskId}`：状态 / 已处理 / 成功 / 失败明细（文件名+原因）；任务表内存 ConcurrentHashMap + 完成态保留 24h（定时清理）
- Flutter/脚本侧分批调用（每批 ≤ 上限）天然形成断点：中断后重传，已入库文件按文件名+大小去重提示（本期只提示不自动跳过——去重属 Non-goal，明细中标注"疑似重复"）

**备选与否决**：zip 整包上传（解压服务端做，校验面扩大；Flutter 端多文件选择器已够用）；任务持久化（重启丢任务不丢图片，重传成本低，不值得引入任务表迁移）。

### D6 · 状态机与领域模型落位

- `ImageStatus` 增加标注相关阶段表述（具体枚举名实施时对齐现有状态机：在「解析完成」与「向量化中」之间插标注，或复用处理中态 + 标注字段回填——倾向显式新态，失败追溯粒度值得）
- 图片资产实体加标注五字段（subject / description / tags / annotationMocked / annotationError）；领域层新端口 `AnnotationProvider`（对应 vlm 槽位）与 `RerankProvider`（对应 reranker 槽位），均经 PythonChannel 走 JSON 协议——沿 ClipEmbeddingProvider 的既有模式，不引第二进程
- `PythonProtocol` 增两组 record（标注请求/响应、重排请求/响应）

### D7 · 配置面

| 配置项 | 默认 | 说明 |
|---|---|---|
| `engine.search.rerank.enabled` | true | 检索端点参数可逐请求覆盖 |
| `engine.search.rerank.candidates` | 20 | 融合后送重排的候选数（硬上限 50） |
| `engine.search.image-desc.min-vector-score` | 沿用文本路现值 | 描述路阈值独立可标定 |
| `engine.images.batch.max-files` | 100 | 单请求文件数上限 |
| `engine.images.batch.max-bytes` | 500MB | 单请求体量上限 |
| `engine.images.import.concurrency` | 2 | 导入任务并发度 |

## Risks / Trade-offs

- [重排 CPU 延迟 1-2s 叠加在检索上] → 默认候选 20 + 逐请求可关；延迟不达标先降候选数（D3），不换模型
- [千张导入占用 Python 进程数分钟到十几分钟] → 并发 2 受控 + 进度可见；极端不满足再议导入专时段/独立进程（记录于 Open Questions）
- [mock 标注的检索说服力依赖 caption] → 预期行为（proposal 已声明）；演示与自验走 caption 路径；真 VLM 变更消除
- [bge-reranker-base 权重 +1.1GB，镜像与首启下载变慢] → download_models.py 纳入 + hf-mirror；惰性加载保住运行内存下限
- [导入任务内存态，重启即失] → 明细已持久化在图片资产状态里，任务壳可重建；文档明示
- [(image, bge) 空间与文档 (text, bge) 同模型异空间] → 闸门二元组已覆盖，专项测试双向不越界（沿 D4 前变更的隔离测试模式补第三空间用例）

## Migration Plan

1. 图片资产表 `ALTER TABLE ADD COLUMN` × 5（SQLite 支持列追加；存量行 NULL = 未拆分）
2. `vector_entry` 零迁移（描述路向量是普通新行）
3. `download_models.py` 增 bge-reranker-base；requirements.txt 零新增（CrossEncoder 属 sentence-transformers）
4. 回滚：回滚代码即可——新列无害、新向量行随图片删除联动清理、旧检索行为不依赖新行

## Open Questions

- 导入期间检索延迟是否可感知（并发 2 下 Python 调用排队）——实施后实测，必要时导入任务让路检索（简单信号量）
- 无标注候选在重排结果中的归位（殿后 vs 剔除）——spec 已定"殿后且确定"，实施时以命中卡片 UI 反馈校验体验
- mock 的 subject 中心词提取规则覆盖率（中文短语截断）——实施时以典型样本集标定，规则不追求语言学完备
