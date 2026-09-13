# reqforge — Gateway Golang 替换 规约文档

> 状态：已实现 · 已自测 · 已完成目录替换 | 版本：1.2 | 日期：2026-09-13

---

## 1. 核心契约（不可变）

替换前后以下行为必须逐条一致：

### 1.1 端口与路由

| 规则 | 值 |
|------|-----|
| 监听端口 | `8090` |
| `/api/**` → 剥 /api → `http://127.0.0.1:8081/**` | 反代 |
| `/api/system/health` → 精确匹配，不走代理 | 网关自响应 |
| `/assets/**` → `static/assets/` 长缓存 | 哈希资产 |
| `/index.html` → `static/index.html` noCache | 入口文件 |
| 其他 GET → 文件存在返回，不存在返回 index.html | SPA 回退 |

### 1.2 反代行为

| 行为 | 规约 |
|------|------|
| 请求体 >55MB | 400 {code:1000, message:"文件超过大小限制"} |
| 流式透传 | 不整包读内存，基于 ReverseProxy，请求体/响应体逐段流转发 |
| FlushInterval | `-1`（SSE 立即 flush） |
| hop-by-hop 头 | `net/http/httputil.ReverseProxy` 自动剔除 |
| ConnectException | 502 {code:5001, message:"上游服务不可用"} |
| Timeout | 504 {code:5001, message:"连接上游服务超时"} |

### 1.3 缓存头

| 路径 | Cache-Control |
|------|---------------|
| `/assets/**` | `public, max-age=31536000, immutable` |
| `/index.html` | `no-cache` |
| SPA 回退（返回 index.html） | `no-cache` |

### 1.4 健康聚合 JSON（与 Java 完全一致）

```json
{
  "code": 0, "message": "ok",
  "data": {
    "status": "UP",
    "gateway": {"application": "engine-gateway", "status": "UP"},
    "server": {"application": "engine-server", "status": "UP", "lastError": null, "lastSuccessAt": "..."},
    "engine": {"status": "UP", "detail": {}},
    "documents": {}
  },
  "timestamp": "2026-09-13T10:00:00Z"
}
```

### 1.5 健康判定规则（v1.2 修正：与 Java 版 HealthController 逐条对齐）

| server | engine | 整体 status |
|--------|--------|-------------|
| UP | UP | UP |
| UP | DEGRADED/DOWN | DEGRADED |
| UP | null（引擎段缺失，如未启用 Python） | **UP**（未知不拖垮整体——v1.1 误写为 DOWN，已修复） |
| DOWN | * | DOWN |

---

## 2. 路由注册顺序（Go 1.22 ServeMux 优先级）

```
1. GET /api/system/health   → healthHandler（精确，最具体）
2. /api/                     → reverseProxy（StripPrefix + ReverseProxy）
3. /assets/                  → assetCacheMiddleware(staticFileServer)
4. GET /index.html           → noCacheStatic("index.html")
5. GET /favicon.ico          → noCacheStatic("favicon.ico")
6. GET /                     → spaFallbackHandler（兜底）
```

---

## 3. 实现要点

### 3.1 反向代理

使用 `net/http/httputil.ReverseProxy`，自定义部分：

1. **请求体大小闸门**：`Transport.RoundTrip` 前检查 `ContentLength`
2. **错误信封**：`ErrorHandler` 中写 `{code:5001, message:"..."}`
3. **SSE 兼容**：`FlushInterval = -1`

### 3.2 SPA 回退 4 分支

```
path 含 "api/"  → 404
path 为空或 "/" → index.html (no-cache)
file 存在       → file (no-cache)
以上都不是      → index.html (no-cache)
```

### 3.3 健康探测

- `time.NewTicker(10s)` goroutine
- `atomic.Int32` 失败计数；`sync.RWMutex` 保护缓存字段
- 一次成功立即 UP，连续 3 次失败才 DOWN

---

## 4. 配置 (config.yaml)

```yaml
server:
  port: 8090
upstream:
  base_url: "http://127.0.0.1:8081"
  connect_timeout: 2s
  read_timeout: 60s
  max_body_bytes: 57671680
health:
  probe_interval: 10s
  failure_threshold: 3
static:
  dir: "./static"
logging:
  level: "info"
  format: "text"
```

---

## 5. 自测验收清单

> v1.2 于 2026-09-13 在真实环境（engine-server :8081 运行中）复测通过。

- [x] `curl http://127.0.0.1:8090/` → 200 + HTML
- [x] `curl http://127.0.0.1:8090/api/system/health` → 200 + 信封（三组件聚合，E2E 实测 UP）
- [x] `/requirements` / `/documents` / `/search` F5 刷新 → 200 + index.html
- [x] `/assets/xxx.js` → `Cache-Control: public, max-age=31536000, immutable`
- [x] `/index.html` → `Cache-Control: no-cache`
- [x] `POST /api/requirements` → 代理到 server 正常响应（E2E 实测 200）
- [x] 停 server → `GET /api/requirements` → 502 + `{"code":5001}`
- [x] 60MB 请求体 → 400 + `{"code":1000,"message":"文件超过大小限制（50MB）"}`（与 server 文案逐字一致）
- [x] `GET /actuator/health` → 200 `{"status":"UP"}`（Docker HEALTHCHECK 探测口，v1.2 新增）
- [x] 启动耗时 < 100ms；内存空闲 < 20MB

---

## 6. v1.2 变更记录（目录替换收尾）

| # | 变更 | 说明 |
|---|------|------|
| 1 | 健康判定修正 | server UP + engine null → UP（对齐 Java），原实现误判 DOWN |
| 2 | 超时语义修正 | `http.Server` 的 Read/WriteTimeout 置 0：绝对期限会掐断 >60s 的流式响应；首字节超时由 Transport `ResponseHeaderTimeout`（=read_timeout）承担，与 OkHttp「按读续期」语义对齐 |
| 3 | 超限文案修正 | 55MB → 50MB，与 server 侧 GlobalExceptionHandler 逐字一致 |
| 4 | 新增 `/actuator/health` | 兼容 Docker HEALTHCHECK 与部署文档探测口 |
| 5 | 优雅停机 | SIGINT/SIGTERM → `srv.Shutdown`（宽限 10s），配合 entrypoint.sh 的 trap 编排 |
| 6 | 探测日志降噪 | 只在状态翻转时记关键日志（对齐 Java） |
| 7 | **静态资产 404 修复** | `/assets/**` 路由缺 `http.StripPrefix`，FileServer 实际查 `static/assets/assets/` → 全部 404（前端会白屏）；v1.1 自测只验证了 Cache-Control 头未验证状态码，故漏检 |
| 8 | **/index.html 301 修复** | `http.ServeFile` 对以 /index.html 结尾的请求会 301 重定向到 `./`（标准库规范化行为），Java 版直接 200；改用 `ServeContent` 直接输出 |
| 9 | 目录替换 | `engine-gateway-go/` 改名 `engine-gateway/`；Java 源码移除（git 历史可溯）；go module 更名 `github.com/dekkerding/engine-gateway` |
| 10 | 前端迁移 | `engine-gateway/frontend/` → 仓库根 `frontend/`；构建任务迁至根 build.gradle，`copyFrontendDist` 产物直送 `engine-gateway/static/` |
| 11 | 部署链接入 | docker/Dockerfile（COPY 二进制+static+config）、Dockerfile.full（builder 加 Go 工具链）、entrypoint.sh（Go 二进制启动，去 JAVA_OPTS_GATEWAY） |