# 部署手册（deployment）

> 目标读者：需要把"文本向量化检索 MVP"跑起来的任何人。
> 两条路径：**Docker 主路径**（推荐，目标机只需装 Docker）与 **裸机备选路径**（预装 JDK 8 + Python）。
> 全文约定：仓库根指 `engine/`，`$` 开头为命令行（Windows 用 Git Bash / PowerShell 均可）。

---

## 1. 部署形态总览

单容器内三进程、一个对外端口：

```
浏览器 ──HTTP──▶ engine-gateway (:8090，容器唯一对外端口)
                   │  ├─ React 静态资源 + SPA 回退
                   │  └─ /api/** 反代（剥前缀）──▶ engine-server (:8081，仅容器内回环)
                   │                                   │
                   │                                   └─Py4J(环回:25335)/stdio──▶ Python 引擎
                   │                                                                 (bge-small-zh-v1.5, 512维)
                   └─ /actuator/health（容器 HEALTHCHECK 探测口）
```

| 组件 | 端口 | 说明 |
|------|------|------|
| engine-gateway | **8090**（对外） | 流量唯一入口，Go 静态二进制 + 前端产物在 static/ 目录 |
| engine-server | 8081（仅容器内） | 业务服务，Python 脚本内嵌 jar |
| Python 引擎 | 25335（Py4J 环回） | server 的子进程，随其启停 |

**运行时镜像不含 Node/npm 和 JDK**（gateway 用 Go 静态编译，无需 JVM）——前端已编译为静态产物由 Go 网关 serve。

---

## 2. Docker 主路径

### 2.1 前提

- 目标机安装 Docker（Windows 11：Docker Desktop + WSL2；Linux：docker engine ≥ 20.10）
- 磁盘：镜像约 4.6GB（含 torch ~900MB + 模型 ~2.1GB——photo-semantic-search 后新增 bge-reranker-base ~1.1GB），另留数据卷空间
- **构建机**（本地轨）额外需要：JDK 8、Gradle（仓库自带 wrapper）、Node 22、Python 3.13（打包 Python 脚本用）
- 首次构建需联网：pip 走 PyPI/镜像，模型走 hf-mirror；**运行期完全离线可用**

### 2.2 本地构建轨（日常迭代）

```bash
# 1. 构建 server jar + 前端 + Go 网关
$ ./gradlew :engine-server:bootJar buildFrontend copyFrontendDist
$ cd engine-gateway
$ GOOS=linux GOARCH=amd64 go build -o engine-gateway .
$ cd ..

# 2. 构建镜像（在仓库根执行；构建上下文 = 仓库根，受 .dockerignore 约束）
$ docker build -f docker/Dockerfile -t text-vector-engine:local .

# （可选）国内加速 pip：
$ docker build -f docker/Dockerfile \
    --build-arg PYPI_INDEX_URL=https://mirrors.aliyun.com/pypi/simple \
    -t text-vector-engine:local .
```

分层策略（变更频率低→高）：系统依赖 → pip 依赖 → 模型权重 → entrypoint → **jar**。
日常改代码重构建只重建最后一层，秒级完成。

### 2.3 全构建轨（可复现发布）

```bash
# 只需要 Docker 与源码：容器内完成 npm 前端构建 + gradle jar 构建
$ docker build -f docker/Dockerfile.full -t text-vector-engine:full .
```

两轨 stage2 运行时层完全一致，仅 jar 来源不同（本机 COPY vs 容器内构建），
对外行为（页面/API/检索）等价。发布建议用 full 轨打 tag 存证。

### 2.4 运行与验证

```bash
# 首次运行（命名卷持久化数据）
$ docker run -d --name engine \
    -p 8090:8090 \
    -v engine-data:/app/data \
    text-vector-engine:local

# 验证：容器健康（HEALTHCHECK 已配置，start-period 120s 给模型加载留时间）
$ docker ps --filter name=engine            # STATUS 应为 (healthy)
$ curl http://127.0.0.1:8090/api/system/health   # 三组件（网关/业务服务/Python 引擎）应 UP
```

浏览器访问 `http://127.0.0.1:8090`：仪表盘 → 文档管理上传 → 检索页查询，全流程可用。

### 2.5 数据卷与模型卷

| 卷 | 容器路径 | 内容 | 建议 |
|----|----------|------|------|
| engine-data | `/app/data` | SQLite（engine.db）、上传文档（documents/）、上传图片（images/）、Lucene 索引（lucene-index/） | **必须持久化**；重建容器数据存活 |
| （可选）模型卷 | `/app/models` | bge + MiniLM + chinese-clip + bge-reranker 权重 ~2.1GB | 多容器共享可挂卷复用；单容器直接用镜像内置层即可 |

备份：停容器后直接打包卷目录（`docker run --rm -v engine-data:/data -v $PWD:/bak alpine tar czf /bak/engine-data.tgz /data`）。

### 2.6 升级与回滚

```bash
# 升级：新镜像 tag → 替换容器（数据卷不动）
$ docker build -f docker/Dockerfile -t text-vector-engine:local .
$ docker stop engine && docker rm engine
$ docker run -d --name engine -p 8090:8090 -v engine-data:/app/data text-vector-engine:local

# 回滚：换回旧 tag（或旧 image id）重跑上面两条即可
# 数据兼容性：SQLite schema 由 DatabaseMigrator 版本化前滚，只增不改列，旧数据向后兼容
```

---

## 3. 裸机备选路径（无 Docker）

### 3.1 前提

| 组件 | 版本 | 用途 |
|------|------|------|
| JDK | 8（1.8.0_301 实测） | 运行两个 Spring Boot 应用 |
| Python | 3.10+（3.13.5 实测） | 向量化引擎 |
| Node | 22（仅构建机需要） | 前端构建（bootJar 任务链自动调用） |

```bash
# Python 依赖（阿里云 pypi 镜像；清华镜像对 jieba 源码包 403）
$ pip install -r engine-server/python/requirements.txt -i https://mirrors.aliyun.com/pypi/simple/

# 环境体检（JDK/Python/Node 三件套）
$ scripts/env-check.bat   # Windows
```

### 3.2 构建

```bash
$ ./gradlew :engine-server:bootJar buildFrontend copyFrontendDist
$ cd engine-gateway
$ go build -o engine-gateway.exe .   # Windows
$ go build -o engine-gateway .       # Linux/macOS
# 产物：engine-server/build/libs/engine-server.jar
#       engine-gateway/engine-gateway(或 .exe)
```

### 3.3 启动顺序与停止

**顺序很重要**：server 先起（含 Python 子进程加载模型 30-60s），gateway 后起（其健康探测容忍 3 次失败 ×10s）。

```bash
# 开发态（推荐）：两个终端分别
$ ./gradlew :engine-server:bootRun
$ cd engine-gateway && go run .

# 生产态：两个终端分别（或用 systemd/supervisor 托管）
$ java -jar engine-server/build/libs/engine-server.jar
$ cd engine-gateway && ./engine-gateway     # Linux
$ cd engine-gateway && .\engine-gateway.exe # Windows
```

**停止**：在对应终端 `Ctrl+C`（触发 JVM shutdown hook，**Python 子进程会被连带优雅终止**）。
IDE 用户用 Stop 按钮（等价发 SIGTERM，同样走 hook）。

> ⚠️ **Windows 注意**：`taskkill /F`（等价 kill -9）**不会**执行 shutdown hook，
> 会遗留 Python 孤儿进程——用 `tasklist | findstr python` 检查并手动清理。
> 详见下方故障排查表第 6 条。

---

## 4. 故障排查表

| # | 症状 | 原因 | 处置 |
|---|------|------|------|
| 1 | 启动报 `Lucene 索引初始化失败` | 上次异常退出残留 `lucene-index/write.lock`，或已有实例在跑 | 确认无 java 进程后删除 `engine-server/lucene-index/write.lock` 重启 |
| 2 | 端口被占但 `netstat -ano \| findstr 8090` 查不到 | Windows 端口处于 **Bound**（非 LISTENING）状态，netstat 默认显示不全 | 用 PowerShell `Get-NetTCPConnection -LocalPort 8090` 查 PID 后 `taskkill /F /PID <pid>`；实在不行换端口（改 `application.yml` 的 `server.port`） |
| 3 | 启动报 `Py4J 通道 ... 未就绪` | 25335 被占用 / Python 环境损坏 | `Get-NetTCPConnection -LocalPort 25335` 排查占用；`python -c "import torch"` 验证依赖 |
| 4 | bootRun 日志中文乱码 | Windows 默认 GBK | 构建脚本已统一 UTF-8；若终端仍乱码，Git Bash 执行 `export LANG=zh_CN.UTF-8`，或 IDEA `Help→Edit Custom VM Options` 加 `-Dfile.encoding=UTF-8` |
| 5 | 页面 404（API 正常） | Go 网关 static/ 目录缺少前端产物 | 重新构建：`./gradlew buildFrontend copyFrontendDist`，然后 `cd engine-gateway && go build .` |
| 6 | 重启后发现两个 Python 进程 | Windows 强杀（`taskkill /F`）Java 不走 shutdown hook，Python 成孤儿 | `tasklist \| findstr python` 后逐个 `taskkill /F /PID`；正常停机请走 Ctrl+C |
| 7 | 检索结果为空/语义不命中 | min-vector-score 阈值与当前模型分布不匹配（文本默认 0.40 按 bge 标定；图片默认 0.25 按 CLIP 标定，两模态独立配置） | 查看响应 `scores` 分布调整 `engine.search.min-vector-score` / `engine.search.image-min-vector-score`；切换模型（MiniLM）必须重新摄取 |
| 8 | 切换模型键后检索报维度不匹配 | 库内向量 512 维（bge），当前查询 384 维（MiniLM） | 预期行为（维度闸门）：切回原模型，或清空文档库重新摄取 |
| 9 | 容器反复重启 | 查看 `docker logs engine`：常见为数据卷权限/磁盘满 | 确认卷可写；`docker inspect engine` 看 Health 与退出码 |
| 10 | 容器内模型加载失败进入降级 | 模型层构建不完整 / 卷挂载覆盖了 /app/models | 重建镜像确保模型层完整；若挂模型卷，确认文本模型在顶层 `models--*`、CLIP 在 `hub/models--*`（双缓存布局，见 `docker/download_models.py` 注释） |
| 11 | 图片检索命中少/不命中 | CLIP 余弦分布偏低（实测相关图 0.30~0.35），阈值 0.25 已按此标定 | 换更具体的视觉描述；命中分布可看响应 `vectorScore`；必要时调 `engine.search.image-min-vector-score` |
| 12 | 图片向量化 FAILED（errorMessage 含 CLIP/引擎） | Python 引擎不可达 / 调用超时（CLIP 模型加载失败则不同：走哈希兜底降级，COMPLETED + degraded 标志） | 修 Python 环境后重新上传该图片；降级标志的图片建议在引擎恢复后删除重传以恢复真实精度 |

---

## 5. 资源建议

| 部署规模 | 内存 | 说明 |
|----------|------|------|
| 最小可用 | 2GB | server 堆 768m + gateway（Go 静态编译 ~20MB） + Python（torch+文本模型 ~1.2GB）；CLIP 与重排器均惰性加载，不用图片检索/重排可不触发 |
| 舒适 | 6GB | 文本 + CLIP 双模型常驻（CLIP 首次图片请求加载 ~1GB），万级向量驻留仍有余量 |
| 照片语义检索全开 | 8GB | 重排器 bge-reranker-base 首次重排请求再加载 ~1.1GB（惰性，与 CLIP 同纪律）；千级照片导入期间 CLIP+bge 连续编码，并发度默认 2（`engine.images.import.concurrency`） |

**模型清单（photo-semantic-search 后）**：bge-small-zh-v1.5（~90MB，文本+描述路）+ MiniLM 多语备选（~470MB）+ chinese-clip-vit-base-patch16（~400MB，像素路）+ bge-reranker-base（~1.1GB，重排，惰性）——磁盘合计 ~2.1GB，`docker/download_models.py --dry-run` 可随时核对清单。重排可经 `engine.search.rerank.enabled=false` 或请求参数 `"rerank": false` 关闭（省内存/降延迟，精度换可用）。

环境变量可调（容器）：`JAVA_OPTS_SERVER`、`HEALTH_TIMEOUT_SECONDS`、`SHUTDOWN_GRACE_SECONDS`（见 `docker/entrypoint.sh`；网关为 Go 二进制，无 JVM 参数）。