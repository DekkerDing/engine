# 单 JAR 合体对外契约规约（single-jar spec）

> 本文是合体形态的**行为契约速查表**：路由、缓存头、健康 JSON、大小闸门、端口边界、构建产物要求。
> 发给任何"我要验证部署对不对"的人，按第 7 节自测清单逐项打勾即可。
> 设计决策的来龙去脉见 [reqforge-single-jar-design.md](file:///F:/workspace/engine/docs/reqforge-single-jar-design.md)。

---

## 1. 端口与寻址

| 项 | 值 |
|----|-----|
| 唯一端口 | **8090**（`server.port`，静态资源 + `/api/**` + actuator 同源） |
| 旧内部端口 8081 | **不再监听**（连接拒绝即正确状态） |
| API 寻址 | `http://<host>:8090/api/**`——与网关时代逐字节一致，调用方零改动 |
| 容器健康探测 | `http://127.0.0.1:8090/actuator/health`——与原网关兼容端点同路径，部署脚本零改动 |

## 2. 路由表（8090 上的全部命名空间）

| 路径 | 归属 | 行为 |
|------|------|------|
| `/api/documents/**` | DocumentController | 业务 API，统一信封 `{code,message,data,timestamp}` |
| `/api/images/**` | ImageController / ImageBatchController | 图片上传/检索 |
| `/api/requirements/**` | RequirementController | 需求条目 CRUD |
| `/api/search` | SearchController | 混合检索（语义 + 全文 RRF 融合） |
| `/api/system/health` | SystemController | 聚合健康（见第 4 节） |
| `/assets/**` | 静态资源 handler | 哈希资产，长缓存（见第 3 节） |
| `/index.html`、`/favicon.ico` | 静态资源 handler | 入口文件，no-cache |
| `/actuator/health` | actuator | 200 健康状态 JSON（唯一暴露端点） |
| 其余 GET（`/`、`/search`、`/documents`…） | SpaFallbackResolver | 文件存在→原样；前端路由→**200** index.html；`api/`、`actuator/` 前缀→**404 不吞 HTML** |

路由实现：控制器类级 `@RequestMapping` 直接带 `/api` 前缀（命名空间物理隔离，非 Filter 剥前缀——后者与同名前端路由冲突，见设计文档 D1）。

**查询串与编码契约**：`GET /api/documents?q=%E6%B5%8B%E8%AF%95&size=10` → 控制器收到解码后 `q=测试`、`size=10`，百分号转义不发生语义漂移。

## 3. 缓存头策略

| 资源 | Cache-Control | 理由 |
|------|---------------|------|
| `/assets/index-<hash>.js|css` | `public, max-age=31536000, immutable` | 文件名含内容哈希，内容变即换名——可安全缓存一年 |
| `/index.html`（含 SPA 回退产物） | `no-cache` | 入口页必须每次回源校验，否则发版后用户拿到旧资产引用 |
| `/index.html` 状态码 | **200 直返，禁止 301** | 重定向会拖慢首屏且部分客户端处理不一致 |

实现：`AssetCacheFilter`（手写 immutable 头——Spring 5.3 无 `CacheControl.immutable()`；仅挂 `/assets/*`）。

## 4. 聚合健康契约

`GET /api/system/health` → 信封 `data` 结构：

```json
{
  "status": "UP",                  // 整体判定，见下表
  "gateway":  { "application": "engine-gateway", "status": "UP" },   // 段名保留兼容前端三卡片，同进程自证恒 UP
  "server":   { "application": "engine-server",  "status": "UP", "lastError": null, "lastSuccessAt": "..." },
  "engine":   { "status": "UP", "detail": { "ok": true, "degraded": false, "model": "...", "dim": 512 } },
  "documents": { "total": 0, "indexed": 0, "failed": 0 }
}
```

**整体 status 判定表**（server 恒 UP 后坍缩为 engine 单维度）：

| engine 段状态 | 整体 status |
|---------------|-------------|
| `null`（Python 通道未启用） | UP |
| status=UP（ok 且非 degraded） | UP |
| status=DEGRADED（哈希降级） | DEGRADED |
| status=DOWN | DEGRADED |

健康数据为**实时计算**（进程内直调 `SystemQueryService.aggregateHealth()`），无探测缓存延迟。

## 5. 上传大小闸门

| 项 | 值 |
|----|-----|
| 单文件上限 | 50MB（`engine.documents.max-file-size-bytes` = `spring.servlet.multipart.max-file-size`） |
| 请求体上限 | 55MB（`max-request-size`） |
| 超限响应 | **400** + `{"code":1000,"message":"文件超过大小限制（50MB）"}`（文案与网关时代逐字一致） |

multipart 解析由本应用完成——合体后不存在跨进程透传，闸门在应用入口即生效。

## 6. 构建与容器产物要求

| 项 | 要求 |
|----|------|
| 唯一产物 | fat JAR `engine-server.jar`（`gradlew :engine-server:bootJar`，自动拉起前端构建与 python 打包） |
| jar 内路径 | `BOOT-INF/classes/static/index.html` + `assets/`；`BOOT-INF/classes/python/` |
| 不依赖 | 独立网关二进制、外部静态目录 |
| 启动条件 | 仅 JRE 8 + Python 3.10+（脚本目录：`./python` 于 jar 同侧，或 `engine.python.home` 指定） |
| 容器进程 | **单 Java 进程**（exec 模式 PID 1；Python 子进程随其启停）；HEALTHCHECK 探 `:8090/actuator/health`；仅 EXPOSE 8090 |
| CI 工具链 | JDK 8 + Node 22 + Docker，**无 Go**；镜像 tag 含 `${BUILD_NUMBER}` |

## 7. 自测清单（部署验收，逐项打勾）

```bash
BASE=http://127.0.0.1:8090
```

| # | 检查项 | 命令 | 预期 |
|---|--------|------|------|
| 1 | 首页 | `curl -s -o /dev/null -w '%{http_code}' $BASE/` | `200`（HTML） |
| 2 | 前端路由刷新 | `curl -s -o /dev/null -w '%{http_code}' $BASE/search` | `200`（HTML，SPA 回退） |
| 3 | 哈希资产长缓存 | `curl -sI $BASE/assets/index-<hash>.js \| grep -i cache-control` | `public, max-age=31536000, immutable` |
| 4 | 入口页 no-cache 无重定向 | `curl -sI $BASE/index.html \| grep -iE 'HTTP\|cache-control'` | `200` + `no-cache`（无 301） |
| 5 | 未匹配 API 不吞 HTML | `curl -s -o /dev/null -w '%{http_code}' $BASE/api/no-such` | `404`（JSON 信封） |
| 6 | 聚合健康 | `curl -s $BASE/api/system/health` | `{status, gateway, server, engine, documents}` 五段信封 |
| 7 | 业务 API | `curl -s -o /dev/null -w '%{http_code}' $BASE/api/requirements` | `200` |
| 8 | 中文查询串 | `curl -s -o /dev/null -w '%{http_code}' "$BASE/api/search?q=%E6%B5%8B%E8%AF%95"` | `200`（参数解码正确） |
| 9 | 容器探测端点 | `curl -s $BASE/actuator/health` | `200` + `{"status":"UP"}` |
| 10 | metrics 不外露 | `curl -s -o /dev/null -w '%{http_code}' $BASE/actuator/metrics` | `404`（不被 SPA 吞成 200） |
| 11 | 超限上传 | 上传 60MB 文件到 `/api/documents` | `400` + `code:1000` + 文案逐字一致 |
| 12 | 旧端口消失 | `curl -m 2 http://127.0.0.1:8081/` | 连接拒绝 |
| 13 | jar 自包含 | `unzip -l engine-server.jar \| grep BOOT-INF/classes/static` | index.html + assets/ 齐备 |

裸机另查：启动日志 `Tomcat started on port 8090`；`Ctrl+C` 后 `tasklist | findstr python` 无孤儿（容器内由编排器保证）。
