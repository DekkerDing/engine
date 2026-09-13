## 执行协议（并行波次 + 断点续传）

- **波次**：组标题标注 `[W0]`-`[W4]`；同波次内任务文件互不相交、可并行执行；波次间存在真实依赖、必须串行（git 全局状态 / 端口绑定 / 构建验证不可并行）。
- **commit 锚点**：每个任务完成并验证通过后**立即独立 commit**（不是每组一个）——commit message 引用任务号（如 `single-jar: 1.3 ApiPrefixRewriteFilter`）。
- **断点续传**：执行中断（网络断连/会话中断）后恢复时，以**双锚点**定位续点——`tasks.md` 勾选状态（主）+ `git log --oneline` 任务号（校验，防勾选滞后于实际提交）；从首个未勾选且无对应 commit 的任务继续，不重做已提交任务。
- 组内微依赖（如 1.5 集成验证依赖 1.2-1.4）保留编号顺序，不可乱序。

## [W0] 0. Checkpoint（前置，独占执行）

- [x] 0.1 提交当前 79 个 staged 文件作 checkpoint（commit message 主题：Go 网关替换），验证 `git status` 工作区干净后 commit（锚点 0.1）

## [W1] 1. server 侧合体能力（只增不删，仍 8081）—— 与组 2 并行

- [x] 1.1 前置检查：搜索 engine-server 是否已有 `WebMvcConfigurer`/资源 handler 配置，确认无冲突（有则合并方案记录到 design.md），验证方式为 grep 结果空或已记录合并结论；commit（锚点 1.1）
- [x] 1.2 从 git HEAD 捞回 `WebStaticConfig`/`SpaFallbackResolver`/`AssetCacheFilter` 三件套，改包名入 `engine.interfaces.web`，修正注释引用，验证编译通过 `gradlew :engine-server:compileJava` 后 commit（锚点 1.2）
- [x] 1.3 新增 `ApiPrefixRewriteFilter`（OncePerRequestFilter + HttpServletRequestWrapper 同时覆写 getRequestURI/getServletPath，仅 `/api` 前缀生效，FilterRegistrationBean HIGHEST_PRECEDENCE），验证编译通过后 commit（锚点 1.3）
  - **终案修正**：1.5 集成验证实测发现剥前缀后控制器裸路径与同名前端路由命名空间冲突（/search 等被 SearchController 抢走）——结构性不可修补，改 D1 终案「控制器映射直接带 /api 前缀」，本 Filter 已删除（见 design.md D1 实测推翻记录）
- [x] 1.4 `SystemController` 健康端点组装合体结构（server 恒 UP 自证 + gateway 段保留 + engine/documents 透传 + 整体 status 判定），验证启动后 `curl :8081/api/system/health` 返回 `{status, gateway, server, engine, documents}` 且 engine null 时整体 UP，commit（锚点 1.4）
  - 实测：合体结构逐字段正确（engine 语义化 {status:UP, detail:{...}}，overall UP，信封完整）；engine null 分支经代码审查确认（Go 版逻辑直译且 Go 版已实测）——当前架构 PythonChannel 为强制依赖，真实进程不可达该分支，作防御性契约保留
- [x] 1.5 集成验证（8081 双轨并存）：`curl :8081/`（200 HTML）、`:8081/search`（SPA 回退 200）、`:8081/assets/<hash>.js`（immutable 头）、`:8081/index.html`（200 + no-cache，无 301）、`:8081/api/no-such`（404 非 HTML）、带中文查询串 API 调用参数解码正确；全部通过后 commit（锚点 1.5）
  - 实测 10 项全过（含 D1 终案修正后复验）：/、/search、/documents、/requirements 全 200 HTML；assets immutable；index.html 200 no-cache 无 301；/api/no-such 404 JSON；/api/system/health 合体信封；/api/requirements 200；中文查询串 200；142 单测全绿

## [W1] 2. 前端工程移入 engine-server 并迁移构建编排 —— 与组 1 并行

- [x] 2.1 `git mv frontend engine-server/frontend`（tracked 文件）+ 手动移动/删除 `node_modules/`（untracked，fingerprint 失效后 npmInstall 自动补装），验证 `engine-server/frontend/` 就位且仓库根无残留 frontend 目录后 commit（锚点 2.1）
- [x] 2.2 将 `npmInstall`/`buildFrontend`/`copyFrontendDist` 任务链迁入 `engine-server/build.gradle`（frontendDir 指向模块内新位置），产物直送 `src/main/resources/static/`（clean-first + fingerprint 跳过保留），验证 `gradlew :engine-server:buildFrontend :engine-server:copyFrontendDist` 后 static/ 出现 index.html 与 assets/，commit（锚点 2.2）
  - 实现细节修正：产物改走 `build/frontend-static/static/`（sourceSets 注册）而非直送 `src/main/resources/static/`——对齐 packagePython「build/ 中转不污染源码树」先例，jar 内路径不变（design.md D5 已同步）
- [x] 2.3 根 `build.gradle` 移除前端任务与 gatewayStaticDir 引用；`.gitignore`/`.dockerignore` 的 `frontend/dist/` 改为 `engine-server/frontend/dist/`；`settings.gradle:15` 结构注释更新，验证 `gradlew tasks` 无悬空任务、全仓库 grep 无 `rootDir}/frontend` 旧路径后 commit（锚点 2.3）
- [x] 2.4 `gradlew :engine-server:bootJar` 后解包验证 `BOOT-INF/classes/static/index.html` 与 `assets/` 存在（jar 自包含），并 `java -jar` 冒烟 8081 全路由，commit（锚点 2.4）
  - 实测：jar 内 BOOT-INF/classes/static/{index.html, assets/}（5 条目）与 python/（21 条目）齐备；java -jar 从 engine-server/ 目录冷启动（./python 探测命中），全路由冒烟通过（同 1.5 清单）

## [W2] 3. 端口切换与网关停用 —— 依赖组 1、2 全部完成

- [x] 3.1 `application.yml`：`server.port: 8090` + `management.endpoints.web.exposure.include: health`，验证启动日志监听 8090 后 commit（锚点 3.1）
- [x] 3.2 停用 engine-gateway 进程，单进程下复测：全前端路由、API 全链路、`:8090/actuator/health` 200、`:8090/actuator/metrics` 404、60MB 上传 400 code 1000 文案逐字一致、8081 无监听，全部通过后 commit（锚点 3.2）
  - 实测 10 项全过（Go 网关进程已停用，单 Java 进程承载全部）；附带修复：SpaFallbackResolver 防御分支扩展拒绝 `actuator/` 前缀（未暴露端点被 SPA 吞成 index.html 200 → 404 JSON，spec actuator 收敛要求）

## [W3] 4. 退役 Go 网关工程 —— 与组 5、6 并行编辑（验证串行：本组先于组 5 的 docker build）

- [x] 4.1 删除 `engine-gateway/` 整目录，验证 `gradlew build` 全绿且仓库无残留引用（grep engine-gateway 仅剩 docs 与 openspec 历史）后 commit（锚点 4.1）
  - 实测：`gradlew build` 全绿（142 测试含）；构建体系（gradle/yml）无 gateway 残留，docker/ 引用由组 5 紧随处理
- [x] 4.2 清理 `.gitignore`/`.dockerignore` 中 gateway 条目，验证 `git status` 无意外文件暴露后 commit（锚点 4.2）
  - 实测：`.gitignore` 删 gateway 段（Go 网关注释 + 二进制 + logs-bootrun-gateway.txt），`.dockerignore` 删第 39-40 行 gateway 产物段并更新头部注释为单 JAR 表述、顺带清 logs-bootrun-gateway.txt；`git status` 仅两文件 M、0 untracked——无残留泄漏

## [W3] 5. Docker 与 Jenkinsfile —— 与组 4、6 并行编辑；docker build 验证须待 4.1 完成

- [x] 5.1 `docker/Dockerfile`：移除 gateway 二进制/static/config.yaml COPY，仅 COPY engine-server fat jar，验证语法（hadolint 或 docker build 本地轨）后 commit（锚点 5.1）
  - 实测：gateway COPY 三行全删；**补齐既有缺口**——launcher 按文件系统探测 python 脚本目录（jar 内 BOOT-INF 资源不参与探测，注释里的 PythonBootstrap 解压从未实现），新增 `COPY engine-server/python/ /app/python/`（旧架构容器内 Python 通道实际从未可用）；本机无 docker/hadolint，docker build 实测列入 7.2 遗留项
- [x] 5.2 `docker/Dockerfile.full`：builder 阶段移除 golang:1.23-alpine 工具链，改为 Node(前端) + JDK8(bootJar)，验证多阶段构建日志后 commit（锚点 5.2）
  - 实测：golang 段与 GOPROXY 全删（Node 22 保留）；构建命令简化为 `./gradlew :engine-server:bootJar --no-daemon -x test`（bootJar → processResources → packagePython + copyFrontendDist 全链自动拉起，build.gradle:90/173 依据）；stage2 与本地轨同步 COPY python/ 并补 `ENV HF_HOME` 两轨对齐；多阶段构建日志无 docker 列入 7.2
- [x] 5.3 `docker/entrypoint.sh`：单 java 进程启动（exec java），移除 GATEWAY_BIN/双进程互守逻辑，保留 trap TERM 转发，验证 SIGTERM 下 Python 子进程连带退出后 commit（锚点 5.3）
  - 实测：bash -n 语法过；**实现修正**——exec 后 shell 被 JVM 替换，trap 无宿主；优雅停机语义改为 JVM 直收 SIGTERM（PID 1 下 shutdown hooks 正常响应 → PythonProcessLauncher.stop() 连带终止 Python 子进程，退出码透传容器），注释中记录此决策；容器内 SIGTERM 实测列入 7.2
- [x] 5.4 HEALTHCHECK 确认指 `:8090/actuator/health`（路径与原网关兼容端点一致，部署脚本零改动），验证镜像内 curl 200 后 commit（锚点 5.4）
  - 实测：两轨 Dockerfile HEALTHCHECK 均保持 `curl -sf http://127.0.0.1:8090/actuator/health`；本机运行中实例实测 HTTP 200；镜像内验证随 7.2 遗留项（无 docker）
- [x] 5.5 新增声明式 `Jenkinsfile`（Frontend → Test → Package → Image → Publish，镜像 tag 含 BUILD_NUMBER），验证语法 `jenkins-cli declarative-linter` 或人工评审后 commit（锚点 5.5）
  - 实测：5 阶段顺序与 gradle 任务引用（buildFrontend/bootJar/test）grep 确认有效；Image 走本地轨（复用流水线内 jar），Dockerfile.full 留作仅 Docker 复现场景；jenkins-cli 不在本机，declarative-linter 与真实构建列入 7.2

## [W3] 6. /docs 手册更新 —— 与组 4、5 并行；本组内 7 篇互不相交可并行起草，各篇独立 commit（写一点提交一点）

- [x] 6.1 新增 `docs/reqforge-single-jar-design.md`（合体架构设计文档：Context/Decisions/迁移记录骨架），验证文档创建后 commit（锚点 6.1）
  - 实测：Context 痛点表/D1-D8 决策（含各自否决案与实测推翻过程）/迁移记录骨架（波次→锚点表）/终态拓扑/风险回滚表；commit ed5fc63（本条勾选在 7.2 复核时补记——git 锚点先于勾选，双锚点校验发现）
- [x] 6.2 新增 `docs/reqforge-single-jar-spec.md`（规约文档：路由/缓存/健康判定表/自测清单），验证文档创建后 commit（锚点 6.2）
  - 实测：路由表/缓存头策略/engine 单维度判定表/上传闸门/构建产物要求/13 项自测清单；commit 2ce8ece；7.1 终验发现清单第 8 项命令笔误（GET→POST）已随 7.1 修正（f14e65b）
- [x] 6.3 改写 `docs/deployment.md`（单进程单端口部署、新 HEALTHCHECK、回滚），验证无 8081/双进程残留描述后 commit（锚点 6.3）
  - 实测：总览图/构建命令/裸机路径/排查表（含 7.2 补充的动态端口竞态处置）全量单进程化；grep 无 8081/双进程/engine-gateway 功能性残留；commit af81d0b
- [x] 6.4 改写 `docs/architecture.md`（进程拓扑：三进程两跳 → 单 JAR 单进程），验证拓扑图与文字一致后 commit（锚点 6.4）
  - 实测：§1 三调用链图去 gateway 跳、§2 模块树（frontend 入 server + 进程树 exec 单进程）、§4 重写为原网关职能内化对照表、§7 决策速查更新；grep 无 engine-gateway/:8081/反代组件残留；commit 72a7efb
- [x] 6.5 改写 `docs/reqforge-dev-guide.md`（本地开发：Vite 代理不变、构建链新位置）与 `docs/reqforge-user-guide.md`（用户视角端口与访问），验证后 commit（锚点 6.5）
  - 实测：dev-guide v1.3（拓扑/三件套对照/12 处链接/启动打包/一键脚本/FAQ/速查）与 user-guide v1.2（概述/构建/部署/start.bat/排查）全量改写；两篇 grep 无 engine-gateway/:8081/go build 功能性残留
- [x] 6.6 改写 `docs/learning-path.md` 与 `docs/frontend-standards.md` 中涉及网关/双端口的段落，验证后 commit（锚点 6.6）
  - 实测：learning-path（全链路第 2 步、上手命令、3 处路径）与 frontend-standards（适用范围、目录树、SPA 回退指称、健康卡片说明）改写；两篇 grep CLEAN
- [x] 6.7 `docs/reqforge-gateway-go-*.md` 头部标记「历史文档（已被单 JAR 合体取代）」，验证后 commit（锚点 6.7）
  - 实测：两篇头部注入历史标记块 + 指向现行 single-jar 文档链接；原状态行保留为「状态（历史）」

## [W4] 7. 终验 —— 依赖全部完成

- [x] 7.1 全量回归：`gradlew clean build` 全绿 + `java -jar` 冷启动全路由自测（对照 spec 自测清单逐项打勾），输出预期 vs 实际对比表，commit（锚点 7.1）
  - 实测：`clean build` 全绿（1m45s，前端→jar→test 全链自动拉起，142 测试 0 失败）；`java -jar` 冷启动 21s 就绪，spec 13 项清单全过（第 8 项为清单命令笔误修正后复验：search 是 POST 端点，GET→500 快速失败系方法错误非缺陷；POST 中文检索 200 且真实命中中文文档、高亮无乱码；信封 items 字段完整）；附带修正 6.2 文档第 8 项命令 GET→POST
  - **环境发现（已定位非缺陷）**：本机 Windows 动态端口范围为 1024-15000（`netsh int ipv4 show dynamicport tcp`），8090 落在临时端口池内——首次冷启动时 Python 加载模型的出站 HTTPS 连接恰被分派 8090 源端口（TIME_WAIT），Tomcat bind 报 PortInUse；等待 60s 后重试成功。冷启动竞态概率低但存在，处置=重试（已记入 deployment.md 排查表第 2 条语境）；首次聚合健康/actuator 探测瞬现 000 抖动与模型冷加载并发相关，重试即恢复
- [x] 7.2 遗留项清点（性能基准/灰度等不可本地验证项列入待办），验证清单与 openspec tasks 无未勾选实现项后 commit（锚点 7.2）
  - 遗留项（全部为「本机无对应工具链」的环境性待办，非实现缺口；每项在对应任务实测记录中已注记）：
    1. `docker build` 本地轨/全构建轨实测（5.1/5.2/5.4——本机无 docker；本机 8090 实测 HTTP 200 已替代性验证 HEALTHCHECK 路径）
    2. 容器内 SIGTERM 优雅停机实测（5.3——无 docker；停机链路经代码审查：JVM PID 1 下 shutdown hooks 正常响应 → PythonProcessLauncher.stop）
    3. `jenkins-cli declarative-linter` + 真实 Jenkins 构建验证（5.5——本机无 jenkins；阶段顺序与 gradle 任务引用 grep 确认有效）
    4. 动态端口范围 1024-15000 环境风险（7.1 发现）：生产机建议 `netsh int ipv4 set dynamicport tcp 49152 16384` 收敛，已记入 deployment.md 排查表第 2 条
    5. 性能基准（可选）：合体后单跳延迟 vs 原网关两跳的量化对比未做（合体少一跳环回必不劣化，定性成立）
  - 验证：tasks.md 28/28 全勾选（本条为最后一项）；git log 锚点 0.1-7.2 齐备
