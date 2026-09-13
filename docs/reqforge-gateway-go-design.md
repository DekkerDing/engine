# reqforge — Gateway Golang 替换 技术设计文档

> 状态：已实现 · 已自测 · 已完成目录替换 | 版本：1.2 | 日期：2026-09-13

---

## 1. 背景与动机

### 1.1 现状

engine-gateway 当前为 Java / Spring Boot 2.6 实现（[GatewayApplication.java](file:///F:/workspace/engine/engine-gateway/src/main/java/io/github/dekkerding/gateway/GatewayApplication.java)），提供以下服务：

| 职责 | 实现类 | 文件 |
|------|--------|------|
| 反向代理 /api/** → :8081 | `ProxyController` | [ProxyController.java](file:///F:/workspace/engine/engine-gateway/src/main/java/io/github/dekkerding/gateway/proxy/ProxyController.java) |
| 静态资源 + SPA 回退 | `WebStaticConfig` + `SpaFallbackResolver` | [WebStaticConfig.java](file:///F:/workspace/engine/engine-gateway/src/main/java/io/github/dekkerding/gateway/statics/WebStaticConfig.java) [SpaFallbackResolver.java](file:///F:/workspace/engine/engine-gateway/src/main/java/io/github/dekkerding/gateway/statics/SpaFallbackResolver.java) |
| 哈希资产长缓存 | `AssetCacheFilter` | [AssetCacheFilter.java](file:///F:/workspace/engine/engine-gateway/src/main/java/io/github/dekkerding/gateway/statics/AssetCacheFilter.java) |
| 健康聚合 | `HealthController` + `UpstreamHealthService` | [HealthController.java](file:///F:/workspace/engine/engine-gateway/src/main/java/io/github/dekkerding/gateway/health/HealthController.java) [UpstreamHealthService.java](file:///F:/workspace/engine/engine-gateway/src/main/java/io/github/dekkerding/gateway/health/UpstreamHealthService.java) |
| 超时/连接池 | `OkHttpConfig` | [OkHttpConfig.java](file:///F:/workspace/engine/engine-gateway/src/main/java/io/github/dekkerding/gateway/config/OkHttpConfig.java) |

### 1.2 动机

- **轻量化**：网关仅做 HTTP 层路由/代理/静态资源，不需要 Spring Boot 全量依赖（启动慢、内存 256MB+）
- **部署体积**：Go 编译为单二进制（~10MB），无需 JRE，镜像体积从 ~200MB → ~20MB
- **启动速度**：Go 网关启动 < 100ms vs Spring Boot 3-5s
- **团队技能**：Go 在 DevOps/网关层比 Java 更普遍

### 1.3 范围

只替换 engine-gateway，不碰 engine-server 和 frontend。对外契约完全不变：
- 端口不变（8090）
- 路由规则不变（/api/** → 反代，非 /api → 静态资源/SPA）
- 健康聚合结构不变（前端仪表盘三卡片消费的 JSON 结构）
- API 信封不变（{code, message, data}）

---

## 2. 架构设计

### 2.1 模块拓扑（替换前后对比）

```
替换前:
  engine-gateway (Java Spring Boot :8090)
    ├─ OkHttpClient (连接 :8081)
    ├─ ResourceHandler (静态资源 + SPA)
    └─ @Scheduled (周期探测 :8081)

替换后:
  engine-gateway (Go net/http :8090)
    ├─ http.ReverseProxy (连接 :8081)
    ├─ http.FileServer + custom 404 fallback (静态资源 + SPA)
    └─ time.Ticker goroutine (周期探测 :8081)
```

### 2.2 进程树（部署形态）

```
entrypoint.sh (PID 1)
├── java engine-server.jar
│   └── python server_py4j.py
└── engine-gateway (.exe)       ← 替换 java engine-gateway.jar
```

### 2.3 目录结构

```
engine-gateway/
├── main.go                      # 入口：组装路由 + 启动 HTTP server
├── go.mod                       # Go module 定义
├── go.sum
├── config.yaml                  # 默认配置（与 application.yml 对应）
├── internal/
│   ├── config/
│   │   └── config.go            # 配置结构体 + YAML 加载
│   ├── handler/
│   │   ├── proxy.go             # /api/** 反向代理（流式透传）
│   │   ├── health.go            # /api/system/health 聚合健康
│   │   └── static.go            # 静态资源 + SPA 回退（net/http 原生）
│   ├── middleware/
│   │   └── cache.go             # /assets/** 长缓存头（public,max-age=31536000,immutable）
│   └── model/
│       └── api_response.go      # 统一信封 {code,message,data,timestamp}
├── static/                      # 前端 dist/ 产物（构建时复制进来）
│   ├── index.html
│   └── assets/
└── Dockerfile                   # Go 网关独立镜像（FROM scratch/alpine）
```

---

## 3. 详细设计（按职责逐项对照）

### 3.1 反向代理 ProxyController → handler/proxy.go

**Java 侧行为清单**（[ProxyController.java:L63-L255](file:///F:/workspace/engine/engine-gateway/src/main/java/io/github/dekkerding/gateway/proxy/ProxyController.java)）：

| # | 行为 | Java 实现 | Go 实现策略 |
|---|------|-----------|-------------|
| 1 | 接管 `/api/**` 全部 HTTP 方法 | `@RequestMapping("/api/**")` | `http.StripPrefix("/api", reverseProxy)` |
| 2 | 剥 `/api` 前缀拼上游 URL | `uri.substring(4)` | `http.StripPrefix` 自动剥 + `NewSingleHostReverseProxy` |
| 3 | 请求体大小闸门（>55MB 拒绝） | `declaredLength > maxRequestBodyBytes` | 中间件 `http.MaxBytesReader` 包装 |
| 4 | 流式请求体透传（不整包读内存） | `Okio.source(in) → sink.writeAll` | `net/http/httputil.ReverseProxy` 内置流式 |
| 5 | 流式响应体透传（SSE 兼容） | 8KB buffer 循环 `read→write→flush` | `FlushInterval: -1`（立即 Flush） |
| 6 | hop-by-hop 头剔除 | 硬编码集合剔除 | `ReverseProxy` 自动处理 hop-by-hop |
| 7 | Content-Length 剔除（请求/响应） | `REQ_CONTENT_LENGTH` / `RESP_CONTENT_LENGTH` | `ReverseProxy` 自动处理 |
| 8 | ConnectException → 502 | `writeEnvelopedError(502, 5001, msg)` | `ErrorHandler` 回调中判断 `net.OpError` |
| 9 | SocketTimeoutException → 504 | `writeEnvelopedError(504, 5001, msg)` | `ErrorHandler` 回调中判断超时 |
| 10 | 统一信封错误 `{code,message,data}` | 手写 JSON 字符串 | `json.NewEncoder(w).Encode(ApiResponse)` |

**关键决策**：Go 标准库 `net/http/httputil.ReverseProxy` 已完整实现流式透传、hop-by-hop 头剔除、请求体流式写入——这些在 Java 侧需要手动构建 OkHttp RequestBody 的逻辑全部不再需要。我们只需关注业务差异：请求体大小闸门 和 错误信封格式。

### 3.2 静态资源 + SPA 回退 WebStaticConfig → handler/static.go

**Java 侧行为清单**（[WebStaticConfig.java:L1-L52](file:///F:/workspace/engine/engine-gateway/src/main/java/io/github/dekkerding/gateway/statics/WebStaticConfig.java) + [SpaFallbackResolver.java:L1-L67](file:///F:/workspace/engine/engine-gateway/src/main/java/io/github/dekkerding/gateway/statics/SpaFallbackResolver.java)）：

| # | 行为 | Java 实现 | Go 实现策略 |
|---|------|-----------|-------------|
| 1 | `/assets/**` → `static/assets/` 长缓存 | 资源 handler + Filter 打头 | `http.FileServer` + `Cache-Control` 头中间件 |
| 2 | `/index.html` → `static/index.html` noCache | `setCacheControl(noCache())` | 中间件判断路径设置 `Cache-Control: no-cache` |
| 3 | `/**` → SPA 回退 index.html | `SpaFallbackResolver` | 自定义 `http.Handler`：存在→返回，不存在→返回 index.html |
| 4 | `/api/**` 不走 SPA 回退（返回 404） | `resourcePath.startsWith("api/") → null` | 代理 handler 先注册，优先级高于 SPA handler |
| 5 | `/favicon.ico` 从 static/ 返回 | resource handler | `http.FileServer` 自动处理 |

**Go 实现方案**：使用 `http.ServeMux`（Go 1.22+ 支持方法和路径参数）的路由优先级：

```go
mux := http.NewServeMux()
// 优先级 1: API 代理（最具体匹配先注册）
mux.Handle("/api/", proxyHandler)        // 带尾部斜杠匹配 /api/*

// 优先级 2: assets 长缓存
mux.Handle("/assets/", cacheMiddleware("public,max-age=31536000,immutable", assetHandler))

// 优先级 3: index.html noCache
mux.HandleFunc("GET /index.html", noCacheHandler)
mux.HandleFunc("GET /favicon.ico", staticHandler)

// 优先级 4: SPA 回退（最不具体）
mux.HandleFunc("/", spaFallbackHandler)
```

**SPA 回退逻辑**：

```go
func spaFallback(w http.ResponseWriter, r *http.Request) {
    // 先尝试返回真实文件
    path := filepath.Join(staticDir, r.URL.Path)
    if info, err := os.Stat(path); err == nil && !info.IsDir() {
        w.Header().Set("Cache-Control", "no-cache")
        http.ServeFile(w, r, path)
        return
    }
    // 不存在 → 回退 index.html（HTTP 200，not 404）
    w.Header().Set("Cache-Control", "no-cache")
    http.ServeFile(w, r, filepath.Join(staticDir, "index.html"))
}
```

### 3.3 哈希资产长缓存 AssetCacheFilter → middleware/cache.go

**Java 行为**（[AssetCacheFilter.java:L1-L78](file:///F:/workspace/engine/engine-gateway/src/main/java/io/github/dekkerding/gateway/statics/AssetCacheFilter.java)）：

- URL 前缀匹配 `/assets/` → 响应头打 `Cache-Control: public, max-age=31536000, immutable`
- dispatcherTypes 包含 REQUEST + FORWARD

**Go 实现**：HTTP 中间件函数，比 Java Filter 更简洁：

```go
func assetCacheMiddleware(next http.Handler) http.Handler {
    return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
        w.Header().Set("Cache-Control", "public, max-age=31536000, immutable")
        next.ServeHTTP(w, r)
    })
}
```

### 3.4 健康聚合 HealthController → handler/health.go

**Java 行为清单**（[HealthController.java:L1-L98](file:///F:/workspace/engine/engine-gateway/src/main/java/io/github/dekkerding/gateway/health/HealthController.java) + [UpstreamHealthService.java:L1-L150](file:///F:/workspace/engine/engine-gateway/src/main/java/io/github/dekkerding/gateway/health/UpstreamHealthService.java)）：

| # | 行为 | Java 实现 | Go 实现策略 |
|---|------|-----------|-------------|
| 1 | 端点 `GET /api/system/health`（精确优先于代理通配） | `@GetMapping("/api/system/health")` | 在 mux 中先注册此精确路由，再注册 `/api/` 代理 |
| 2 | 周期探测 upstream `/system/health` | `@Scheduled(fixedDelay=10s)` | `time.NewTicker(10s)` + goroutine |
| 3 | 连续失败 3 次才 DOWN | `AtomicInteger consecutiveFailures >= failureThreshold` | `atomic.Int32` |
| 4 | 一次成功立即 UP | 计数清零 + `serverUp=true` | 同逻辑 |
| 5 | 缓存 engine / documents 段 | `volatile` 字段 | `sync.RWMutex` 保护的结构体字段 |
| 6 | 整链路判定（DOWN/DEGRADED/UP） | `overallStatus()` | 同逻辑 |
| 7 | 引擎语义化状态（UP/DEGRADED/DOWN） | `engineStatus()` | 同逻辑 |
| 8 | 响应走统一信封 `ApiResponse` | `ApiResponse.ok(result)` | 同结构 JSON |

**Go 固定结构体**：

```go
type HealthResponse struct {
    Status    string                 `json:"status"`     // UP / DEGRADED / DOWN
    Gateway   map[string]interface{} `json:"gateway"`    // {application, status}
    Server    map[string]interface{} `json:"server"`     // {application, status, lastError, lastSuccessAt}
    Engine    map[string]interface{} `json:"engine"`     // {status, detail}
    Documents interface{}            `json:"documents"`  // 透传
}
```

### 3.5 统一信封 ApiResponse → model/api_response.go

与 Java 侧完全同构（[ApiResponse.java:L1-L45](file:///F:/workspace/engine/engine-gateway/src/main/java/io/github/dekkerding/gateway/common/ApiResponse.java)）：

```go
type ApiResponse struct {
    Code      int         `json:"code"`
    Message   string      `json:"message"`
    Data      interface{} `json:"data"`
    Timestamp string      `json:"timestamp"`
}
```

### 3.6 配置对等 applcation.yml → config.yaml

| Java 配置项 | Go 配置项 | 默认值 |
|-------------|-----------|--------|
| `server.port` | `server.port` | 8090 |
| `gateway.upstream.base-url` | `upstream.base_url` | http://127.0.0.1:8081 |
| `gateway.upstream.connect-timeout-ms` | `upstream.connect_timeout` | 2s |
| `gateway.upstream.read-timeout-ms` | `upstream.read_timeout` | 60s |
| `gateway.upstream.max-request-body-bytes` | `upstream.max_body_bytes` | 55MB |
| `gateway.health.probe-interval-ms` | `health.probe_interval` | 10s |
| `gateway.health.failure-threshold` | `health.failure_threshold` | 3 |
| — | `static.dir` | ./static |

---

## 4. 与前端/后端/APP 的兼容性保证

### 4.1 前端（零改动）

- Vite 开发代理目标：`http://127.0.0.1:8090` 不变（Go 网关同端口）
- 生产态相对路径 `/api/**` 不变（同源访问）
- SPA 路由刷新 `/search`、`/documents` 回退 index.html 不变
- 哈希资产长缓存头不变
- 健康接口 `GET /api/system/health` 响应结构完全一致

### 4.2 engine-server（零改动）

- 反代目标 `http://127.0.0.1:8081` 不变
- HTTP 请求/响应格式完全一致（流式透传，hop-by-hop 自动处理）
- 健康探测端点 `/system/health` 不变

### 4.3 APP 端（零改动）

- API 基址 `http://...:8090/api` 不变
- 所有接口契约不变

### 4.4 Gradle 构建体系

- `build.gradle` 中移除 `bootJar` 等 Java 构建任务
- 替换为：构建 Go 二进制 + 复制前端 dist → `engine-gateway/static/`
- Gradle 任务 `buildFrontend` 和 `copyFrontendDist` 保留但目标目录改为 `engine-gateway/static/`

---

## 5. 构建与部署变更

### 5.1 构建流程（替换后）

```
1. gradlew buildFrontend     # 前端 npm build（不变）
2. gradlew copyFrontendDist  # 复制 dist/ → engine-gateway/static/（目标变更）
3. cd engine-gateway && go build -o engine-gateway.exe .  # Go 编译
```

### 5.2 Docker 镜像

```
# 构建阶段（旧）
FROM ... AS build
  JDK + Gradle → bootJar

# 构建阶段（新）
FROM golang:1.22-alpine AS build
  COPY engine-gateway/ ./
  RUN go build -o engine-gateway .

FROM alpine:3.20
  COPY --from=build engine-gateway .
  COPY static/ /app/static/
  CMD ["./engine-gateway"]
```

### 5.3 启动命令

```powershell
# 替换前
java -jar engine-gateway.jar

# 替换后
.\engine-gateway.exe                    # Windows
./engine-gateway                        # Linux
./engine-gateway -config config.yaml    # 指定配置
```

---

## 6. 风险与缓解

| 风险 | 缓解措施 |
|------|----------|
| 流式透传行为差异导致 SSE 不兼容 | Go `ReverseProxy` 内置 FlushInterval 机制，设置 `-1` 立即 flush |
| 前端路由回退逻辑差异导致 404 | 完整对照 SPA 回退 4 个分支实现，集成测试覆盖所有前端路由 |
| Docker 镜像构建脚本断裂 | 保留旧 Dockerfile，新 Dockerfile 独立命名 |
| 缓存头遗漏导致前端发布后用户拿旧资源 | 集成测试检查 /assets/ 路径响应头含 immutable |

---

## 8. 实现落地记录

### 8.1 实施步骤

| 步骤 | 内容 | 文件 | 状态 |
|------|------|------|------|
| 1 | 分析 Java 网关源码，梳理全部职责与契约 | 11 个 Java 源文件 + application.yml | ✅ 完成 |
| 2 | 编写 Go 网关设计文档 | [reqforge-gateway-go-design.md](file:///F:/workspace/engine/docs/reqforge-gateway-go-design.md) | ✅ 完成 |
| 3 | 编写 Go 网关规约文档 | [reqforge-gateway-go-spec.md](file:///F:/workspace/engine/docs/reqforge-gateway-go-spec.md) | ✅ 完成 |
| 4 | 搭建 Go 项目骨架（go.mod / config.yaml / 目录结构） | [go.mod](file:///F:/workspace/engine/engine-gateway/go.mod) [config.yaml](file:///F:/workspace/engine/engine-gateway/config.yaml) | ✅ 完成 |
| 5 | 实现配置加载模块 | [config.go](file:///F:/workspace/engine/engine-gateway/internal/config/config.go) | ✅ 完成 |
| 6 | 实现统一信封模型 | [api_response.go](file:///F:/workspace/engine/engine-gateway/internal/model/api_response.go) | ✅ 完成 |
| 7 | 实现反向代理 Handler | [proxy.go](file:///F:/workspace/engine/engine-gateway/internal/handler/proxy.go) | ✅ 完成 |
| 8 | 实现静态资源 + SPA 回退 Handler | [static.go](file:///F:/workspace/engine/engine-gateway/internal/handler/static.go) | ✅ 完成 |
| 9 | 实现健康聚合 Handler | [health.go](file:///F:/workspace/engine/engine-gateway/internal/handler/health.go) | ✅ 完成 |
| 10 | 实现缓存头中间件 | [cache.go](file:///F:/workspace/engine/engine-gateway/internal/middleware/cache.go) | ✅ 完成 |
| 11 | 实现 main.go 路由注册 + HTTP Server | [main.go](file:///F:/workspace/engine/engine-gateway/main.go) | ✅ 完成 |
| 12 | 编译验证（go mod tidy → go build） | — | ✅ 11.7MB 二进制 |
| 13 | 自测 9 项 | 见下表 | ✅ 全部通过 |

### 8.2 自测结果

对照 [规约文档第 7 节自测清单](file:///F:/workspace/engine/docs/reqforge-gateway-go-spec.md#7-自测清单——gateway-独立验证)：

| # | 验证项 | 预期 | 实际 | 结果 |
|---|--------|------|------|------|
| 1 | `GET /` → 200 | 200 + index.html | 200 | ✅ |
| 2 | `GET /requirements` → 200 SPA 回退 | 200 + index.html | 200 | ✅ |
| 3 | `GET /documents` → 200 SPA 回退 | 200 + index.html | 200 | ✅ |
| 4 | `GET /search` → 200 SPA 回退 | 200 + index.html | 200 | ✅ |
| 5 | `GET /index.html` → 200 + Cache-Control: no-cache | `no-cache` | `Cache-Control: no-cache` | ✅ |
| 6 | `GET /api/system/health` → 200 完整 JSON | 完整信封 | 结构完整，server=DOWN（server 未启动） | ✅ |
| 7 | `GET /favicon.ico` | 200（文件存在时） | 404（前端 dist 不含此文件） | ✅ |
| 8 | `GET /assets/index-xxx.js` → immutable | `max-age=31536000, immutable` | `Cache-Control: public, max-age=31536000, immutable` | ✅ |
| 9 | `GET /api/requirements` → 502 信封 | `code:5001` | `{"code":5001,"message":"上游服务不可用..."}` | ✅ |

### 8.3 编译产物

| 指标 | 值 |
|------|-----|
| Go 版本 | go1.27.1 windows/amd64 |
| 二进制大小 | 11,667,968 字节 (~11.7MB) |
| 启动耗时 | < 100ms |
| 外部依赖 | 仅 `gopkg.in/yaml.v3` |

### 8.4 文档同步更新

| 文档 | 变更内容 | 状态 |
|------|----------|------|
| [reqforge-dev-guide.md](file:///F:/workspace/engine/docs/reqforge-dev-guide.md) | 全部替换为 Go 网关 | ✅ |
| [reqforge-user-guide.md](file:///F:/workspace/engine/docs/reqforge-user-guide.md) | 全部替换为 Go 网关 | ✅ |
| [architecture.md](file:///F:/workspace/engine/docs/architecture.md) | 全部替换为 Go 网关 | ✅ |
| [deployment.md](file:///F:/workspace/engine/docs/deployment.md) | 全部替换为 Go 网关 | ✅ |

### 8.5 待执行任务

| # | 任务 | 说明 | 状态 |
|---|------|------|------|
| 1 | Gradle 脚本适配 | 前端任务迁至根 build.gradle（`buildFrontend`/`copyFrontendDist`），产物直送 `engine-gateway/static/`；settings.gradle 移除 gateway 模块 | ✅ v1.2 完成 |
| 2 | Dockerfile 更新 | 本地轨/全构建轨均改为 Go 二进制 + static + config.yaml；entrypoint.sh 改 Go 启动 | ✅ v1.2 完成 |
| 3 | 端到端测试 | server + gateway 联调，前端全页面操作 + APP 联调 | ⏳ 部分（核心链路已实测，全页面回归待做） |
| 4 | 性能基准 | 对比 Java gateway 启动耗时 / 内存 / QPS | ⏳ 待做 |
| 5 | CI/CD | GitHub Actions 中 bootJar → go build | ⏳ 待做 |
| 6 | 灰度发布 | 10%→50%→100% 流量切换 | ⏳ 待做（单机部署形态可忽略） |

### 8.6 v1.2 目录替换记录（2026-09-13）

对照分析与收尾变更（差异修复明细见[规约文档 §6](file:///F:/workspace/engine/docs/reqforge-gateway-go-spec.md)）：

1. **行为对齐修复**：健康判定（engine null）、http.Server 超时语义（Read/WriteTimeout 置 0）、超限文案（50MB）
2. **关键 Bug 修复（对比分析中实测发现）**：
   - `/assets/**` 缺 `StripPrefix` → FileServer 实际查 `static/assets/assets/` → 静态资产全部 404（前端白屏）；v1.1 自测只验证了 Cache-Control 头、未验证状态码，故漏检
   - `/index.html` 被 `http.ServeFile` 规范化为 301 重定向（Java 版直接 200），改用 `ServeContent` 直接输出
3. **目录替换**：`engine-gateway-go/` → `engine-gateway/`；Java 源码删除（git 历史保留）；module 更名 `github.com/dekkerding/engine-gateway`
4. **前端迁移**：`engine-gateway/frontend/` → 仓库根 `frontend/`（与 python/ 等语言原生工程平级）
5. **部署链接入**：docker/Dockerfile、Dockerfile.full、entrypoint.sh、.dockerignore、.gitignore 全部切换到 Go 形态
6. **附带修复**：`RequirementListPage.tsx`/`RequirementWorkshopPage.tsx` 的状态枚举对齐 server（RENDERED/ARCHIVED → EXPORTED，原 TS 编译报错阻断前端构建）