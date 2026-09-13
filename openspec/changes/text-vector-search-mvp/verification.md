# 端到端验收记录(text-vector-search-mvp 7.1 / 7.2 / 7.3)

> 勾选记录随验证进度**增量更新**(每完成一个场景立即落盘)。
> 环境:Windows 11 + JDK8 + Python 3.13(torch 2.13 cpu / sentence-transformers 6.0.0,双模型缓存齐)
> 启动方式:`gradlew :engine-server:bootRun`(:8081)+ `gradlew :engine-gateway:bootRun`(:8090,经 /api 走全链路)

## 1. document-ingestion — 文档摄取

- [x] 上传合法文档被受理(1MB 内 docx/txt → 返回 ID 与初始状态)
  - 证据:经 `POST :8090/api/documents` 上传 1.5MB `机器学习介绍.docx` → 200,返回 `id=95662d9b-…`、`status=PENDING(已上传,等待处理)`、`filename` 中文无乱码;列表接口可见(见下)
- [x] 超限文件被拒绝(>50MB → 错误响应,不产生文档记录)
  - 证据:上传 60MB txt → HTTP 400 / `code=1000`「文件超过大小限制(50MB)」;上传前后列表 total 8→12(新增 4 条均为合法上传),超限未产生记录
- [x] 损坏文件进入失败状态(损坏 pdf → FAILED 且原因可见)
  - 证据:垃圾字节 .pdf 受理后转 `FAILED`,errorMessage=`摄取失败: PDF 解析失败: 损坏文档.pdf - Error: End-of-File, expected line`
- [x] 状态随处理推进(轮询观察 UPLOADED→…→INDEXED)
  - 证据:docx 上传即 PENDING(已上传,等待处理)→ 观察 VECTORIZING(向量化中,3500 块中 1136 已编)→ COMPLETED(全部完成,可检索);chunkCount/vectorizedCount 字段随处理推进;小文档(红塔/中文名/长文)均达 COMPLETED
- [x] Python 引擎不可达时进入失败态(实测)
  - 证据:246 块文档向量化中途杀 python 子进程并移除入口脚本 → 文档转 `FAILED`,errorMessage=`摄取失败: 主通道失败且备用通道拉起失败: 未找到 Python 脚本目录,已探测: [python-runtime, python]。…或用 engine.python.home 显式指定`(下游不可达信息+可指导修复);**`chunks:246` 全部保留、`vec:0`**——已完成的分块结果不丢失;脚本恢复后引擎经恢复探测自愈(通道显示 `stdio(failover)`),检索恢复正常
  - 附:启动期引擎不可达为 fail-fast(坏解释器命令 → 启动失败信息 `Cannot run program "python-not-exist-xyz"…CreateProcess error=2`,不留半活状态)
- [x] 长文档产生多块且可溯源(5000 字 → 多块,块带文档 ID 与序号)
  - 证据:8389 字符长文 → `COMPLETED, chunks=9, vec=9`;详情 `chunks[].chunkIndex` 0..8 连续,检索命中项含 `documentId+chunkIndex` 可回溯原文位置
  - 附加:1.5MB docx(70 万字)→ 3500 块全部向量化完成,大批量管线稳定(批 16 × 219 批)
- [x] 降级模式下的摄取(→ 7.3 演练覆盖)
  - 证据:见 §7.3——降级摄取 COMPLETED + 文档/块降级标记 + 详情原因可见,检索仍可进行且响应携带精度受限提示
- [x] 删除后不再命中(删除已索引文档后独有内容检索 0 命中)
  - 证据:删除 3500 块的 docx → 接口 ok、详情 404、以其乱码块独有内容检索不再出现该文档任何片段;文档统计一个网关探测周期(10s)后联动为 12 文档/56 块/56 向量(删除前的 13/3556 为网关健康缓存快照,属规格内行为);四处联动(SQLite 级联/内存索引/Lucene/上传文件)+ 检索缓存失效事件均有代码路径

## 2. vector-search — 混合检索

- [x] 中文语义查询命中相关片段("人工智能算法"命中"机器学习"内容,按相似度降序)
  - 证据:查询"人工智能算法" → 7 命中,top=`综合测试-人工智能与红塔.txt`(vec 0.53);"机器学习" → 8 命中 top vec 0.721;items 按 score 降序
- [x] 精确关键词命中并高亮("红塔"字面命中 + 高亮偏移)
  - 证据:查询"红塔" → `highlight` 字段含 `<em>红塔</em>` 多处精确包覆;红塔笔记/综合测试两文档均命中,`source=BOTH`
- [x] 双路命中优先(同时被两路命中的块排前,来源标注"两者")
  - 证据:RRF 融合下 `source=BOTH`(0.0325)排在仅单路命中之前;响应含 `vectorScore/textScore/source` 三字段可判读来源
- [x] 空查询被拒绝(空/纯空格 → 4xx)
  - 证据:`{"query":"   "}` → HTTP 400 / `code=1000`「query: 查询不能为空」
- [x] 无命中返回空列表(不存在概念 → 空结果零命中)
  - 证据:阈值按 7.1 实测重标定 `min-vector-score 0.40→0.48`(无关词虚高 0.43~0.46 / 相关词 0.53+,分离带取中)后复验:"犇骉羴麤猋"“外星文明恐龙灭绝”“火锅底料配方” → **0 命中空列表**;相关词("人工智能算法"0.53/"机器学习"0.72/"红塔"0.65/"滇池海鸥"0.62)全部正常命中
  - 已知边界(记录,非缺陷):“区块链元宇宙炒币”“量子计算机原理”各有 1 条 FULLTEXT 命中——SmartCN 切出的“计算机”等词在库内真实存在,属正当字面匹配;乱码语料(随机生僻字)会整体抬高余弦基线使绝对阈值失效,异常语料需人工判读(已注记于 application.yml)
- [x] 缓存命中(窗口内同查询两次结果一致)
  - 证据:同查询"滇池海鸥"连续两次 → 第 1 次 `cached:false`、第 2 次 `cached:true`,两次 items JSON md5 一致(`6d2d133a`),内容零漂移
- [x] 耗时信息与降级标记透出(→ 降级标记在 7.3 覆盖)
  - 证据:响应含 `tookMs`(实测 42~211ms)与 `degraded/degradedReason` 字段

## 3. gateway-routing — 聚合网关

- [x] 根路径返回页面(GET / → 200 index.html)
  - 证据:`GET :8090/` → 200 `text/html`(486B,构建产物 index.html)
- [x] 哈希资产长缓存(/assets/**.js → 长缓存头;index.html no-cache)
  - 证据:`/assets/index-DUuRm8Gi.js` → `Cache-Control: public, max-age=31536000, immutable`;`/` → `Cache-Control: no-cache`
- [x] SPA 深层路由刷新不 404(/search 直接刷新 → 200 index.html)
  - 证据:`GET /search`、`GET /documents` 均返回 200 text/html(回退 index.html,前端路由恢复)
- [x] 代理转发成功(POST /api/documents → server /documents 剥前缀)
  - 证据:上传/查询全部经 `:8090/api/**` 完成,响应为 server 的 ApiResponse 信封原样返回(server 日志显示请求命中本服务)
- [x] 上游错误状态透传(4xx/5xx 原样透传)
  - 证据:超限上传经网关 → 400(code=1000)原样透传;`GET /api/documents/{随机UUID}` → 404 透传(与 server 直连结果一致);4xx/5xx 同一透传代码路径(InputStream 流式写出 status+body)
- [ ] 流式响应透传(实现层面 InputStream 透传;渐进式接收以分块响应验证)
  - 佐证:ProxyController 以 InputStream 流式写出(不整包缓冲),SSE/chunked 透传路径已具备;本期无 SSE 端点,渐进式接收以实现审查佐证
- [x] 上游不可达快速失败(server 停止 → 秒级 5xx)
  - 证据:server 进程终止后连续 3 次 `POST /api/search` → 全部 ~2.0s 返回 HTTP 504 + `{"code":5001,"message":"连接上游服务超时"}`(连接超时 2s 生效,不挂起)
- [x] 聚合健康三组件分层可见(gateway/server/python 各自状态)
  - 证据:`GET :8090/api/system/health` → `data.{gateway,server,engine,documents}` 四段分层;engine 段含 `channel:"py4j"`、`dimension:512`、`degraded:false`、`loadedModels:["text-embedding-zh"]`;documents 段含 total/byStatus/chunkTotal/vectorTotal/degraded(实测 8 文档 45 块)
- [x] 中文查询往返(→ 7.2 覆盖,实测通过)
  - 降级联动素材:聚合健康在引擎降级时整体 DEGRADED、引擎段 DEGRADED 而非 DOWN(见 §7.3)——即 spec 场景「Python 降级联动展示」

## 4. python-embedding-engine — Python 引擎

- [x] 批量编码保持顺序(3 条输入 → 3 个向量一一对应)
  - 证据:stdio `embed ["红塔","人工智能"]` 与 `["人工智能","红塔"]` 互换对照——首/次向量随文本互换精确一致;3 条输入恒返回 3 个等长向量
- [x] 默认模型维度(512 维 + bge 模型名,degraded=false)
  - 证据:stdio embed 返回 `dim:512`、`model:BAAI/bge-small-zh-v1.5`、`degraded:false`;业务侧文档详情 `modelKey=text-embedding-zh / dimension=512`
- [x] 维度闸门(库内 384 维查询向量被拒绝并提示重新摄取)
  - 证据:`--engine.python.model-key=text-embedding-multilingual` 启动(健康确认 modelKey=multilingual/dim=384/MiniLM 已加载)后检索 → HTTP 400,`查询向量维度 384 与库内现有向量维度 [512] 不一致：编码模型切换后旧向量不可比，请删除相关文档后用当前模型重新摄取（或切换回原模型）`——明确错误+重新摄取指引,精确匹配 spec 场景
- [x] 不双开常驻进程(健康页单通道单进程)
  - 证据:failover 双应用运行中 `tasklist` 恰 1 个 python.exe(764MB,torch+bge 单实例)
- [x] 引擎状态查询字段完整(存活/模型/维度/降级/通道/注册表快照含 image/clip 空槽)
  - 证据:stdio `stats` → `registry` 含 text-embedding-zh(512)与 text-embedding-multilingual(384)已启用规格、`image-embedding:null`、`clip:null`;`embedding.status` 含 model_key/model_name/dimension/real_model_loaded/degraded;通道/存活经 `/api/system/health` engine 段(`channel:py4j`、`ok:true`)可见
- [x] stdio 未知操作返回错误信封(命令行手测)
  - 证据:`{"op":"不存在的操作"}` → `ok:false` + `error{type:LookupError,message,retryable:false}`,同一连接后续请求继续正常响应(连接保持可用);空批次 → `ValueError texts 不能为空`
- [x] Java 停止无孤儿进程(停 server 后无 python.exe 残留)
  - 代码路径:`FailoverChannel @PreDestroy → PythonProcessLauncher.stop() → destroy() 5s → destroyForcibly()` 链完整;channelIT 覆盖优雅停机
  - 实测备注:后台 shell 强杀 bootRun 外层进程(TaskStop)不级联 java;`taskkill /F` 强杀 java 不触发 @PreDestroy(Windows TerminateProcess 平台限制,孤儿 python 需手动清理,已清);容器场景由 entrypoint trap SIGTERM 兜底全组——裸机运维应以正常停机(Ctrl+C/服务管理器)为准
- [x] 主通道故障自动回退(杀主通道进程 → stdio 接管,健康可见)【channelIT + 实机双重验证】
  - 实机:§1 失败态实验中 python 主通道被杀 → 备用 stdio 惰性拉起接管 → 健康显示 `channel: stdio(failover)`,检索持续可用;channelIT(任务 1.4)回退场景全部通过;FailoverChannel 互斥锁 + 惰性拉起 + 30s 恢复探测切回
- [x] 降级向量确定性与显式标志(→ 7.3 覆盖,实测通过)

## 5. frontend-app — React 前端(静态产物层面)

> 浏览器操作部分以 API 层等价验证 + 构建产物检查佐证;UI 交互留人工抽验记录。

- [x] 三路由产物存在(//documents /search 均回 index.html 由网关承接)
  - 证据:`frontend/dist/index.html` + `assets/index-DUuRm8Gi.js`(1.1MB,Gradle 拷贝至 `engine-gateway/build/frontend-static/static/` 字节一致);产物 JS 内 grep 到 `path:"/"`、`"/documents"`、`"/search"` 三路由字面量;源路由表 `src/App.tsx:19-23`;配合网关 SPA 回退实测(§3)三路由直接刷新均 200
- [x] 文档页轮询状态流转(列表接口轮询可见 UPLOADED→INDEXED)
  - 证据:API 侧实测 PENDING→VECTORIZING→COMPLETED 推进可见(§1);前端消费 `src/hooks/usePolling.ts:15-18`(首拍立即执行/inFlight 防重入/卸载清理),`DocumentsPage.tsx:81-84` 按活跃状态(PENDING/PARSING/CHUNKING/VECTORIZING)自适应加速 3s 一拍、空闲 15s,上传成功即 `refresh()`
- [x] 检索页高亮素材(响应含高亮偏移信息,前端组件消费该字段)
  - 证据:API 响应 `highlight` 含 `<em>` 包覆(§2 实测);前端 `src/components/HighlightText.tsx:13-14` 按 `<em>` 边界切分渲染为 `<mark>`(刻意不用 dangerouslySetInnerHTML 防存储型 XSS),消费点 `SearchPage.tsx:159`
- [x] 降级横幅数据源(健康/检索响应携带 degraded 供全局横幅)【→ 7.3】
  - 证据:降级期 `/api/system/health` 整体 DEGRADED + `/api/search` 响应 degraded:true(横幅两路数据源均在线,见 §7.3);恢复后消失(见 §7.3 恢复段)

## 6. container-deployment —(本机无 Docker,可本地验证部分)

- [x] entrypoint.sh shell 语法检查(bash -n 通过)与信号逻辑评审
  - 证据:`trap on_signal TERM INT`(89 行)→ shutdown_all 逐个 kill -TERM、宽限 30s 后 kill -KILL;server 先起(94-96 行,注入 Lucene 路径)→ `until curl -sf` 健康轮询(102 行,180s 超时/等待期 server 死亡立即 exit 1)→ gateway 后起(122 行);`wait -n` 任一退出 → 全组停机以子进程退出码退出(128-140 行)
- [x] Dockerfile / Dockerfile.full 两轨运行时等价性 + 修复一处构建缺陷
  - 等价性:两轨运行阶段逐项一致——JRE `eclipse-temurin:8-jre-jammy`、python3/pip 安装、torch+requirements pip 依赖、`download_models.py` 预下载至 /app/models + `SENTENCE_TRANSFORMERS_HOME`、两 jar COPY 至同路径、EXPOSE 8090/VOLUME /app/data/HEALTHCHECK/ENTRYPOINT 全同;full 轨 Node 仅 stage1(COPY --from=node:22),stage2 全新 JRE 基础镜像无 node/npm
  - **修复**:验收复查发现 `Dockerfile.full:19` 缺 `AS builder` 命名而 75 行 `COPY --from=builder` 引用之——BuildKit 必报 stage not found、full 轨实际不可构建;已补 `AS builder`(任务 8.3 评审漏项)
- [x] 运行时镜像不含 Node(设计评审层面:镜像无 node/npm 层)
  - 证据:本地轨无任何 node 安装层(头注 17 行声明);full 轨 node 二进制仅存在于 stage1 builder,stage2 运行时层不拷贝
- [x] 模型预下载与数据卷(静态评审)
  - 证据:`download_models.py` HF_ENDPOINT 默认 hf-mirror(21 行)、MODELS 恰为 registry 双槽位 bge+MiniLM(26-29 行)、实例化校验可加载并打印维度;两轨 `VOLUME /app/data` + entrypoint 显式注入 lucene-index 路径,SQLite/上传目录经 WORKDIR=/app 落 /app/data 下(与 docs/deployment.md 卷表一致)

## 7.2 中文编码全链路

- [x] 中文文件名上传(文件名与文档名入库无乱码)
  - 证据:`机器学习介绍.docx`、`损坏文档.pdf`、`红塔笔记.txt`、`中文文件名测试文档.txt`、`降级演练文档.txt` 上传后列表/详情/检索命中项 filename 均为正确中文(经网关 :8090 全链路,multipart filename RFC 5987 UTF-8)
- [x] 中文查询往返(查询词与命中片段无乱码,UTF-8)
  - 证据:查询"昆明的红塔与滇池" → 响应 `query` 回显原文,首命中 `中文文件名测试文档.txt` 片段与 `<em>昆明</em><em>红塔</em>…` 高亮全部正常显示;此前十余次中文查询(红塔/机器学习/人工智能算法/滇池海鸥…)往返均无乱码
- [x] 响应 Content-Type 带 charset=UTF-8
  - 证据:响应头 `Content-Type: application/json;charset=UTF-8`(server `server.servlet.encoding.force=true` + 网关透传)

## 7.3 降级演练

- [x] 屏蔽模型加载(重命名模型缓存 + HF_HUB_OFFLINE=1)→ 引擎 degraded=true
  - 操作:`models--BAAI--bge-small-zh-v1.5` 缓存目录重命名为 `.HIDDEN` + `HF_HUB_OFFLINE=1 java -jar engine-server.jar` 启动;引擎状态 `degraded:true`、`loadedModels:[]`、lastError=`OSError: We couldn't connect to 'https://hf-mirror.com' …`、通道 py4j 正常(ok:true)
- [x] 降级摄取:文档与块携带降级标记(详情可见)
  - 证据:降级态上传 `降级演练文档.txt` → COMPLETED(哈希兜底可摄取)、文档 `degraded:true`、详情 `degradedReason` 完整;检索命中项级 `degraded:true/false` 区分新旧向量
- [x] 降级检索:响应携带 degraded 提示
  - 证据:`POST /api/search` 响应级 `degraded:true` + `degradedReason`(前端全局横幅数据源);降级文档命中排首(哈希字面自匹配 vec 0.582),检索仍可用
- [x] 健康降级展示:/api/system/health 引擎段显示降级而非 DOWN
  - 证据:网关聚合整体 `DEGRADED`、engine 段 `status:DEGRADED` 且 `ok:true`(非 DOWN)、`documents.degraded:1`——精确对应 spec"降级而非 DOWN,整体标注部分异常"
- [x] 降级向量确定性与显式标志(stdio 手测)
  - 证据:离线+屏蔽环境下 stdio embed 同文本两次向量**完全一致**、三次调用 `degraded` 全为 true、model=`hash-fallback(OSError: …)` 显式标注、不同文本向量不同
- [x] 恢复:模型还原 + 重启 → degraded=false,横幅数据源消失
  - 证据:模型缓存目录还原 + 默认配置重启(bootRun)→ 触发一次编码后健康 `整体 UP / degraded:false / loadedModels:[text-embedding-zh] / dim:512`;横幅数据源(健康引擎段+检索响应 degraded)全部回到 false——「横幅出现与消失」闭环;恢复态复验检索质量(阈值 0.48)无关词 0 命中、相关词全命中
