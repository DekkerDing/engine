# 单 JAR 合体：gateway + frontend 并入 engine-server

## Why

当前部署形态是「单容器三进程两跳」：engine-gateway(Go, :8090) 反代 engine-server(Java, :8081)，前端产物由网关承载。进程分离在单容器部署下只带来成本——多一跳环回 HTTP、多一个进程与端口、健康数据要靠 10 秒周期探测缓存、CI 需要 JDK+Node+Go 三套工具链。把入口能力（静态资源 + `/api` 前缀 + 健康聚合）收编进 engine-server 单 JAR，可实现「一次构建一个 jar、一个进程一个端口」的最简部署，并为 Jenkinsfile + Gradle 一条线编排铺路。

## What Changes

- **新增 `/api` 前缀重写 Filter**（engine-server 内）：`/api/**` 进 DispatcherServlet 前剥掉前缀，控制器零改动；`/actuator/**`、静态资源不受影响
- **静态资源 + SPA 回退 + 缓存头三件套迁入 engine-server**：从本仓库 git 历史（当日上午删除的 Java 网关实现）原样捞回 `WebStaticConfig` / `SpaFallbackResolver` / `AssetCacheFilter`，改包名入 `engine.interfaces.web`
- **健康聚合进程内化**：`/api/system/health` 直调 `SystemQueryService`，补 `gateway` 段与整体 `status` 判定，保持前端三卡片 JSON 结构不变；删除探测循环/OkHttp/失败计数
- **端口统一为 8090**（对外契约零变动）；8081 随双进程架构消失
- **actuator 收敛**：`management.endpoints.web.exposure.include: health`（actuator 首次上公网端口，只保留 health）
- **前端工程整体移入 `engine-server/frontend/` 并把构建编排迁入 `engine-server/build.gradle`**（npmInstall → buildFrontend → copyFrontendDist → 注入 jar 的 `classpath:/static/`）：对齐 `engine-server/python/` 的模块资产先例（「谁用它在谁家」，src 外、随模块一棵树），根 build.gradle 的前端任务移除，仓库根不再有独立工程目录
- **退役 `engine-gateway/`（Go 工程）**：目录删除，`docs/reqforge-gateway-go-*.md` 转为历史记录 **BREAKING**（仅对部署脚本/CI 有影响，HTTP 契约不变）
- **Docker/entrypoint 简化**：单 java 进程编排，删除双进程互守与健康等待；HEALTHCHECK 指 `:8090/actuator/health`
- **新增 Jenkinsfile**（声明式流水线：前端 → 单测 → bootJar → 镜像），CI 工具链降为 JDK8 + Node
- **`/docs` 手册批量改写**：deployment / architecture / dev-guide / user-guide / learning-path，并新增 `docs/reqforge-single-jar-*.md` 设计与规约文档（增量提交）

## Capabilities

### New Capabilities

- `single-jar-deployment`: 单 JAR 统一入口——`/api` 前缀重写、静态资源与 SPA 回退、缓存头策略、健康聚合 JSON 契约、单端口 8090、actuator 暴露收敛、单进程容器编排与 Jenkins 构建流水线

### Modified Capabilities

（无——`openspec/specs/` 尚无已归档能力规格，全部要求随新能力规格建立）

## Impact

- **代码**：`engine-server`（新增 Filter + web 三件套 + health 包装 + build.gradle 前端链 + 收编 `frontend/` 为 `engine-server/frontend/`）；`engine-gateway/` 整目录删除；根 `build.gradle` 移除前端任务；`settings.gradle` 仅注释更新
- **部署**：`docker/Dockerfile`、`docker/Dockerfile.full`、`docker/entrypoint.sh`、`.dockerignore`、`.gitignore`；端口 8081 消失（EXPOSE 仅 8090）
- **契约**：HTTP 对外契约不变（`:8090/api/**`、健康 JSON 结构、缓存头语义）；**BREAKING**：8081 不再存在、`engine-gateway` 二进制不再产出、CI 不再需要 Go
- **前置**：当前工作区有当日上午 Go 网关替换的 79 个 staged 文件，必须先提交作 checkpoint 再开始本变更
- **文档**：`/docs` 六份手册 + 两篇新增设计/规约文档；`docs/reqforge-gateway-go-*.md` 标记为历史
