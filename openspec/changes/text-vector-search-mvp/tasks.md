# tasks.md — 文本向量化检索 MVP 实施任务

> 依赖顺序：1→2→3→4→(5∥6)→7→8→9。验收方式写在每个任务内。

## 1. 目录迁移与 Python 引擎升级

- [x] 1.1 将 `python/` 迁移至 `engine-server/python/`（git mv 保留历史），同步修改 `packagePython` 的取源路径与集成测试的仓库根定位；验证 `gradlew :engine-server:packagePython` 后 build/python-pack/python/ 下含全部脚本与 requirements.txt
- [x] 1.2 更新 `PythonProcessLauncher` 开发态探测路径（`./python`，bootRun 工作目录命中）并在候选列表保留 `./python-runtime`（jar 态）；验证 `gradlew :engine-server:bootRun` 日志出现"Python 脚本目录定位成功: ...python"
- [x] 1.3 registry 双模型注册：`text-embedding-zh`（bge-small-zh-v1.5，512 维，默认）与 `text-embedding-multilingual`（MiniLM，384 维），配置键选择；验证 `echo stats 请求 | python server_stdio.py` 手测返回双槽位且默认键为 bge
- [x] 1.4 实现 Python 通道选择器（Py4J 主 → stdio 惰性回退 → 主通道恢复探测切回，互斥锁保护，不双开常驻进程）；扩展 channelIT 增加回退场景；验证 `gradlew :engine-server:channelIT` 全部通过

## 2. 持久化与检索基础设施

- [x] 2.1 DatabaseMigrator 增加 vector_entry 表（document_id/chunk_seq/text/vector BLOB/dim/model/modality/degraded）并实现 SqliteVectorStore 读写；单测验证 float[] → BLOB → float[] 往返精度无损
- [x] 2.2 内存向量索引：启动预加载 + 摄取增量更新 + L2 归一化点积 top-k + 维度闸门（不一致抛明确错误）；单测覆盖排序正确性与维度不匹配场景
- [x] 2.3 Lucene 全文索引实现（SmartCN 分词、块写入/按文档删除/查询/高亮偏移）；单测验证中文关键词命中与删除文档后不再命中

## 3. 摄取应用层

- [x] 3.1 DocumentApplicationService 摄取用例：上传受理 → 固定线程池异步 → 解析 → 分块 → 批 16 向量化 → 向量+全文双入库 → 状态机推进（UPLOADED→…→INDEXED）；集成测试：上传 txt 最终到达 INDEXED 且块数>0
- [x] 3.2 失败路径与降级标记：解析失败/引擎超时 → FAILED 且原因入库可查；引擎 degraded → 文档与块携带降级标记；测试以假 EmbeddingProvider 模拟两种场景
- [x] 3.3 文档管理用例：分页列表（状态/块数过滤）、详情（模型/维度/降级/失败原因）、删除（向量+索引+文件联动清理）；测试删除后该文档独有内容检索不命中

## 4. REST 接口层

- [x] 4.1 DocumentController（POST /documents 上传、GET /documents 分页、GET /documents/{id}、DELETE /documents/{id}）+ @Valid 校验 + GlobalExceptionHandler 按错误码段（1xxx/2xxx/3xxx/5xxx）扩展；curl 冒烟四接口含 4xx 场景
- [x] 4.2 SearchController（POST /search：查询校验、RRF 融合 top-k、Caffeine 缓存可关、响应含分数/来源标注/高亮/耗时/degraded）；curl 验证中文语义查询命中、空查询 4xx、无命中空列表
- [x] 4.3 SystemController 扩展：/system/health 返回 server 自身、Python 引擎（当前通道/模型/维度/降级）、文档统计；curl 验证字段完整

## 5. 前端工程

- [x] 5.1 脚手架：`engine-gateway/frontend/` 初始化 Vite5+React18+TS+ReactRouter6+AntD5+axios，devServer 代理 /api→:8090，目录骨架（api/layouts/pages/components/hooks）；验证 `npm run dev` 空壳页可访问且代理连通 /api/system/health
- [x] 5.2 布局与路由：手写 CSS Grid 布局（顶栏 56px/侧边 208px/内容区，间距 4/8/16/24 体系）+ 路由 / /documents /search；验证三页切换顶栏侧边不动、浏览器前进后退正常
- [x] 5.3 API 客户端：统一信封解析（code≠0 抛错）、错误 toast、超时控制、documents/search/system 类型定义；验证后端停机时页面报统一错误且不崩溃
- [x] 5.4 仪表盘页：三组件健康卡片（含通道/模型/维度/降级原因）+ 文档统计 + 调用链路示意（浏览器→网关→server→Python）+ 定时刷新；验证手动杀 Python 子进程后卡片转红
- [x] 5.5 文档管理页：上传（进度/结果）、列表（状态徽标轮询自动流转）、删除确认、详情（块数/模型/维度/降级/失败原因）；验证上传后状态自动推进至"已索引"无需手动刷新
- [x] 5.6 检索页：查询提交、命中列表（片段高亮/相似度/来源标注）、计数与耗时、空态与错误态、会话内查询历史、降级全局横幅组件挂载；端到端手测含"红塔"式关键词高亮场景

## 6. 网关实现

- [x] 6.1 ProxyController：/api/** 剥前缀转发 127.0.0.1:8081，OkHttp 单例、InputStream 流式透传响应体、透传状态码与错误体；curl 经 :8090/api 完成上传与检索、server 停止时秒级 5xx
- [x] 6.2 SPA 回退与缓存策略：非 /api 未命中路径回 index.html（200），/assets/** 长缓存、index.html no-cache；验证 /search 直接刷新不 404
- [x] 6.3 健康聚合：@Scheduled 10s 探测 server（连续 3 次失败才 DOWN），透传 Python 引擎段，/api/system/health 三组件分层可见；验证停 server 后聚合转 DOWN
- [x] 6.4 构建集成：gateway build.gradle 的 frontendDir 指向模块内 frontend/，npmInstall→buildFrontend→copyFrontendDist 任务链打通；验证 `gradlew :engine-gateway:build` 产出的 jar 内 static/ 含前端产物、jar 启动后页面可访问

## 7. 端到端验收

- [x] 7.1 按 specs 六份的场景逐条过全链路手测（bootRun 双应用 + 页面操作：上传→状态流转→检索高亮→删除清理），产出勾选记录（记录：verification.md，全部场景含证据；实测产出阈值重标定 min-vector-score 0.40→0.48 与 Dockerfile.full AS builder 修复）
- [x] 7.2 中文编码全链路验证：中文文件名上传、中文查询往返、响应片段显示均无乱码（记录：verification.md §7.2；经网关全链路，含降级态）
- [x] 7.3 降级演练：屏蔽模型加载 → 哈希降级路径 → 文档降级标记/检索降级提示/健康降级展示/横幅出现与恢复（记录：verification.md §7.3，含确定性哈希与恢复闭环）

## 8. Docker 部署

- [x] 8.1 entrypoint.sh：server 先起→健康等待→gateway 前台托管、trap SIGTERM 优雅停全组、任一进程退出则容器非零退出；人工评审信号处理与等待逻辑（本机无 Docker，评审+shell 语法检查为验收）
- [x] 8.2 Dockerfile 本地构建轨：temurin:8-jre-jammy + python3/pip + 依赖安装 + 模型预下载脚本（hf-mirror，落 /app/models，SENTENCE_TRANSFORMERS_HOME 指向）+ COPY 两 jar + entrypoint；语法评审 + 分层合理性检查
- [x] 8.3 Dockerfile.full 多阶段全构建轨：stage1（gradle:8-jdk8 + Node22）构建 jar，stage2 复用本地轨运行时层；语法评审 + 与本地轨等价性说明
- [x] 8.4 docs/deployment.md：Docker 主路径（构建/运行/数据卷/模型卷/回滚）+ 裸机备选路径 + 故障排查表

## 9. 文档与规范

- [x] 9.1 docs/architecture.md：调用链全景图、DDD 分层与依赖方向、模块边界、图片扩展路径说明
- [x] 9.2 docs/backend-standards.md：Java 命名/分层边界/错误码分段/API 信封/异常处理/测试约定
- [x] 9.3 docs/frontend-standards.md：布局栅格与坐标定位体系、间距/字号 token、组件与命名规范、状态徽标约定
- [x] 9.4 docs/learning-path.md：面向 Java 背景新手的 Python 与前端分阶段学习导读 + 代码导览地图（每阶段指向本仓库具体文件）
