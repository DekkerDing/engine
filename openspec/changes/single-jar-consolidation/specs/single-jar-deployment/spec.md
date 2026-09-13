## Purpose

定义 engine-server 以单 JAR 形态承载全部入口能力的对外行为契约：`/api` 前缀路由、前端静态资源与 SPA 回退、缓存头策略、聚合健康接口、单端口暴露边界、容器内单进程编排与 Jenkins 构建产物要求。

## ADDED Requirements

### Requirement: 单端口统一入口
系统 SHALL 在唯一端口 8090 上同时提供 API、前端静态资源与健康探测端点；端口 8081 SHALL NOT 再被监听。API 调用方（浏览器、APP、部署脚本）的既有寻址 `http://<host>:8090/api/**` MUST 保持不变。

#### Scenario: 浏览器同源访问页面与 API
- **WHEN** 浏览器访问 `http://127.0.0.1:8090/` 加载页面后，前端发起 `GET /api/requirements`
- **THEN** 两者均在 8090 端口返回 200，API 响应走统一信封 `{code,message,data}`

#### Scenario: 旧端口 8081 不再存在
- **WHEN** 部署后对 `http://127.0.0.1:8081` 发起任意请求
- **THEN** 连接被拒绝（无进程监听该端口）

### Requirement: /api 前缀重写
系统 SHALL 将以 `/api` 开头的请求路径剥掉该前缀后交给业务控制器处理；查询字符串与路径编码 MUST 原样保留；非 `/api` 开头的路径（静态资源、actuator、SPA 路由）MUST 不受该重写影响。

#### Scenario: 常规 API 调用
- **WHEN** 客户端发起 `GET /api/system/health`
- **THEN** 由 `/system/health` 端点处理并返回 200 信封响应

#### Scenario: 带查询串与编码路径的请求
- **WHEN** 客户端发起 `GET /api/documents?q=%E6%B5%8B%E8%AF%95&size=10`
- **THEN** 控制器收到查询参数 `q=测试`（解码后）与 `size=10`，加号与百分号转义不发生语义漂移

#### Scenario: 未匹配的 API 路径
- **WHEN** 客户端请求不存在的 `/api/no-such-endpoint`
- **THEN** 返回 404，不回退为 index.html

### Requirement: 前端静态资源与 SPA 回退
系统 SHALL 从内嵌资源 `classpath:/static/` 服务前端构建产物：`/assets/**` 返回对应文件；`/index.html`、`/favicon.ico` 返回入口文件；其余非 `/api`、非 actuator 的 GET 路径，文件存在则返回文件，不存在则 MUST 返回入口页 `index.html` 且状态码为 200。`/api/**` 路径 MUST NOT 被 SPA 回退吞成 HTML。

#### Scenario: 前端路由刷新
- **WHEN** 用户在前端路由 `/search` 页面按 F5 刷新
- **THEN** 返回 200 与 index.html 内容，前端路由接管渲染

#### Scenario: 存在的杂项静态文件
- **WHEN** 请求 `favicon.ico` 且前端产物包含该文件
- **THEN** 返回该文件；产物不含该文件时返回 404

#### Scenario: API 路径不落入 SPA 回退
- **WHEN** 请求未匹配的 `/api/xxx`
- **THEN** 返回 404 而非 index.html

### Requirement: 静态资源缓存头策略
系统 SHALL 对哈希资产设置 `Cache-Control: public, max-age=31536000, immutable`；对 `index.html` 及 SPA 回退产物设置 `Cache-Control: no-cache`；对 `/index.html` 的请求 MUST 直接返回 200（MUST NOT 发生 301 重定向）。

#### Scenario: 哈希资产长缓存
- **WHEN** 请求 `/assets/index-<hash>.js`
- **THEN** 响应头含 `Cache-Control: public, max-age=31536000, immutable`

#### Scenario: 入口文件每次校验
- **WHEN** 请求 `/index.html`
- **THEN** 响应状态码为 200 且 `Cache-Control: no-cache`

### Requirement: 聚合健康接口契约
`GET /api/system/health` SHALL 返回统一信封，`data` 结构 MUST 包含：整体 `status`（UP/DEGRADED/DOWN）、`gateway` 段（同进程自证，恒 UP）、`server` 段（同进程自证，含 `lastError`、`lastSuccessAt` 字段）、`engine` 段（语义化 `status` 与 `detail` 明细）、`documents` 段（统计透传）。健康数据 MUST 为实时计算（进程内直调），不存在探测缓存延迟。整体判定规则：引擎降级或不 ok 时 MUST 为 DEGRADED；引擎段缺失（如未启用 Python）时整体 MUST 为 UP。

#### Scenario: 全链路健康
- **WHEN** 应用、Python 引擎均正常
- **THEN** `data.status` 为 UP，gateway/server/engine 三段 status 均为 UP

#### Scenario: Python 引擎降级联动
- **WHEN** Python 引擎处于哈希降级（engine.degraded=true）
- **THEN** `data.engine.status` 为 DEGRADED 且 `data.status` 为 DEGRADED

#### Scenario: 引擎段缺失
- **WHEN** 运行环境未启用 Python 通道（engine 段为 null）
- **THEN** `data.engine` 为 null 且 `data.status` 为 UP

### Requirement: 上传大小限制由应用自身执行
系统 SHALL 在入口处对请求体执行大小闸门：单文件超过 50MB 或请求超过 55MB 时 MUST 返回 400 与信封 `{code:1000, message:"文件超过大小限制（50MB）"}`；multipart 解析由本应用完成，不存在跨进程透传。

#### Scenario: 超限上传被拒
- **WHEN** 客户端上传 60MB 请求体到 `/api/documents`
- **THEN** 返回 400 与 `{"code":1000,"message":"文件超过大小限制（50MB）"}`

### Requirement: actuator 暴露收敛
公网端口上 actuator SHALL 仅暴露 `health` 端点；`/actuator/health` MUST 返回 200 供容器 HEALTHCHECK 探测；其余 actuator 端点（info/metrics 等）MUST NOT 在 8090 上可访问。

#### Scenario: 容器健康探测
- **WHEN** 编排器执行 `curl -sf http://127.0.0.1:8090/actuator/health`
- **THEN** 返回 200 与健康状态 JSON

#### Scenario: metrics 不外露
- **WHEN** 请求 `/actuator/metrics`
- **THEN** 返回 404

### Requirement: 单 JAR 构建产物
构建流程 SHALL 产出唯一可执行 fat JAR（engine-server.jar），前端构建产物 MUST 内嵌于 JAR 的 `classpath:/static/`；产物 MUST NOT 依赖独立网关二进制或外部静态目录。

#### Scenario: jar 自包含验证
- **WHEN** 解包 engine-server.jar 并检查 `BOOT-INF/classes/static/`
- **THEN** 存在 `index.html` 与 `assets/` 目录

#### Scenario: 仅凭 jar 启动即全功能
- **WHEN** 在仅有 JRE 8 与 Python 的环境执行 `java -jar engine-server.jar`
- **THEN** 8090 端口提供页面、API 与健康端点全部功能

### Requirement: 单进程容器编排
容器内 SHALL 仅运行一个 Java 进程（其 Python 子进程随其启停）；entrypoint MUST 在收到 SIGTERM 时转发给 Java 进程并由其 shutdown hook 连带终止 Python 子进程；容器 HEALTHCHECK MUST 探测 `:8090/actuator/health`；除 8090 外 MUST NOT EXPOSE 其他端口。

#### Scenario: 优雅停机连带 Python
- **WHEN** `docker stop` 触发 entrypoint 收到 SIGTERM
- **THEN** Java 进程完成在途请求后退出，Python 子进程随之终止，容器以 0 退出

#### Scenario: 任一关键进程崩溃即容器退出
- **WHEN** Java 进程（或其 Python 子进程）异常退出
- **THEN** 容器以非零码退出，由编排器感知重启

### Requirement: Jenkins 流水线构建契约
仓库 SHALL 提供声明式 Jenkinsfile，流水线 MUST 依次完成前端构建、服务端单测、fat JAR 打包与 Docker 镜像构建；构建工具链 MUST NOT 依赖 Go；流水线产物（jar 与镜像 tag）MUST 可追溯至构建号。

#### Scenario: 一条命令出全量产物
- **WHEN** Jenkins agent（JDK8 + Node22 + Docker）执行该流水线
- **THEN** 产出内嵌前端的 engine-server.jar 与已打 tag 的 Docker 镜像，全部单测通过
