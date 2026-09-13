# 端到端验收记录（photo-semantic-search 5.3 / 6.4 / 6.1 / 6.2）

> 勾选记录随验证进度**增量更新**（每完成一个场景立即落盘）。
> 环境：Windows 11 + JDK8 + Python 3.13（torch 2.13 cpu / transformers 5.15 / sentence-transformers 6.0，四模型缓存齐：bge-small-zh-v1.5 + MiniLM + chinese-clip + bge-reranker-base——重排器为本次验证经 hf-mirror 现场下载 ~1.1GB）
> 启动方式：`gradlew :engine-server:bootRun`（:8081 直连，Python 引擎 py4j 通道随 JVM 启停）
> 测试图：`scripts/gen_e2e_photos.py` 生成的 101 张合成照片（1 张主图"红花.jpg" + 100 张中文描述性文件名缩样：红花/黄花/白花/蓝色的湖面/绿色的山/夕阳/石板路/薰衣草 系列）

## 6.4 端到端主链路（caption 上传 → 检索 → 命中含描述与重排分）

- [x] caption=「红色的小花」上传 → 受理即回，标注异步完成
  - 证据：`POST /images`（multipart file=红花.jpg + caption=红色的小花）→ 200，`status=PENDING`、`annotationMocked=null`（未拆分占位语义正确）；5s 后 `COMPLETED`
- [x] 详情返回派生标注且 mocked=true（任务 3.3 接口契约在真实栈复核）
  - 证据：`GET /images/{id}` → `subject="红色、小花"`（jieba 名词派生）、`description="红色的小花"`（**caption 优先规则生效**）、`tags=["小花","红色"]`、`annotationMocked=true`（mock 如实标记）、`degraded=false`（真实 CLIP 向量非哈希兜底）
- [x] 查询「红色的小花」→ 命中含描述与重排分数
  - 证据：`POST /search {"query":"红色的小花","modality":"image","topK":5}` → total=2，top1=主图：
    - `subject/description/tags/annotationMocked` 全透出（spec：命中响应带标注）
    - `rerankScore=0.99995`、`reranked=true`（真实 CrossEncoder 打分，与模型直测同查询 0.9999 一致）
    - `source=BOTH`（像素路 vectorScore=0.4305 + 描述路双命中）、融合分 0.0328 = 1/61+1/62（RRF k=60 数值精确吻合）
    - 无标注存量图殿后：`rerankScore=null`、单路 SEMANTIC、排第 2（未参与重排不伪造分数 ✓）
  - 延迟：首查 12795ms（含 bge-reranker-base ~1.1GB **惰性冷加载**，符合 design）；热路径复检 **151ms**、rerankScore 逐位一致
- [x] 修复后批量导入图的检索（重排对批量图同样生效）
  - 证据：查询「蓝色的湖面」→ top3 全为该系列（subject="蓝色、湖面"），`rerankScore=1.0`；查询「夕阳」→ top3 夕阳系列 rerank≈0.999；查询「红色的小花」top2 红花049 描述路命中 rerank=0.986（重排序与语义相关性一致）

## 5.3 百张缩样实测（千级推算）

- [x] 批量受理与执行（受理毫秒级回执 + 执行异步推进）
  - 证据：100 张（~1.5MB 总量）`POST /images/batch` → 受理即回 `{taskId, total:100, processed:0}`
- [x] 进度可见（轮询 processed 递增至终态）
  - 证据：首轮实测进度序列 `(0.4s→40) (12.8s→74) (23.4s→100)` 单调递增；终态 `succeeded=100 / failed=0 / rejected=0`
- [x] 导入期间检索可用（导入不阻塞检索）
  - 证据：任务 RUNNING 中段（processed≈40-74 区间）穿插 `POST /search {"query":"红色的花"}` → 356ms 正常返回、top1 仍为主图（检索精度不受并发导入影响）
- [x] 导入后双路检索命中
  - 证据：完成后「蓝色的湖面」「夕阳」查询均命中新导入系列（见 §6.4 第四条），描述路由标注文本驱动
- [x] 吞吐与千级推算
  - 证据：模型热态 100 张 / 16.4~23.4s ≈ **4.3~6.1 张/s**（并发 2）→ 千级推算 **约 2.7~3.9 分钟**（不含首图 CLIP 冷加载 ~10s；CPU only，GPU 环境更快）；结论：千到数千张照片在小时级内可完成导入，进度与可用性均满足 spec
- [x] 疑似重复提示（真实栈复核，提示不阻断）
  - 证据：同批 100 张修复后重导 → `duplicateSuspected` 100/100 全标注（同名同大小精确匹配存量），全部正常受理并 COMPLETED（design D5 语义 ✓）
- [x] **缩样实测暴露缺陷并修复**：无 caption 批量导入的标注从 uuid 存储路径派生（subject="f18737、b6db、a3" 之类无语义片段），描述路检索失效
  - 根因：管线把 `getStoredPath()`（存储层 uuid 重命名）传给标注器的"文件名派生"输入
  - 修复：协议 annotate 增 `filename` 字段贯穿（Python `annotate(path, caption, filename)` / Py4J `annotateImage(path, filename, caption)` / stdio payload / Java `AnnotationProvider.annotate(imagePath, filename, caption)`），管线传原始 `imageAsset.getFilename()`；真实 VLM 未来仍用 path 读像素，两者各取所需
  - 回归：Java 全量测试通过（cleanTest 重编译）；修复后重导 100 张全部正确派生（「蓝色的湖面052」→ subject="蓝色、湖面"）
  - 存量数据说明：修复前已入库的坏标注记录需删除重传方可获得正确标注（一次性操作，无自动迁移）

## 6.1 / 6.2 前端（构建级验证 + 视觉走查待用户浏览器复核）

- [x] 契约同步（types.ts 镜像后端新字段：ImageSummary 标注五字段 / SearchHitVo 标注+rerankScore / SearchResultVo reranked+rerankReason / 批量任务 ImageImportTaskVo）
  - 证据：`npm run build`（tsc --noEmit + vite）通过，4067 模块
- [x] 6.1 列表卡片三态视觉：`annotationMocked=null` → 灰字"未拆分（等待识别）"；`true` → 紫 Tag"模拟识别"+Tooltip；`false` → 主题·描述直出；附 tags 行（≤3 个 + 溢出计数）与"标注失败"hover 原因
- [x] 6.1 详情弹窗：原图 + Descriptions 全景（主题/描述/标签/标注来源三态徽标/状态/大小）
- [x] 6.1 命中卡片：主题·描述 + 识别来源徽标（模拟识别/已识别/未拆分）+ 蓝色"重排 {分数}"徽标；结果行"已重排/未重排（hover 原因）"
- [x] 6.2 批量导入：Dragger 暂存队列（可移除）→「开始导入（N 张）」→ 两段进度弹窗（上传网络进度 → 1.5s 轮询 processed/total）+ 明细列表（文件名 + 状态徽标 + 失败原因 hover + 疑似重复徽标）
  - 说明：三类卡片视觉验收与进度条推进的浏览器级走查待用户在 `:8090` 复核（后端行为已由本文件 §5.3/§6.4 实测背书）

## 环境备注

- Windows 下 gradle 停止后 bootRun 子进程（java/python）遗留占用 8081/25335，需 `taskkill //F //PID` 定向清理（Git Bash 路径转义：`//F`）——与部署手册故障排查第 6 条一致
- 重排器内存纪律复核：进程峰值 ~2.1GB（python.exe），CLIP+bge+reranker 三模型全部加载后仍符合部署手册"照片语义检索全开 8GB"档位下限
