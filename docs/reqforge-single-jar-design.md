# 单 JAR 合体架构设计（single-jar design）

> 本文回答：为什么把网关、前端、业务服务合并成一个 fat JAR、一个 Java 进程、一个 8090 端口；
> 七个关键决策（D1-D7）各自否决了什么；迁移是怎么分步落地的。
> 配套规约（路由表/缓存头/健康判定/自测清单）见 [reqforge-single-jar-spec.md](file:///F:/workspace/engine/docs/reqforge-single-jar-spec.md)。
> 变更管理档案（proposal/specs/tasks 原文）：`openspec/changes/single-jar-consolidation/`。

---

## 1. Context（合体前的形态与动机）

合体前是**单容器三进程两跳**：

```
浏览器 ──▶ engine-gateway (Go, :8090)          ← 前端静态 + /api/** 剥前缀反代
              └──▶ engine-server (Java, :8081)  ← 业务 API（仅容器内回环）
                       └──▶ Python 引擎          ← Py4J/stdio 子进程
```

痛点：

| 痛点 | 具体表现 |
|------|----------|
| 三份部署资产 | jar + Go 二进制 + 前端 dist，任一缺位即坏（容器内 Python 通道实际从未可用——见 D8 说明） |
| 两跳延迟 | 每个请求过网关一次环回转发；上传再过一次 |
| 双端口心智 | 8090 对外 / 8081 内部，开发调试要起两个进程 |
| 双语工具链 | CI 需要 JDK + Go + Node 三套 |

合体后：**一个 fat JAR、一个 Java 进程、一个 8090 端口**承载 REST API（`/api/**`）+ 前端静态资源 + SPA 回退 + actuator/health + Python 向量化子进程。对外 HTTP 契约（路由、健康 JSON、缓存头、上传文案）**逐字节不变**。

锁定约束：Spring Boot 2.6.14 + JDK 8 + Gradle 7.6.4（不升级）；对外端口 8090 不变。

---

## 2. Decisions（七个决策与各自的否决案）

### D1：`/api` 路由策略——控制器映射直接带前缀

6 个控制器类级 `@RequestMapping` 直接加 `/api` 前缀（`DocumentController`→`/api/documents`、`SearchController`→`/api/search`、`ImageController`/`ImageBatchController`→`/api/images`、`RequirementController`→`/api/requirements`、`SystemController`→`/api/system`）。

**为什么不用 Filter 剥前缀（实测推翻的初案）**：剥前缀后控制器裸路径（`/search` 等）与**同名前端路由**在同进程命名空间合并——controller 映射优先级永远高于静态资源 handler，浏览器 F5 刷新 `/search` 会被 `SearchController` 抢走（500 JSON 而非 index.html）。原网关架构下两者靠 `/api` 前缀**物理隔离**，合体后冲突结构性存在，Filter 无法修补。直接带前缀让命名空间天然分离，`SpaFallbackResolver` 的 `api/` 防御分支恰好归位（未匹配的 `/api/no-such` → 404 不吞成 HTML）。

| 否决的替代方案 | 否决原因 |
|----------------|----------|
| `server.servlet.context-path: /api` | 拖累静态资源与 actuator（也被迫挂 /api 下），与「页面与 API 同端口同源」冲突 |
| 改前端 `baseURL` | 波及浏览器/APP/脚本全部调用方，违反「对外契约零变动」 |
| server 内嵌自代理（转发到自身） | 同进程多一跳环回 HTTP，纯性能税，保留双端口复杂度 |
| Filter 剥前缀 | 前端路由与控制器裸路径命名空间冲突（上表首条） |

### D2：静态三件套原样捞回

`WebStaticConfig`（三段 resource handler）、`SpaFallbackResolver`（SPA 回退 + `api/`/`actuator/` 防御分支）、`AssetCacheFilter`（`public, max-age=31536000, immutable` 手写头——Spring 5.3 无 `CacheControl.immutable()`）从旧 Java 网关的 git 历史捞回，改包名入 `io.github.dekkerding.engine.interfaces.web`。资源位置 `classpath:/static/` 不变——前端产物从网关 jar 迁到 server jar 的同一路径，三件套零适配。

### D3：健康聚合进程内化——直调 Service

`SystemController#/api/system/health` 直调 `SystemQueryService.aggregateHealth()` 组装合体结构：

```
server 段   = {application:"engine-server", status:"UP"}   ← 同进程自证，不再探测，恒 UP
gateway 段  = {application:"engine-gateway", status:"UP"}  ← 段名保留，兼容前端三卡片
engine 段   = 语义化 {status: UP|DEGRADED|DOWN, detail}（透传 aggregateHealth）
documents段 = 文档统计（透传）
整体 status = engine null → UP；engine 非 UP → DEGRADED
```

server 恒 UP 后判定表坍缩为 **engine 单维度**——与原 Go 网关 `health.go` 的判定逻辑逐字段对齐（unknown 不拖垮整体）。删除原网关的 10s 探测循环/失败计数——同进程没有「探测自己」的意义。

### D4：端口 8090 + actuator 收敛 `include: health`

`server.port: 8090`（原 8081 消失）；actuator 首次上公网端口，只放 health——容器 HEALTHCHECK 探测口 `:8090/actuator/health` 与原网关兼容端点**同路径，部署脚本零改动**。未暴露端点（如 metrics）由 `SpaFallbackResolver` 的防御分支拒绝，保持 404 语义不被 SPA 回退吞掉。

### D5：前端工程移入 `engine-server/frontend/`，构建编排同迁

对齐 `engine-server/python/` 的既有先例——「模块资产、src 外、谁用它在谁家」。三个嵌套服务（python/frontend/Java 源码）归并为同一种归属模式，仓库根只保留一个工程。任务链 `npmInstall → buildFrontend → copyFrontendDist` 迁入 `engine-server/build.gradle`，产物走 **build/ 中转**（`build/frontend-static/static/` → sourceSets 注册 → jar 内 `classpath:/static/`），不污染 `src/main/resources`——对齐 `packagePython` 先例。

jar 内容语义：前端**构建产物**入 jar（`.tsx` 源码不入）与 python **源码**入 jar 的差异源于编译执行 vs 解释执行——jar 携带的始终是运行所需物。

### D6：迁移执行模型——波次并行 + 每任务 commit 锚点 + 断点续传

网络不稳定约束下的组织设计：28 个任务切 5 个波次（W0 checkpoint → W1 server 代码∥前端迁移 → W2 端口切换 → W3 退役网关∥Docker/Jenkins∥docs → W4 终验），**每任务独立 commit**（message 引用任务号），断连恢复以双锚点定位（`tasks.md` 勾选状态为主 + `git log` 任务号校验）。W1 **只增不删**——完成后即有完整合体应用可用，后续波次全是减法，任意点中断不留半成品状态。

### D7：Jenkinsfile 声明式流水线

单 agent（JDK8 + Node22 + Docker，**无 Go 工具链**）：Frontend → Test → Package → Image → Publish；镜像 tag 含 `${BUILD_NUMBER}`。Image 阶段走本地轨 Dockerfile（复用流水线内 jar 产物）；`Dockerfile.full`（容器内全构建）留给「仅有 Docker」的复现场景。

### D8：容器内 Python 脚本目录补齐（实现期发现的既有缺口）

`PythonProcessLauncher` 按**文件系统路径**探测脚本目录（`engine.python.home` → `ENGINE_PYTHON_HOME` → `./python` → `./python-runtime`），不读 jar 内 `BOOT-INF/classes/python/` 资源。注释里的「PythonBootstrap 解压」**从未实现**——旧双进程架构的镜像同样缺这一层，Python 通道在容器内从未真正可用。单 JAR 合体在两轨 Dockerfile 补 `COPY engine-server/python/ /app/python/`（CWD=/app 时 `./python` 探测命中）。fat jar 内的 `BOOT-INF/classes/python/` 满足「单 jar 资产自包含」的完整性规约；运行时探测走文件系统是 launcher 的既有契约。

---

## 3. 迁移记录骨架（波次 → 锚点）

| 波次 | 内容 | 关键验证 | commit 锚点 |
|------|------|----------|-------------|
| W0 | 79 文件 checkpoint（Go 网关替换先落地） | `git status` 干净 | `0.1` |
| W1 组1 | 三件套捞回（D2）→ 健康内化（D3）→ 集成验证 | `:8081/`、`:8081/search`、assets immutable、`:8081/api/no-such` 404 | `1.1`-`1.5` |
| W1 组2 | `frontend/` git mv → `engine-server/frontend/`；任务链迁入（D5）；jar 自包含验证 | 解包见 `BOOT-INF/classes/static/`；`java -jar` 冒烟 | `2.1`-`2.4` |
| W2 | 端口 8090 + actuator 收敛（D4）；停网关进程 | 单进程全路由；60MB→400 code 1000 文案逐字一致；8081 无监听 | `3.1`-`3.2` |
| W3 组4 | 删 `engine-gateway/`；ignore 文件清理 | `gradlew build` 全绿；0 untracked | `4.1`-`4.2` |
| W3 组5 | Dockerfile×2 单进程化（D8 补 COPY python）；entrypoint exec 模式；Jenkinsfile（D7） | grep 无 gateway 功能残留；HEALTHCHECK 200 | `5.1`-`5.5` |
| W3 组6 | docs 七篇（本文 + spec + 五篇改写） | 交叉引用一致；无 8081/双进程残留 | `6.1`-`6.7` |
| W4 | 全量回归 + 遗留项清点 | `gradlew clean build` 全绿；spec 自测清单逐项 | `7.1`-`7.2` |

---

## 4. 合体后进程拓扑（终态）

```
entrypoint.sh (exec 让渡 PID 1)
└── java engine-server.jar (:8090，唯一进程、唯一端口)
    ├── Tomcat：/api/**（6 控制器，D1 前缀方案）
    │           /assets/**、/index.html、/** SPA 回退（三件套，D2）
    │           /actuator/health（D4 收敛）
    └── python server_py4j.py / server_stdio.py（随 Java 启停，环回 25335 / stdio）
```

容器资产：`/app/engine-server.jar`（fat jar，含前端产物与 python 脚本资产）+ `/app/python/`（运行时探测目录，D8）+ `/app/models/`（预下载权重，离线可用）+ `/app/data/`（挂卷）。

---

## 5. 风险与回滚

| 风险 | 处置 |
|------|------|
| actuator 首次上公网端口 | `exposure.include: health` 收敛到单端点；未暴露端点 404（防御分支保证） |
| 失去网关层 55MB 预检 | multipart 闸门本就由 server 执行且超限文案逐字一致（实测 60MB→400 code 1000），行为等价 |
| 改前端须重打 jar | 开发态仍走 Vite dev 代理（`/api`→127.0.0.1:8090），仅发布态内嵌，与既有开发流一致 |
| JVM 被强杀 Python 孤儿 | 容器内 PID 1 死→容器退出→内核清命名空间，无跨容器残留；裸机 Ctrl+C 走 shutdown hook 连带终止 |

**回滚**：无数据迁移、无契约变更——单 commit revert（代码）或镜像 tag 回退（部署）即完全回滚。
