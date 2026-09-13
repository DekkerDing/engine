# embed-go-toolbox — 实施任务清单

> 【节拍规约】每完成一个任务立即 git commit（消息前缀 `gotoolbox: N.M ...`）——网络中断后从最近 commit 续传，不攒批。每个任务的「验证」即勾选依据，实测记录写进本文件勾选项后随同提交。

## 1. Go 工程骨架与协议框架

- [x] 1.1 创建 `engine-server/golang/`（`go.mod` 钉 Go 版本下限、`cmd/toolbox/main.go`、`internal/protocol/`、`internal/router/`），文件头带【教学注释 · Go vs Java 对照】块；验证：`cd engine-server/golang && go build ./... && go vet ./...` 通过（实测 go1.27.1 BUILD_VET_OK；结构含 internal/engine 门面，对齐手工改写的 main.go 三步装配）
- [x] 1.2 实现 JSON 行协议循环（stdin 逐行读请求帧、stdout 写响应帧、日志走 stderr、未知 method 返回同 id error 且进程不退出）；验证：手工管道测试 `echo '{"id":1,"method":"sys.ping","params":{}}' | ./toolbox` 返回 `{"id":1,"result":...}`，再跟一帧未知方法验证不退出（实测：四帧管道全过——ping→pong(0.2.0)；未知方法→1001 且进程存活；坏帧→1002(id=0)；EOF 退出码 0）
- [x] 1.3 实现 `sys.shutdown`（优雅退出）与启动横幅（版本/平台/GOMAXPROCS 打 stderr）；验证：发送 shutdown 帧后进程退出码 0，stdin EOF 同效（实测：横幅 stderr 打印 version=0.2.0 platform=windows/amd64 gomaxprocs=8；shutdown→bye 帧退出码 0；EOF 退出码 0）

## 2. Java 侧通道与装配开关

- [x] 2.1 Go 进程启动器（平台解析 + classpath 解压 + 拉起）——初版落 `infrastructure/golang/`，随 D10 合并决策并入 `infrastructure/go/GoProcessLauncher`（定位链追加生产轨）；验证：单测 6 项全过（平台对表/windows-exe 命名/解压路径与复用/缺失资源修复指引/home 直指与目录两式/home 不存在快败）；0.5b 合并迁移后重跑 7 项全过（目录两式随定位链简化为"直指文件"单式，另加 `@ConditionalOnProperty` 门控注解）
- [x] 2.2 Go 通道接口与 stdio 实现（常驻读线程 + `BlockingQueue` + 毒丸 + 串行化 + 超时）——初版落 `infrastructure/golang/`，随 D10 并入 `infrastructure/go/GoStdioChannel`（API 取手写版 `send(Map)→Response`，内核取已测硬化：`poll(timeout)` 免忙等/`EngineException`/迟到帧丢弃）；验证：FakeGoToolbox 假进程集成测试 5 项全过（握手往返/超时+迟到帧自愈/未知方法通道存活/优雅关闭后未运行/启动前快败）；0.5b 合并迁移后全量 154 项重跑全绿（迟到帧时序裕度修正 2s→1.2s，抗全量并发抖动）
- [x] 2.3 `GoToolboxProvider` 门面补齐与 `GoProtocol` 对齐：`sys.stats`、`hashing.generate` 参数（text/dim/normalize）、错误帧转 `EngineException.downstream`；验证：假通道单测 10 项全过（tokenize/keywords/chunk/vectorSearch 参数传递与 DTO 解析、generateHash normalize 三参、stats、embedBatch 512 维降级组装、错误帧转 503 EngineException、空 result 容错、insert/delete 计数）（实测：GoToolboxProviderTest tests=10 failures=0；错误翻译落在通道层 call()，假通道按同款语义回放验证）
- [x] 2.4 两级装配门控：`engine.go.enabled`（默认 false）控通道三件套，`go-toolbox` Profile + enabled 控降级 provider；`application.yml` 加 `engine.go.*` 配置块（enabled/binary/command/stdio-timeout-seconds）；验证：`ApplicationContextRunner` 四态单测全过（默认配置零 Go Bean——启动炸弹已拆除的直接证据；enabled+Profile 通道拉起 go run 真实握手 sys.ping 成功且降级 provider 在场；enabled 无 Profile 仅通道不接管；Profile 无 enabled provider 不装配）（实测：GoAssemblyGatingTest tests=4 failures=0；"TEXT 无重复注册"的全应用闭环归 3.4；修双构造器歧义——生产 ctor 补 @Autowired）
- [x] 2.5 毒丸与崩溃语义：杀死 Go 子进程后在途/后续调用快速失败、`@PreDestroy` 发 sys.shutdown；验证：StdioGoChannelCrashTest 3 项全过（外杀后首次/后续调用均抛「退出/写入失败」语义 EngineException 且实测秒级返回——30s 长超时配置下不靠超时兜底；die.now 自杀分支同款快败；崩溃态 close() 静默成功）（实测：tests=3 failures=0）

## 3. 场景一：text.* 与 hashing.*（Go 注册 + Java 对拍）

- [x] 3.1 Go 侧 `internal/engine` 注册 `text.chunk`/`text.tokenize`/`text.keywords`（接 `internal/text` 手写实现）；修正 `appendChunk` 为 rune 感知硬切（spec 中文正确性硬约束）；验证：`go test ./internal/text/...` 7 项全绿（含 1000 汉字无标点硬切三块全合法 UTF-8 且拼回原文 / 重叠保链精确断言 / search-exact-spec 场景 / TF 频次区分度回归防线）+ 真实二进制管道三方法实测（chunk 契约小写字段、tokenize bigram 与 spec 场景逐 token 一致、keywords 频次排序恢复——顺带修掉 bigram 先去重导致 TF 恒 1 的语义 bug；句子累计判长从 byte 口径改 rune 口径对齐 Java String.length()，否则中文块比 Java 小三分之二）
- [x] 3.2 Go 侧注册 `hashing.generate`（接 `internal/hashing`，参数 text/dim/normalize，result 含 vector/dim/degraded）与 `sys.stats`（engine/version/vector_count/tools）；验证：`go test ./...` 全绿（hashing 6 项：确定性/维度回落与奇数维/值域 [-1,1)/灵敏性/L2=1/归一保方向）+ 手工管道帧实测（hashing.generate 四维归一向量 degraded:true；sys.stats 六方法字典序含自身、vector_count=0 待 4.x 回填）——Router 补 `Methods()`（map 遍历随机须排序）
- [x] 3.3 Java 对拍测试：`text.chunk` vs Java 分块器（块数/块文本/重叠一致）、`hashing.generate` 确定性（两次调用逐元素相等、normalize 模长=1）；验证：GoTextParityTest 7 项全过——真实引擎（go run 轨）完整协议链对拍，TextChunker(400,1) vs text.chunk 四语料逐块一致（60 句中文标点 / 1000 汉字无标点双端硬切 / 中英混排 / 短文本），哈希确定性（128 维两调用逐元素相等）、L2 模长=1（1e-9）、embedBatch 与单条 generate 同参逐元素一致（1e-7 float 落地容差）；无 Go 环境 assume-skip
- [x] 3.4 降级向量化集成：`go-toolbox` Profile 下摄取走 `GoToolboxProvider.embedBatch`（hashing.generate，degraded=true），检索链路正常完成；验证：集成测试摄取→检索闭环（哈希向量语义检索结果为确定性降级输出，不做相关性断言，只断链路不断线）（实测：GoDegradedIngestIT tests=1 failures=0——仓库第一个 @SpringBootTest，@ActiveProfiles("go-toolbox")+@DynamicPropertySource 临时库/索引，30 段中文摄取 COMPLETED、向量 modelKey=go-hash-degraded dim=512 degraded=true、检索"红塔 公园"命中新文档、删除后不再命中；生产装配侧 python 四适配器全数让位——ChannelEmbeddingProvider/ClipEmbeddingProvider/ChannelAnnotationProvider/ChannelRerankProvider 加 @Profile("!go-toolbox")（rerank 为装配链第 4 个炸点，一次清完），Document/Image 应用服务的 EngineStatusQuery/VisionStatusQuery/AnnotationProvider 依赖 Optional 化（缺席语义：标注步记失败标记不阻塞、详情页降级原因走静态文案、重排直通）；全量回归 179/179 绿）

## 4. 场景二：vector.*（索引副本 + 并行扫描）

- [x] 4.1 Go 侧 `internal/vector/` 索引副本：连续 float 矩阵 + 空间标签（source_type/model_key/dimension），`vector.insert`（按 docId 幂等替换）/`vector.delete`（逻辑删除）/`vector.similarity`；验证：Go 单测覆盖 insert 幂等/删除不命中/相似度对角线=1（实测：用户手写 vector 包为正典——`map[docId][]indexedEntry` 分组 + 预归一化 scoringVec（点积=余弦）替代"连续扁平矩阵"、"map delete 物理删除"替代"逻辑删除墓碑"（连续数组原地压缩才有 tombstone 必要，分组结构下语义等价）；补 index_test.go 8 项全绿（幂等替换不双计/删除不命中含幂等删除/对角线=1 含索引路径/空间双闸门/维度不匹配跳过/零向量 score=0 非 NaN/Top-K 截断降序串行基线/GetAllEntries 快照）；顺手修 GetAllEntries 浅拷贝陷阱——Entry 值拷贝但 Vector 切片共享底层数组，兑现"返回副本"注释合同须深拷贝；engine 注册 vector.insert/delete/similarity（条目复用 vector.Entry tag、handler 边界维校验把库 panic 翻译成 1002 帧）；sys.stats vector_count 回填真实行数；管道实测 insert{inserted:2,total:2}→similarity 对角线=1→维不一致 1002→stats vector_count=2→delete total=0→stats vector_count=0）
- [x] 4.2 Go 侧 `vector.search`：空间过滤 + goroutine 分片并行点积 + 局部 top-K 归并（定序：score 容差 1e-6 内按 document_id/chunk_index 字典序稳定）；验证：Go 单测随机数据与串行参考实现全量对拍（含平分定序）（实测：search.go 三阶段——持锁只盖快照采集、goroutine 分片计算无锁、WaitGroup 归并；局部 top-K 归并正确性论证（全局第 k 名在任一分片内名次不高于全局）注释在案；定序用分数量化（math.Round(score*1e6) 整数格点全序）而非容差比较——|a-b|<ε 不满足传递性，喂给 sort 是未定义行为，量化是浮点工程标准手法；串行 Search 与并行共用 sortHits（对拍前提：定序合同逐字相同）；search_test.go 6 项——固定种子随机 120 文档×4 块×32 维 topK 四档全量对拍（位置逐一 doc_id/chunk_idx 恒等 score<1e-12）、workers=1/2/8/17/自动五档一致（质数分片验非均匀尾片）、乱序插入同分九条字典序+块序、容差平分构造性证据（分数差 5e-7<1e-6 时字典序压倒分数序）、差 2e-6>容差时严格分数序、四类边界空结果；engine 注册 vector.search（命中只回三元组 doc_id/chunk_idx/score——计算副本不回表，完整条目 Java 主索引持有）；管道实测维度闸门（2 维条目对 3 维查询跳过、对 2 维查询命中）+ topK 默认/截断 + 空过滤全扫）
- [x] 4.3 Java 侧检索适配：经 `GoToolboxProvider.vectorSearch` 的检索路径 + 空间闸门判定（与 `InMemoryVectorIndex.spaceGateError` 语义对齐）；验证：单测构造「同模态模型切换」场景断言 400 与指引文案（实测：协议层新组件 `GoVectorReplica`（只认第一级开关 engine.go.enabled，与 go-toolbox Profile 解耦——场景二/三是独立场景），vector.* 四方法从 GoToolboxProvider 迁入并修三处契约断裂——query 参数键（原误发 vector）/removed 键（Go 端 vectorDelete 响应从 deleted:字符串 改 removed:条数，Index.Remove 返回删除数）/SimilarityResult.similarity→score；HitItem 去掉 source_type/model_key 冗余字段（回表语义钉死：Go 只回三元组）；路由落位 InMemoryVectorIndex 内部（回表/闸门/兜底天然同源）：Go 轨锁外调用（通道 IO 数十秒不得持锁堵死摄取写路径——类注释锁纪律）、命中回主索引组装 VectorHit、空结果/回表 miss/通道异常三态降级本地扫描——闸门判定单源留在本地轨，文案与关闭态逐字一致即 D5 的实现方式；SqliteVectorStore 生产构造注入 Optional<GoVectorReplica>（单参构造 Optional.empty——裸 null 会在 map() NPE，全量回归抓住后修正）；测试：GoVectorReplicaTest 5 项（组装契约键名/removed/score/空过滤键缺席）+ GoVectorIndexRoutingTest 5 项（同模态模型切换 Go 空结果经本地轨闸门 400 含"不可比/重新摄取/text-embedding-zh"三段文案——验收主项；命中回表组装；通道异常兜底与关闭态逐分一致；幽灵命中降级；真空库不触发闸门）；GoToolboxProviderTest 删迁走的 3 项留 8 项；全量 187/187 绿）
- [ ] 4.4 副本同步接线：`InMemoryVectorIndex` replace/remove 后经通道发 `vector.insert`/`vector.delete`（复制先于摄取返回）；enabled 启动后全量灌入；验证：集成测试「摄取完成立刻经 vector.search 可命中新块」+ 重启后 vector_count = 索引 size
- [ ] 4.5 双实现对拍：同查询/topK/过滤，Java `InMemoryVectorIndex` 与 `vector.search` 命中集合与顺序一致（1e-6 容差）；验证：对拍集成测试跑通并记录样本对拍输出

## 5. 构建编排与 CI

- [ ] 5.1 `build.gradle` 加 `buildGoToolbox`（GOOS=windows/linux 双平台交叉编译、源码指纹 onlyIf、本机无 Go 且开关关闭时允许跳过）与 `packageGolang`（Copy → `build/golang-pack/golang/<platform>/`）并注册 sourceSets；验证：`gradlew :engine-server:bootJar` 后 jar 内 `classpath:/golang/` 存在双平台二进制（unzip -l 断言）
- [ ] 5.2 `.gitignore` 加 `go-runtime/` 与 Go 构建产物；验证：构建后 `git status` 不出现新垃圾文件
- [ ] 5.3 Jenkinsfile：节点要求注释 +Go、`GOPROXY=https://goproxy.cn`、Package 阶段确认含 Go 构建；验证：Jenkinsfile 语法走查 + 本地按流水线同序手工执行一遍全绿（记录输出）
- [ ] 5.4 离线构建验证：断网状态（或 GOFLAGS=-mod=mod + GOPROXY=off 构造）跑 `buildGoToolbox` 成功；验证：命令输出留痕，证明零三方依赖

## 6. 健康聚合扩展

- [ ] 6.1 `SystemController` 的 health 组装加 `goToolbox` 段（enabled=false 时 status=N/A 或缺席语义、true 时 UP/DOWN + 关键指标），整体 status 判定规则保持；验证：curl 双态对比 JSON 留痕，既有键结构不变，前端三卡片正常渲染（浏览器实测）

## 7. /docs 文档（增量提交：一篇一 commit）

- [ ] 7.1 新增 `docs/reqforge-gotoolbox-design.md`（含架构图、D1-D9 决策、「Go 速成路线图」按代码走读顺序）；验证：文档内引用的文件路径/行号与代码一致
- [ ] 7.2 新增 `docs/reqforge-gotoolbox-spec.md`（协议帧、方法注册表契约、副本同步语义、开关矩阵）；验证：与 spec delta 逐条对照无遗漏
- [ ] 7.3 改写 `docs/learning-path.md` 增补 Go 章节与走读路线；验证：章节引用的代码锚点存在
- [ ] 7.4 改写 `docs/reqforge-dev-guide.md`（本地构建需 Go、开关说明、常见排查：防病毒/端口/离线）；验证：按文档从零构建一遍可复现

## 8. 全量回归与收尾

- [ ] 8.1 clean build 全绿：`gradlew clean :engine-server:test :engine-server:bootJar`（含 Go 构建）；验证：输出留痕零失败
- [ ] 8.2 开关双态冷启动清单：false 态（默认，行为与基线一致——上传/检索/健康对拍）与 true 态（Go 拉起、握手、秒传、对拍、健康段）各过一遍 13 项式清单；验证：逐项实测记录
- [ ] 8.3 Docker 轨验证：`docker build -f docker/Dockerfile` 镜像内启动，linux 二进制被正确解压执行；验证：容器内 curl :8090 健康含 goToolbox UP（true 态）
- [ ] 8.4 遗留项清点：Open Questions 两项（健康指标字段、Go 版本下限）落定答案回写 design.md；验证：无未勾任务、无未回写项
