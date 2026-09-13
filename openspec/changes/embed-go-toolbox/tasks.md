# embed-go-toolbox — 实施任务清单

> 【节拍规约】每完成一个任务立即 git commit（消息前缀 `gotoolbox: N.M ...`）——网络中断后从最近 commit 续传，不攒批。每个任务的「验证」即勾选依据，实测记录写进本文件勾选项后随同提交。

## 1. Go 工程骨架与协议框架

- [x] 1.1 创建 `engine-server/golang/`（`go.mod` 钉 Go 版本下限、`cmd/toolbox/main.go`、`internal/protocol/`、`internal/router/`），文件头带【教学注释 · Go vs Java 对照】块；验证：`cd engine-server/golang && go build ./... && go vet ./...` 通过（实测 go1.27.1 BUILD_VET_OK；结构含 internal/engine 门面，对齐手工改写的 main.go 三步装配）
- [x] 1.2 实现 JSON 行协议循环（stdin 逐行读请求帧、stdout 写响应帧、日志走 stderr、未知 method 返回同 id error 且进程不退出）；验证：手工管道测试 `echo '{"id":1,"method":"sys.ping","params":{}}' | ./toolbox` 返回 `{"id":1,"result":...}`，再跟一帧未知方法验证不退出（实测：四帧管道全过——ping→pong(0.2.0)；未知方法→1001 且进程存活；坏帧→1002(id=0)；EOF 退出码 0）
- [x] 1.3 实现 `sys.shutdown`（优雅退出）与启动横幅（版本/平台/GOMAXPROCS 打 stderr）；验证：发送 shutdown 帧后进程退出码 0，stdin EOF 同效（实测：横幅 stderr 打印 version=0.2.0 platform=windows/amd64 gomaxprocs=8；shutdown→bye 帧退出码 0；EOF 退出码 0）

## 2. Java 侧通道与装配开关

- [ ] 2.1 新增 `infrastructure/golang/GoProcessLauncher`（按 `os.name/os.arch` 从 `classpath:/golang/<platform>/` 解压到 `./go-runtime/` 并拉起，镜像 `PythonProcessLauncher`）；验证：单测断言平台目录解析与解压产物路径
- [ ] 2.2 新增 `GoChannel` 接口与 `StdioGoChannel`（常驻读线程 + `BlockingQueue` + 毒丸 + `synchronized call()` + 超时，镜像 `StdioChannel`）；验证：集成测试跑通 call→响应往返与超时分支（假进程/短超时构造）
- [ ] 2.3 新增 `GoToolboxClient`（门面：方法名 + params → result，base64 float32 编解码工具）与 `engine.go.*` 配置项（enabled 默认 false / call-timeout / startup-timeout）；验证：单测覆盖 base64 向量编解码 round-trip
- [ ] 2.4 `@ConditionalOnProperty` 装配：enabled=true 时启动即拉起 + `sys.ping` 握手；false 时零装配（无 Go 进程）；验证：两种配置各启动一次，true 时日志含握手成功、false 时无 go-runtime 目录创建
- [ ] 2.5 毒丸与崩溃语义：杀死 Go 子进程后在途/后续 call 快速失败、`@PreDestroy` 发 shutdown；验证：集成测试杀进程后断言调用抛「引擎不可用」且不再阻塞

## 3. S2 文件流式哈希与上传去重

- [ ] 3.1 Go 侧 `internal/hashing/`：单文件流式 SHA-256（`io.Copy` 到 `sha256.New()`，内存有界）；验证：Go 单测对已知内容断言哈希值，对大文件断言内存平稳（或以 buffer 复用逻辑走查记录）
- [ ] 3.2 Go 侧批量哈希 worker pool（goroutine + channel 分发/汇聚，并发度 = GOMAXPROCS）与路径校验（规范化后必须位于传入根目录内，越界拒绝）；验证：Go 单测覆盖批量正确性、越界路径拒绝、混合成败批次
- [ ] 3.3 Java 侧 `ChecksumPort` 端口 + Java 兜底实现（`MessageDigest` 流式）+ Go 适配器（`hash.file`/`hash.batch`）；验证：单测双实现各算同文件哈希一致
- [ ] 3.4 上传链路接入：摄取前取哈希，SQLite 存内容哈希列（首次摄取回填），同哈希跳过解析/向量化直接关联既有内容；验证：集成测试——同内容二传显著快于首传且库内无重复内容，不同内容同名文件正常摄取
- [ ] 3.5 上传契约回归：大小限制/类型校验行为不变；验证：跑既有上传相关单测全绿 + 手工超限上传仍 400

## 4. S1 并行向量扫描与索引副本

- [ ] 4.1 Go 侧 `internal/vscan/` 副本存储：连续 `[]float32` 矩阵 + 侧表（entryId→行、docId→行集合、空间标签），`vscan.index.replace`/`vscan.index.remove`（逻辑删除标记）方法；验证：Go 单测覆盖 replace 幂等、remove 后查询不命中
- [ ] 4.2 Go 侧 `vscan.query`：按 (sourceType, modelKey, dimension) 过滤 + 分片并行点积 + 局部 top-K 归并，定序规则 (score 容差 1e-6, entryId 字典序)；验证：Go 单测随机数据与暴力参考实现全量对拍一致（含平分定序）
- [ ] 4.3 Java 侧 Go 适配器：实现与 `InMemoryVectorIndex.search` 同签名语义的检索路径 + 空间闸门判定（错误文案与 `spaceGateError` 逐字对齐）；验证：单测构造「同模态模型切换」场景断言 400 与指引文案
- [ ] 4.4 副本同步接线：`InMemoryVectorIndex` replace/remove 后同步发复制命令（先于摄取事务返回）；启动时全量灌入；验证：集成测试「摄取完成立刻检索可命中新块」+ 重启后副本行数 = 索引 size
- [ ] 4.5 双实现对拍：同查询向量/同 topK/同过滤，Java 扫描与 Go 扫描结果集合与顺序一致（1e-6 容差）；验证：对拍集成测试跑通并记录样本数据对拍输出

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
