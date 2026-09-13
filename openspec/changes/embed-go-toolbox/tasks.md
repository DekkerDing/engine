# embed-go-toolbox — 实施任务清单

> 【节拍规约】每完成一个任务立即 git commit（消息前缀 `gotoolbox: N.M ...`）——网络中断后从最近 commit 续传，不攒批。每个任务的「验证」即勾选依据，实测记录写进本文件勾选项后随同提交。

## 1. Go 工程骨架与协议框架

- [x] 1.1 创建 `engine-server/golang/`（`go.mod` 钉 Go 版本下限、`cmd/toolbox/main.go`、`internal/protocol/`、`internal/router/`），文件头带【教学注释 · Go vs Java 对照】块；验证：`cd engine-server/golang && go build ./... && go vet ./...` 通过（实测 go1.27.1 BUILD_VET_OK；结构含 internal/engine 门面，对齐手工改写的 main.go 三步装配）
- [x] 1.2 实现 JSON 行协议循环（stdin 逐行读请求帧、stdout 写响应帧、日志走 stderr、未知 method 返回同 id error 且进程不退出）；验证：手工管道测试 `echo '{"id":1,"method":"sys.ping","params":{}}' | ./toolbox` 返回 `{"id":1,"result":...}`，再跟一帧未知方法验证不退出（实测：四帧管道全过——ping→pong(0.2.0)；未知方法→1001 且进程存活；坏帧→1002(id=0)；EOF 退出码 0）
- [x] 1.3 实现 `sys.shutdown`（优雅退出）与启动横幅（版本/平台/GOMAXPROCS 打 stderr）；验证：发送 shutdown 帧后进程退出码 0，stdin EOF 同效（实测：横幅 stderr 打印 version=0.2.0 platform=windows/amd64 gomaxprocs=8；shutdown→bye 帧退出码 0；EOF 退出码 0）

## 2. Java 侧通道与装配开关

- [x] 2.1 Go 进程启动器（平台解析 + classpath 解压 + 拉起）——初版落 `infrastructure/golang/`，随 D10 合并决策并入 `infrastructure/go/GoProcessLauncher`（定位链追加生产轨）；验证：单测 6 项全过（平台对表/windows-exe 命名/解压路径与复用/缺失资源修复指引/home 直指与目录两式/home 不存在快败），合并后随包迁移重跑
- [x] 2.2 Go 通道接口与 stdio 实现（常驻读线程 + `BlockingQueue` + 毒丸 + 串行化 + 超时）——初版落 `infrastructure/golang/`，随 D10 并入 `infrastructure/go/GoStdioChannel`（API 取手写版 `send(Map)→Response`，内核取已测硬化：`poll(timeout)` 免忙等/`EngineException`/迟到帧丢弃）；验证：FakeGoToolbox 假进程集成测试 5 项全过（握手往返/超时+迟到帧自愈/未知方法通道存活/优雅关闭后未运行/启动前快败），合并后随包迁移重跑
- [ ] 2.3 `GoToolboxProvider` 门面补齐与 `GoProtocol` 对齐：`sys.stats`、`hashing.generate` 参数（text/dim/normalize）、错误帧转 `EngineException.downstream`；验证：假通道单测覆盖 send→DTO 解析与错误翻译
- [ ] 2.4 两级装配门控：`engine.go.enabled`（默认 false）控通道三件套，`go-toolbox` Profile + enabled 控降级 provider；`application.yml` 加 `engine.go.*` 配置块（enabled/binary/command/call-timeout/startup-timeout）；验证：默认配置启动零 Go 进程零 go-runtime 目录，enabled=true 启动日志含握手成功，go-toolbox Profile 下 TEXT 模态无重复注册
- [ ] 2.5 毒丸与崩溃语义：杀死 Go 子进程后在途/后续调用快速失败、`@PreDestroy` 发 sys.shutdown；验证：集成测试杀进程后断言调用抛「引擎已退出」语义错误且不阻塞

## 3. 场景一：text.* 与 hashing.*（Go 注册 + Java 对拍）

- [ ] 3.1 Go 侧 `internal/engine` 注册 `text.chunk`/`text.tokenize`/`text.keywords`（接 `internal/text` 手写实现）；修正 `appendChunk` 为 rune 感知硬切（spec 中文正确性硬约束）；验证：`go test ./internal/text/...` 覆盖中文无标点长文硬切不乱码 + Go 单测全绿
- [ ] 3.2 Go 侧注册 `hashing.generate`（接 `internal/hashing`，参数 text/dim/normalize，result 含 vector/dim/degraded）与 `sys.stats`（engine/version/vector_count/tools）；验证：`go test ./...` 全绿 + 手工管道帧实测
- [ ] 3.3 Java 对拍测试：`text.chunk` vs Java 分块器（块数/块文本/重叠一致）、`hashing.generate` 确定性（两次调用逐元素相等、normalize 模长=1）；验证：假通道或直连引擎的对拍单测全绿
- [ ] 3.4 降级向量化集成：`go-toolbox` Profile 下摄取走 `GoToolboxProvider.embedBatch`（hashing.generate，degraded=true），检索链路正常完成；验证：集成测试摄取→检索闭环（哈希向量语义检索结果为确定性降级输出，不做相关性断言，只断链路不断线）

## 4. 场景二：vector.*（索引副本 + 并行扫描）

- [ ] 4.1 Go 侧 `internal/vector/` 索引副本：连续 float 矩阵 + 空间标签（source_type/model_key/dimension），`vector.insert`（按 docId 幂等替换）/`vector.delete`（逻辑删除）/`vector.similarity`；验证：Go 单测覆盖 insert 幂等/删除不命中/相似度对角线=1
- [ ] 4.2 Go 侧 `vector.search`：空间过滤 + goroutine 分片并行点积 + 局部 top-K 归并（定序：score 容差 1e-6 内按 document_id/chunk_index 字典序稳定）；验证：Go 单测随机数据与串行参考实现全量对拍（含平分定序）
- [ ] 4.3 Java 侧检索适配：经 `GoToolboxProvider.vectorSearch` 的检索路径 + 空间闸门判定（与 `InMemoryVectorIndex.spaceGateError` 语义对齐）；验证：单测构造「同模态模型切换」场景断言 400 与指引文案
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
