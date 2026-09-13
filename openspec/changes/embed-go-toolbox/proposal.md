# Go 工具箱引擎嵌入：Java 当门面，Go 做计算

## Why

项目有两个真实的高负载场景至今由 Java 单线程承担：暴力余弦扫描（`InMemoryVectorIndex` 单线程 `synchronized` 全库扫，代码注释已自认「百万级再换」）与文件上传（全库无内容寻址去重，重复文件整份重摄取）。同时维护者希望**通过真实业务掌握 Go 语言**（学习目标是本变更的一等动机，性能是二等动机）。仓库已有「外部语言引擎随单 JAR 提供」的成熟模式（`engine-server/python/`），Go 仿此先例进入——Java 继续承接外部请求（单 JAR 合体架构不动摇），Go 作为嵌入引擎承接高 IO / 高计算的工具方法调用。

## What Changes

- **新增 `engine-server/golang/` 模块资产**（Go 工具箱引擎，仿 `python/` 先例：src 外、随模块一棵树、构建产物入 jar 的 `classpath:/golang/`）：一个二进制 `toolbox`，内部是「方法注册表 + stdio JSON 行协议」的通用派发框架，**stdlib-only 零三方依赖**（离线可构建，对不稳定网络友好）
- **新增 `infrastructure/golang/` Java 侧通道**（镜像 python 通道三件套）：`GoChannel` 接口 + `StdioGoChannel`（JSON 行协议、常驻读线程、毒丸、串行化 call——继承 `StdioChannel` 全部智慧）+ `GoProcessLauncher`（按 `os.name/os.arch` 从 classpath 解压对应平台二进制到 `./go-runtime/` 再拉起子进程）
- **场景 S2（高 IO · 起步）：文件流式 SHA-256 哈希**——上传链路接入内容寻址，同哈希文件跳过重摄取（worker pool 并行批量哈希）
- **场景 S1（高计算 · 深入）：并行余弦 top-K 扫描**——Go 侧维护向量索引副本（连续 `[]float32` 矩阵），启动全量灌入 + 摄取增量复制（replace/remove 命令），查询时 goroutine 分片扫描 + 局部 top-K 归并；Java 现役 `InMemoryVectorIndex` 保留为兜底实现
- **配置开关 `engine.go.enabled`（默认 false）**：业务代码只依赖 domain 端口，Spring 按开关装配 Go 适配器或 Java 实现——灵活性来自运行时可切、业务零感知；v1 不做运行时热切换（YAGNI，崩溃即毒丸报错 + 健康可见）
- **Gradle 构建链**：`buildGoToolbox`（`GOOS` 交叉编译 windows-amd64 + linux-amd64 双平台）→ `packageGolang`（Copy 入 jar，镜像 `packagePython` 编排）
- **Jenkinsfile**：节点要求 +Go 工具链（`GOPROXY` 国内镜像），Package 阶段自动拉起 Go 构建
- **健康聚合加性扩展**：`/api/system/health` 的 `data` 下新增可选键 `goToolbox`（前端忽略未知键，三卡片契约不破）
- **教学注释全覆盖**：Go 文件沿用仓库【教学注释】惯例，每个文件头加「Go vs Java 对照」块，锚定既有 Java 心智模型
- **/docs 四篇文档**：`reqforge-gotoolbox-design.md` + `reqforge-gotoolbox-spec.md`（设计/规约，对齐 single-jar 双篇先例）+ learning-path / dev-guide 增补 Go 章节

## Capabilities

### New Capabilities

- `go-toolbox-engine`: Go 嵌入引擎——stdio JSON 行通道协议与方法注册表派发、进程生命周期（解压/拉起/毒丸/超时）、双平台交叉编译与单 JAR 打包编排、`engine.go.enabled` 开关与 Java 兜底装配、两个工具方法契约（文件流式哈希、并行余弦 top-K 扫描含索引副本同步语义）、健康聚合 `goToolbox` 段

### Modified Capabilities

（无——`openspec/specs/` 尚无已归档能力规格，全部要求随新能力规格建立）

## Impact

- **代码**：`engine-server/golang/`（新增 Go 工程）；`engine-server/src/main/java/.../infrastructure/golang/`（新增通道三件套 + 适配器）；`InMemoryVectorIndex`/上传链路接入点小改（端口化）；`SystemController` 健康组装加 `goToolbox` 段；`engine-server/build.gradle` 加 Go 任务链
- **构建/CI**：Jenkinsfile 节点要求 JDK8 + Node **+ Go**（唯一真实成本：刚在 single-jar 变更里删掉的 Go 工具链请回来了——为学习目标付出的明确代价）；开发机构建 Go 需装 Go（`go build` 离线可跑，零三方依赖）
- **部署**：Docker/entrypoint **零改动**（linux-amd64 二进制已在 jar 内，仍单 JAR 单进程单容器）
- **契约**：对外 HTTP API 零变动；健康 JSON **加性扩展**（新增可选键 `goToolbox`，前向兼容）；默认 `engine.go.enabled=false` 时行为与现状逐字节一致
- **文档**：/docs 新增两篇 + 改写两篇（learning-path、dev-guide）
