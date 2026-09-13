# Go 工具箱引擎嵌入：Java 当门面，Go 做计算

## Why

项目有真实的高 CPU 场景至今由 Java 单线程承担（暴力余弦扫描、文本分块/分词/关键词），且 Python 模型不可用时向量化整条链路瘫痪（缺降级路径）。同时维护者希望**通过真实业务掌握 Go 语言**（学习目标是本变更的一等动机，性能是二等动机）。仓库已有「外部语言引擎随单 JAR 提供」的成熟模式（`engine-server/python/`），Go 仿此先例进入——Java 继续承接外部请求（单 JAR 合体架构不动摇），Go 作为嵌入引擎承接高 CPU 的工具方法调用。

## What Changes

- **新增 `engine-server/golang/` 模块资产**（Go 工具箱引擎，仿 `python/` 先例：src 外、随模块一棵树、构建产物入 jar 的 `classpath:/golang/`）：一个二进制 `toolbox`，内部是「方法注册表 + stdio JSON 行协议」的通用派发框架，**stdlib-only 零三方依赖**（离线可构建，对不稳定网络友好）
- **新增 `infrastructure/go/` Java 侧通道**：`GoChannel` 接口（类型化 `send(method, params) → Response`）+ `GoStdioChannel`（JSON 行协议、常驻读线程、毒丸、串行化、超时等待）+ `GoProcessLauncher`（二进制定位链：配置 → 环境变量 → 本地构建产物 → 开发态 `go run` → jar 内 classpath 按平台解压）
- **场景一（文本处理）：`text.chunk` / `text.tokenize` / `text.keywords`**——分块（含中文 rune 硬切正确性）、分词（bigram/exact 模式）、TF 关键词提取，与 Java/python 端语义对齐可对拍
- **场景二（向量检索）：`vector.search` / `vector.insert` / `vector.delete` / `vector.similarity`**——Go 侧维护向量索引副本（连续存储 + 空间过滤），摄取增量复制先于事务返回，goroutine 分片并行扫描，与 Java `InMemoryVectorIndex` 结果一致（1e-6 容差对拍）
- **场景三（降级向量化）：`hashing.generate` + `go-toolbox` Profile**——确定性哈希伪向量接管 TEXT 模态（python provider 让位），无模型环境摄取/检索不断线
- **文件哈希与上传内容寻址去重（原 S2 蓝图）保留为后续独立变更**，本变更不含
- **配置开关 `engine.go.enabled`（默认 false）**：两级开关——enabled 控制引擎在场，`go-toolbox` Profile 控制降级接管向量化；默认零变化、零进程；v1 不做运行时热切换（YAGNI，崩溃即毒丸报错 + 健康可见）
- **Gradle 构建链**：`buildGoToolbox`（`GOOS` 交叉编译 windows-amd64 + linux-amd64 双平台）→ `packageGolang`（Copy 入 jar，镜像 `packagePython` 编排）
- **Jenkinsfile**：节点要求 +Go 工具链（`GOPROXY` 国内镜像），Package 阶段自动拉起 Go 构建
- **健康聚合加性扩展**：`/api/system/health` 的 `data` 下新增可选键 `goToolbox`（前端忽略未知键，三卡片契约不破）
- **教学注释全覆盖**：Go 文件沿用仓库【教学注释】惯例，每个文件头加「Go vs Java 对照」块，锚定既有 Java 心智模型
- **/docs 四篇文档**：`reqforge-gotoolbox-design.md` + `reqforge-gotoolbox-spec.md`（设计/规约，对齐 single-jar 双篇先例）+ learning-path / dev-guide 增补 Go 章节

## Capabilities

### New Capabilities

- `go-toolbox-engine`: Go 嵌入引擎——stdio JSON 行通道协议与方法注册表派发、进程生命周期（二进制定位链/拉起/毒丸/超时）、双平台交叉编译与单 JAR 打包编排、`engine.go.enabled` 开关与 `go-toolbox` 降级 Profile 的两级装配、三组工具方法契约（文本处理、向量索引与检索含副本同步语义、哈希向量降级）、健康聚合 `goToolbox` 段

### Modified Capabilities

（无——`openspec/specs/` 尚无已归档能力规格，全部要求随新能力规格建立）

## Impact

- **代码**：`engine-server/golang/`（新增 Go 工程）；`engine-server/src/main/java/.../infrastructure/go/`（通道三件套 + 类型化协议 DTO + GoToolboxProvider 业务门面）；`InMemoryVectorIndex` 复制接线（副本同步源头）；`SystemController` 健康组装加 `goToolbox` 段；`engine-server/build.gradle` 加 Go 任务链；`application.yml` 加 `engine.go.*` 配置块
- **构建/CI**：Jenkinsfile 节点要求 JDK8 + Node **+ Go**（唯一真实成本：刚在 single-jar 变更里删掉的 Go 工具链请回来了——为学习目标付出的明确代价）；开发机构建 Go 需装 Go（`go build` 离线可跑，零三方依赖；日常开发可走 `go run` 开发轨免预编译）
- **部署**：Docker/entrypoint **零改动**（linux-amd64 二进制已在 jar 内，仍单 JAR 单进程单容器）
- **契约**：对外 HTTP API 零变动；健康 JSON **加性扩展**（新增可选键 `goToolbox`，前向兼容）；默认 `engine.go.enabled=false` 时行为与现状逐字节一致
- **文档**：/docs 新增两篇 + 改写两篇（learning-path、dev-guide）
