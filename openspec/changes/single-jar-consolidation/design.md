## Context

当前形态（见 proposal.md - Why）：单容器三进程两跳——`engine-gateway`(Go, :8090) 反代 `engine-server`(Java, :8081)，前端 dist 由网关 `static/` 承载。

实现本设计的全部现成材料：

- **三件套 Java 源码在 git HEAD 暂存区可原样捞回**（当日上午 Go 替换时删除，尚未提交）：`WebStaticConfig`（三段 resource handler：`/assets/**`、`/index.html`+`/favicon.ico` noCache、`/**` 兜底挂 `SpaFallbackResolver`）、`SpaFallbackResolver`（api/ 前缀拒绝→404；目录型路径→入口页；文件存在→原样；否则→index.html 200）、`AssetCacheFilter`（手写响应头 `public, max-age=31536000, immutable`——Spring 5.3 无 `CacheControl.immutable()`，`FilterRegistrationBean` 限 `/assets/*`、REQUEST+FORWARD、HIGHEST_PRECEDENCE）。三者均为 Boot 2.6 同版本 API，捞回零适配成本，仅改包名。
- **engine-server 现成件**：`SystemController#/system/health` → `SystemQueryService.aggregateHealth()` 返回 `{application,status,engine,documents}`；multipart 闸门（50MB/55MB + 超限信封文案）由 server 自身执行，与网关预检文案逐字一致（已实测）；Python 子进程生命周期由 server 管理（shutdown hook 连带终止）。
- **前端**：`frontend/`（仓库根），生产 baseURL `/api` 相对路径；`client.ts` 无硬编码主机。
- **锁定约束**：Spring Boot 2.6.14 + JDK 8 + Gradle 7.6.4；端口决策 8090（对外契约零变动）；工作区 79 个 staged 文件须先提交 checkpoint。

## Goals / Non-Goals

**Goals:**

- 一个 fat JAR、一个 Java 进程、一个 8090 端口承载全部入口能力；对外 HTTP 契约（路由、健康 JSON、缓存头、上传文案）逐字节不变
- 迁移路径每步独立可验证、可 commit、可断点续传（用户网络不稳定约束）
- Jenkinsfile + Gradle 一条线产出 jar 与镜像，CI 工具链降为 JDK8 + Node

**Non-Goals:**

- 不动业务控制器与前端代码（零改动收益自 Filter 方案）
- 不做网关层的限流/鉴权等新能力（原网关也没有）
- 不处理分布式/多实例部署（单机单容器形态）
- 不迁移 `docs/reqforge-gateway-go-*.md` 之外的历史文档结构（仅标记历史 + 新增两篇）

## Decisions

### D1: `/api` 前缀重写用 OncePerRequestFilter（否决三个替代方案）

`HIGHEST_PRECEDENCE` 的 `OncePerRequestFilter` 匹配 `requestURI` 以 `/api` 开头时，用 `HttpServletRequestWrapper` 包装请求继续链——控制器、`@RequestMapping`、actuator、静态 handler 全部零改动。

**Wrapper 必须同时覆写 `getRequestURI()` 与 `getServletPath()`**（Spring MVC 映射依据二者；只覆写其一会出现部分 mapping 失效）。`getQueryString()` 不覆写（默认透传，query 原样）；不解码不重编码路径（`getRequestURI()` 返回原始编码串，剥前缀是纯字符串操作，百分号转义零漂移）。

否决的替代方案：

| 方案 | 否决原因 |
|------|----------|
| `server.servlet.context-path: /api` | 拖累静态资源与 actuator（它们也被迫挂 `/api` 下），与「页面与 API 同端口同源」冲突 |
| 改前端 `baseURL` 三端 | 波及浏览器/APP/脚本全部调用方，违反「对外契约零变动」目标 |
| server 内嵌自代理（RestTemplate/OkHttp 转发到自身 8081） | 同进程内多一跳环回 HTTP，纯性能税，且保留双端口复杂度 |

精确 `/api`（无尾部分）剥后为空串→DispatcherServlet 404，与原网关 `StripPrefix` 行为等价（前端不会发起裸 `/api`）。

### D2: 静态三件套从 git HEAD 捞回，改包名入 `engine.interfaces.web`

`io.github.dekkerding.gateway.statics` → `io.github.dekkerding.engine.interfaces.web`。资源位置 `classpath:/static/` 不变（前端产物从网关 jar 迁到 server jar 的同一路径）。原实现的教学注释保留，仅修正引用（spec 引用、产物来源注释）。

前置检查：确认 engine-server 无既有 `WebMvcConfigurer` 资源 handler 配置（三段 handler 依赖 mapping 优先级排序，重复注册 `/**` 会引入不确定性）。

### D3: 健康聚合进程内化——直调 Service，控制器外包一层

`SystemController` 的 `/system/health`（经 Filter 后即 `/api/system/health`）改为组装合体结构：

```
server 段   = {application:"engine-server", status:"UP", lastError:null, lastSuccessAt:now}
             （同进程自证——不再探测，恒 UP）
engine 段   = aggregateHealth() 的 engine（语义化 status + detail，null 时保持 null）
documents段 = aggregateHealth() 的 documents（透传）
gateway 段  = {application:"engine-gateway", status:"UP"}   ← 保留段名兼容前端三卡片
整体 status = engine null → UP；engine 非 UP → DEGRADED；其余 UP
             （server 恒 UP 后判定表坍缩为 engine 单维度）
```

判定规则与原网关逐条对齐（v1.2 修正后的 Java/Go 判定表：unknown 不拖垮整体）。删除原网关的 10s 探测循环/失败计数/OkHttp——同进程没有「探测自己」的意义，健康数据从周期缓存变为实时计算。

### D4: 端口 8090 + actuator 收敛 `include: health`

`application.yml`: `server.port: 8090`；`management.endpoints.web.exposure.include: health`。actuator 首次上公网端口，只放 health（容器 HEALTHCHECK 探测口 `:8090/actuator/health` 与原网关兼容端点同路径，部署脚本零改动）。

### D5: 前端工程整体移入 `engine-server/frontend/`，构建编排同迁

两件事一次做：

1. **目录归属**：`frontend/`（仓库根）→ `engine-server/frontend/`，对齐 `engine-server/python/` 的既有先例——「模块资产、src 外、谁用它在谁家」（`engine-server/build.gradle:70-71` 原注释）。三个嵌套服务归并为同一种归属模式，仓库根不再有独立前端工程。当初选「仓库根」的语境是产物要送 `engine-gateway/static/`，该前提已随网关退役消失。
2. **任务链**：`npmInstall`（fingerprint 跳过）→ `buildFrontend` → `copyFrontendDist`（clean-first 直送 `src/main/resources/static/`）迁入 `engine-server/build.gradle`，`bootJar` 自动内嵌为 `classpath:/static/`；根 `build.gradle` 移除前端任务。

`frontendDir` 路径引用面（全部随本决策同步）：根 `build.gradle:60`（任务迁走时重写）、`.gitignore:30`、`.dockerignore:30`、`settings.gradle:15`（结构注释）；Dockerfile.full 经 Gradle 任务间接引用，无硬编码路径。

**jar 内容不变**：无论源码目录在哪，进 jar 的都只是 npm 构建产物（`BOOT-INF/classes/static/`），.tsx 源码不进 jar——与 python 模式「源码进 jar」的差异源于解释执行 vs 编译执行，jar 携带的始终是运行所需物。

**移动注意**（Windows）：`git mv` 只动 tracked 文件；`node_modules/` 为 untracked，随目录手动移动或删除重装（fingerprint 缓存随路径变化失效，npmInstall 会自动补装）。

### D6: 迁移执行模型——波次并行 + 每任务 commit 锚点 + 断点续传协议

三个执行要求叠加的组织设计（用户约束：网络不稳定、断连后须能续传）：

1. **波次并行**：真实依赖把工作切成 5 个波次（W0-W4）。可并行的窗口是纯文件编辑且互不相交的任务（W1 的 server 代码 vs 前端迁移；W3 的退役网关 vs Docker/Jenkinsfile vs docs 七篇）；git 操作（mv/rm/commit）、端口绑定验证、docker build 物理上必须串行。依赖图：

```
W0 checkpoint（独占）
 ↓
W1 [server 代码 1.x] ∥ [前端迁移 2.x]        ← 文件不相交，可并行
 ↓（合体应用完整）
W2 [端口 8090 切换 3.x]                        ← 依赖 W1 全部
 ↓
W3 [退役 gateway 4.x] ∥ [Docker/Jenkins 5.x] ∥ [docs 6.x]  ← 编辑并行
 ↓（4.x 验证 → 5.x docker build 验证依次串行）
W4 [终验 7.x]
```

2. **每任务 commit 锚点**：每个任务验证通过后立即独立 commit，message 引用任务号（`single-jar: 1.3 ApiPrefixRewriteFilter`）。粒度从「每步」细化到「每任务」——commit 既是回滚单元也是续传定位单元。
3. **断点续传协议**：恢复时以双锚点定位续点——`tasks.md` checkbox 勾选状态（主锚点）+ `git log` 任务号（校验锚点，防勾选滞后于实际提交）；从首个「未勾选且无对应 commit」的任务继续，已提交任务不重做。

风险边界不变：**W1 只增不删**——完成后 engine-server 在 8081 上已是完整合体应用，此时中断，双进程形态照常工作，后续波次全是减法。

### D7: Jenkinsfile 声明式流水线

单 agent（JDK8 + Node22 + Docker），stages：Frontend（npm 构建，可与 Gradle 任务链二选一起点）→ Test（`gradlew test`）→ Package（`gradlew :engine-server:bootJar`）→ Image（docker build）→ Publish（archive jar + 镜像 tag `${BUILD_NUMBER}` 可追溯）。无 Go 工具链。

## Risks / Trade-offs

- [actuator 首次暴露公网端口] → `exposure.include: health` 收敛到单端点；info/metrics 默认不暴露
- [Wrapper 覆写不全导致部分 mapping 失效] → 同时覆写 `getRequestURI()`+`getServletPath()`；步骤 1 集成测试覆盖全路由 + 带查询串/编码路径用例
- [server 已有 WebMvcConfigurer 与三件套冲突] → 步骤 1 前置检查；若有则合并进同一 Configurer 而非并行注册
- [ApiPrefixFilter 与 AssetCacheFilter 同为 HIGHEST_PRECEDENCE 的顺序不确定性] → 二者 URL 模式不相交（`/api` vs `/assets/*`），语义互不影响；均用 `FilterRegistrationBean` 显式 order
- [合体后失去网关层 55MB 预检，超大请求直达 server] → multipart 闸门本就由 server 执行且超限信封文案逐字一致（已实测 60MB→400 code 1000），行为等价；无真实损失
- [前端产物入 jar 后改前端须重打 jar] → 开发态仍走 Vite dev 代理（`/api`→127.0.0.1:8090），仅发布态内嵌；与既有开发流一致
- [rollback 需求] → 无数据迁移、无契约变更，单 commit revert 或镜像 tag 回退即完全回滚

## Migration Plan

| 步 | 波次 | 内容 | 验证点 |
|----|------|------|--------|
| 0 | W0 | 提交 79 个 staged 文件作 checkpoint（Go 网关替换与本变更不得混 commit） | `git status` 干净 |
| 1 | W1 ∥组2 | server 侧三件套捞回 + ApiPrefixFilter + 健康包装（**只增不删**，仍 8081） | `curl :8081/api/system/health`、`:8081/`、`:8081/search`、`:8081/assets/*.js` 头与码 |
| 2 | W1 ∥组1 | `frontend/` → `engine-server/frontend/`（git mv + node_modules 处理）；任务链迁入 `engine-server/build.gradle`；`.gitignore`/`.dockerignore`/`settings.gradle` 注释同步 | 解包 jar 见 `BOOT-INF/classes/static/index.html`；`java -jar` 冒烟；仓库根无 frontend 目录 |
| 3 | W2 | `application.yml` 端口 8090 + actuator 收敛；停用网关进程 | 单进程下全路由 + HEALTHCHECK 探测口 200 |
| 4 | W3 ∥组5,6 | 退役 `engine-gateway/`；根 build.gradle 减负；`.gitignore`/`.dockerignore` 清理 | `gradlew build` 全绿；目录不存在 |
| 5 | W3 ∥组4,6 | Docker/entrypoint 单进程化 + 新增 Jenkinsfile（docker build 验证待 4.1 后串行） | 镜像构建日志单阶段；HEALTHCHECK 路径不变 |
| 6 | W3 ∥组4,5 | `/docs` 增量改写 + 新增 `docs/reqforge-single-jar-*.md` | 文档交叉引用一致 |

每**任务**完成即 commit（锚点含任务号）；文档更新遵循用户约束「写一点更新一点」。断连恢复协议见 D6。

## Open Questions

（无——端口 8090、Filter 方案、frontend 位置、健康判定表均已在探索阶段决策）
