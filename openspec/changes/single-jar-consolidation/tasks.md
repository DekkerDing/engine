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
- [x] 1.4 `SystemController` 健康端点组装合体结构（server 恒 UP 自证 + gateway 段保留 + engine/documents 透传 + 整体 status 判定），验证启动后 `curl :8081/api/system/health` 返回 `{status, gateway, server, engine, documents}` 且 engine null 时整体 UP，commit（锚点 1.4）
  - 实测：合体结构逐字段正确（engine 语义化 {status:UP, detail:{...}}，overall UP，信封完整）；engine null 分支经代码审查确认（Go 版逻辑直译且 Go 版已实测）——当前架构 PythonChannel 为强制依赖，真实进程不可达该分支，作防御性契约保留
- [ ] 1.5 集成验证（8081 双轨并存）：`curl :8081/`（200 HTML）、`:8081/search`（SPA 回退 200）、`:8081/assets/<hash>.js`（immutable 头）、`:8081/index.html`（200 + no-cache，无 301）、`:8081/api/no-such`（404 非 HTML）、带中文查询串 API 调用参数解码正确；全部通过后 commit（锚点 1.5）

## [W1] 2. 前端工程移入 engine-server 并迁移构建编排 —— 与组 1 并行

- [x] 2.1 `git mv frontend engine-server/frontend`（tracked 文件）+ 手动移动/删除 `node_modules/`（untracked，fingerprint 失效后 npmInstall 自动补装），验证 `engine-server/frontend/` 就位且仓库根无残留 frontend 目录后 commit（锚点 2.1）
- [x] 2.2 将 `npmInstall`/`buildFrontend`/`copyFrontendDist` 任务链迁入 `engine-server/build.gradle`（frontendDir 指向模块内新位置），产物直送 `src/main/resources/static/`（clean-first + fingerprint 跳过保留），验证 `gradlew :engine-server:buildFrontend :engine-server:copyFrontendDist` 后 static/ 出现 index.html 与 assets/，commit（锚点 2.2）
  - 实现细节修正：产物改走 `build/frontend-static/static/`（sourceSets 注册）而非直送 `src/main/resources/static/`——对齐 packagePython「build/ 中转不污染源码树」先例，jar 内路径不变（design.md D5 已同步）
- [ ] 2.3 根 `build.gradle` 移除前端任务与 gatewayStaticDir 引用；`.gitignore`/`.dockerignore` 的 `frontend/dist/` 改为 `engine-server/frontend/dist/`；`settings.gradle:15` 结构注释更新，验证 `gradlew tasks` 无悬空任务、全仓库 grep 无 `rootDir}/frontend` 旧路径后 commit（锚点 2.3）
- [ ] 2.4 `gradlew :engine-server:bootJar` 后解包验证 `BOOT-INF/classes/static/index.html` 与 `assets/` 存在（jar 自包含），并 `java -jar` 冒烟 8081 全路由，commit（锚点 2.4）

## [W2] 3. 端口切换与网关停用 —— 依赖组 1、2 全部完成

- [ ] 3.1 `application.yml`：`server.port: 8090` + `management.endpoints.web.exposure.include: health`，验证启动日志监听 8090 后 commit（锚点 3.1）
- [ ] 3.2 停用 engine-gateway 进程，单进程下复测：全前端路由、API 全链路、`:8090/actuator/health` 200、`:8090/actuator/metrics` 404、60MB 上传 400 code 1000 文案逐字一致、8081 无监听，全部通过后 commit（锚点 3.2）

## [W3] 4. 退役 Go 网关工程 —— 与组 5、6 并行编辑（验证串行：本组先于组 5 的 docker build）

- [ ] 4.1 删除 `engine-gateway/` 整目录，验证 `gradlew build` 全绿且仓库无残留引用（grep engine-gateway 仅剩 docs 与 openspec 历史）后 commit（锚点 4.1）
- [ ] 4.2 清理 `.gitignore`/`.dockerignore` 中 gateway 条目，验证 `git status` 无意外文件暴露后 commit（锚点 4.2）

## [W3] 5. Docker 与 Jenkinsfile —— 与组 4、6 并行编辑；docker build 验证须待 4.1 完成

- [ ] 5.1 `docker/Dockerfile`：移除 gateway 二进制/static/config.yaml COPY，仅 COPY engine-server fat jar，验证语法（hadolint 或 docker build 本地轨）后 commit（锚点 5.1）
- [ ] 5.2 `docker/Dockerfile.full`：builder 阶段移除 golang:1.23-alpine 工具链，改为 Node(前端) + JDK8(bootJar)，验证多阶段构建日志后 commit（锚点 5.2）
- [ ] 5.3 `docker/entrypoint.sh`：单 java 进程启动（exec java），移除 GATEWAY_BIN/双进程互守逻辑，保留 trap TERM 转发，验证 SIGTERM 下 Python 子进程连带退出后 commit（锚点 5.3）
- [ ] 5.4 HEALTHCHECK 确认指 `:8090/actuator/health`（路径与原网关兼容端点一致，部署脚本零改动），验证镜像内 curl 200 后 commit（锚点 5.4）
- [ ] 5.5 新增声明式 `Jenkinsfile`（Frontend → Test → Package → Image → Publish，镜像 tag 含 BUILD_NUMBER），验证语法 `jenkins-cli declarative-linter` 或人工评审后 commit（锚点 5.5）

## [W3] 6. /docs 手册更新 —— 与组 4、5 并行；本组内 7 篇互不相交可并行起草，各篇独立 commit（写一点提交一点）

- [ ] 6.1 新增 `docs/reqforge-single-jar-design.md`（合体架构设计文档：Context/Decisions/迁移记录骨架），验证文档创建后 commit（锚点 6.1）
- [ ] 6.2 新增 `docs/reqforge-single-jar-spec.md`（规约文档：路由/缓存/健康判定表/自测清单），验证文档创建后 commit（锚点 6.2）
- [ ] 6.3 改写 `docs/deployment.md`（单进程单端口部署、新 HEALTHCHECK、回滚），验证无 8081/双进程残留描述后 commit（锚点 6.3）
- [ ] 6.4 改写 `docs/architecture.md`（进程拓扑：三进程两跳 → 单 JAR 单进程），验证拓扑图与文字一致后 commit（锚点 6.4）
- [ ] 6.5 改写 `docs/reqforge-dev-guide.md`（本地开发：Vite 代理不变、构建链新位置）与 `docs/reqforge-user-guide.md`（用户视角端口与访问），验证后 commit（锚点 6.5）
- [ ] 6.6 改写 `docs/learning-path.md` 与 `docs/frontend-standards.md` 中涉及网关/双端口的段落，验证后 commit（锚点 6.6）
- [ ] 6.7 `docs/reqforge-gateway-go-*.md` 头部标记「历史文档（已被单 JAR 合体取代）」，验证后 commit（锚点 6.7）

## [W4] 7. 终验 —— 依赖全部完成

- [ ] 7.1 全量回归：`gradlew clean build` 全绿 + `java -jar` 冷启动全路由自测（对照 spec 自测清单逐项打勾），输出预期 vs 实际对比表，commit（锚点 7.1）
- [ ] 7.2 遗留项清点（性能基准/灰度等不可本地验证项列入待办），验证清单与 openspec tasks 无未勾选实现项后 commit（锚点 7.2）
