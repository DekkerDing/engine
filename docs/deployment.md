# 部署手册（deployment）

> 目标读者：需要把"文本向量化检索 MVP"跑起来的任何人。
> 两条路径：**Docker 主路径**（推荐，目标机只需装 Docker）与 **裸机备选路径**（预装 JDK 8 + Python）。
> 全文约定：仓库根指 `engine/`，`$` 开头为命令行（Windows 用 Git Bash / PowerShell 均可）。

---

## 1. 部署形态总览

**单 JAR 合体**：单容器、单 Java 进程、一个对外端口 8090 承载全部入口能力：

```
浏览器 ──HTTP──▶ engine-server (:8090，唯一进程、唯一端口)
                   │  ├─ React 静态资源 + SPA 回退（jar 内 BOOT-INF/classes/static/）
                   │  ├─ /api/** 业务 API（控制器映射直接带前缀）
                   │  ├─ /actuator/health（容器 HEALTHCHECK 探测口）
                   │  └─ Py4J(环回:25335)/stdio──▶ Python 引擎子进程
                   │                               (bge-small-zh-v1.5, 512维)
```

| 组件 | 端口 | 说明 |
|------|------|------|
| engine-server（合体） | **8090**（对外唯一） | fat jar：API + 前端产物 + actuator + Python 子进程管理 |
| Python 引擎 | 25335（Py4J 环回） | server 的子进程，随其启停 |
| ~~engine-gateway~~ | ~~8090~~ | **已退役**（Go 网关职能并入 server，:8081 内部端口随之消失） |

**运行时镜像不含 Node/npm 和 Go 工具链**——前端已编译进 jar 的 `static/`，运行时只需 JRE 8 + Python 3。

> 合体动机与决策见 [reqforge-single-jar-design.md](file:///F:/workspace/engine/docs/reqforge-single-jar-design.md)，
> 部署验收按 [reqforge-single-jar-spec.md](file:///F:/workspace/engine/docs/reqforge-single-jar-spec.md) 第 7 节清单逐项打勾。

---

## 2. Docker 主路径

### 2.1 前提

- 目标机安装 Docker（Windows 11：Docker Desktop + WSL2；Linux：docker engine ≥ 20.10）
- 磁盘：镜像约 4.6GB（含 torch ~900MB + 模型 ~2.1GB——photo-semantic-search 后新增 bge-reranker-base ~1.1GB），另留数据卷空间
- **构建机**（本地轨）额外需要：JDK 8、Gradle（仓库自带 wrapper）、Node 22、Python 3.13（打包 Python 脚本用）——**无需 Go**
- 首次构建需联网：pip 走 PyPI/镜像，模型走 hf-mirror；**运行期完全离线可用**

### 2.2 本地构建轨（日常迭代）

```bash
# 1. 构建单 JAR 合体 fat jar（前端构建与 python 脚本打包被自动拉起）
$ ./gradlew :engine-server:bootJar

# 2. 构建镜像（在仓库根执行；构建上下文 = 仓库根，受 .dockerignore 约束）
$ docker build -f docker/Dockerfile -t text-vector-engine:local .

# （可选）国内加速 pip：
$ docker build -f docker/Dockerfile \
    --build-arg PYPI_INDEX_URL=https://mirrors.aliyun.com/pypi/simple \
    -t text-vector-engine:local .
```

分层策略（变更频率低→高）：系统依赖 → pip 依赖 → 模型权重 → entrypoint+python 脚本 → **jar**。
日常改代码重构建只重建最后一层，秒级完成。

### 2.3 全构建轨（可复现发布）

```bash
# 只需要 Docker 与源码：容器内完成 npm 前端构建 + gradle bootJar
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
$ curl http://127.0.0.1:8090/api/system/health   # 合体信封 {status, gateway, server, engine, documents}
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
$ ./gradlew :engine-server:bootJar
$ docker build -f docker/Dockerfile -t text-vector-engine:local .
$ docker stop engine && docker rm engine
$ docker run -d --name engine -p 8090:8090 -v engine-data:/app/data text-vector-engine:local

# 回滚：换回旧 tag（或旧 image id）重跑上面两条即可
# 数据兼容性：SQLite schema 由 DatabaseMigrator 版本化前滚，只增不改列，旧数据向后兼容
# 代码级回滚：变更无数据迁移、无契约变更，单 commit revert 即完全回滚
```

### 2.7 CI 流水线（Jenkins）

仓库根 `Jenkinsfile`（声明式）：Frontend → Test → Package → Image → Publish。
agent 需 JDK 8 + Node 22 + Docker（**无 Go**）；镜像 tag 含 `${BUILD_NUMBER}` 可追溯；
`REGISTRY` 环境变量配置后自动推送，未配置则跳过 Publish。

---

## 3. 裸机备选路径（无 Docker）

### 3.1 前提

| 组件 | 版本 | 用途 |
|------|------|------|
| JDK | 8（1.8.0_301 实测） | 运行 Spring Boot 合体应用 |
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
$ ./gradlew :engine-server:bootJar
# 产物：engine-server/build/libs/engine-server.jar（含前端产物与 python 脚本资产）
```

### 3.3 启动与停止

```bash
# 开发态（推荐）
$ ./gradlew :engine-server:bootRun

# 生产态：从 engine-server/ 目录启动（./python 探测命中脚本目录）
$ cd engine-server
$ java -jar build/libs/engine-server.jar
# 或从任意目录，显式指定脚本目录：
$ java -jar engine-server/build/libs/engine-server.jar --engine.python.home=engine-server/python
```

**停止**：`Ctrl+C`（触发 JVM shutdown hook，**Python 子进程会被连带优雅终止**）。
IDE 用户用 Stop 按钮（等价发 SIGTERM，同样走 hook）。

> ⚠️ **Windows 注意**：`taskkill /F`（等价 kill -9）**不会**执行 shutdown hook，
> 会遗留 Python 孤儿进程——用 `tasklist | findstr python` 检查并手动清理。
> 详见下方故障排查表第 6 条。

---

## 4. 故障排查表

| # | 症状 | 原因 | 处置 |
|---|------|------|------|
| 1 | 启动报 `Lucene 索引初始化失败` | 上次异常退出残留 `lucene-index/write.lock`，或已有实例在跑 | 确认无 java 进程后删除 `lucene-index/write.lock` 重启 |
| 2 | 端口被占但 `netstat -ano \| findstr 8090` 查不到 | Windows 端口处于 **Bound**（非 LISTENING）状态，netstat 默认显示不全；另一种形态：出站连接的**临时源端口**恰被分派 8090（本机动态端口范围 1024-15000 含 8090，`netsh int ipv4 show dynamicport tcp` 可查），TIME_WAIT 撞 Tomcat bind 报 PortInUse | 用 PowerShell `Get-NetTCPConnection -LocalPort 8090` 查 PID 后 `taskkill /F /PID <pid>`；TIME_WAIT 形态等 60-120s 自散后重试即可（7.1 终验实测过一次）；根治可收敛动态端口范围 `netsh int ipv4 set dynamicport tcp 49152 16384`；实在不行换端口（改 `application.yml` 的 `server.port`） |
| 3 | 启动报 `Py4J 通道 ... 未就绪` 或 `未找到 Python 脚本目录` | 25335 被占用 / Python 环境损坏 / 启动目录不对（`./python` 探测落空） | `Get-NetTCPConnection -LocalPort 25335` 排查占用；`python -c "import torch"` 验证依赖；从 `engine-server/` 目录启动或 `--engine.python.home=` 显式指定 |
| 4 | bootRun 日志中文乱码 | Windows 默认 GBK | 构建脚本已统一 UTF-8；若终端仍乱码，Git Bash 执行 `export LANG=zh_CN.UTF-8`，或 IDEA `Help→Edit Custom VM Options` 加 `-Dfile.encoding=UTF-8` |
| 5 | 页面 404（API 正常） | jar 内缺前端产物（构建时前端任务链被跳过） | 重新构建：`./gradlew :engine-server:bootJar`，解包验证 `BOOT-INF/classes/static/index.html` 存在 |
| 6 | 重启后发现两个 Python 进程 | Windows 强杀（`taskkill /F`）Java 不走 shutdown hook，Python 成孤儿 | `tasklist \| findstr python` 后逐个 `taskkill /F /PID`；正常停机请走 Ctrl+C |
| 7 | 检索结果为空/语义不命中 | min-vector-score 阈值与当前模型分布不匹配（文本默认 0.40 按 bge 标定；图片默认 0.25 按 CLIP 标定，两模态独立配置） | 查看响应 `scores` 分布调整 `engine.search.min-vector-score` / `engine.search.image-min-vector-score`；切换模型（MiniLM）必须重新摄取 |
| 8 | 切换模型键后检索报维度不匹配 | 库内向量 512 维（bge），当前查询 384 维（MiniLM） | 预期行为（维度闸门）：切回原模型，或清空文档库重新摄取 |
| 9 | 容器反复重启 | 查看 `docker logs engine`：常见为数据卷权限/磁盘满 | 确认卷可写；`docker inspect engine` 看 Health 与退出码 |
| 10 | 容器内模型加载失败进入降级 | 模型层构建不完整 / 卷挂载覆盖了 /app/models | 重建镜像确保模型层完整；若挂模型卷，确认文本模型在顶层 `models--*`、CLIP 在 `hub/models--*`（双缓存布局，见 `docker/download_models.py` 注释） |
| 11 | 图片检索命中少/不命中 | CLIP 余弦分布偏低（实测相关图 0.30~0.35），阈值 0.25 已按此标定 | 换更具体的视觉描述；命中分布可看响应 `vectorScore`；必要时调 `engine.search.image-min-vector-score` |
| 12 | 图片向量化 FAILED（errorMessage 含 CLIP/引擎） | Python 引擎不可达 / 调用超时（CLIP 模型加载失败则不同：走哈希兜底降级，COMPLETED + degraded 标志） | 修 Python 环境后重新上传该图片；降级标志的图片建议在引擎恢复后删除重传以恢复真实精度 |
| 13 | `/api/xxx` 返回 200 但内容是 HTML | 不应发生——SpaFallbackResolver 防御分支拒绝 `api/` 前缀 | 若出现说明回退逻辑被改动，对照 spec 第 2 节路由表与 `SpaFallbackResolver` 防御分支 |

---

## 5. 资源建议

| 部署规模 | 内存 | 说明 |
|----------|------|------|
| 最小可用 | 2GB | Java 堆 768m + Python（torch+文本模型 ~1.2GB）；CLIP 与重排器均惰性加载，不用图片检索/重排可不触发 |
| 舒适 | 6GB | 文本 + CLIP 双模型常驻（CLIP 首次图片请求加载 ~1GB），万级向量驻留仍有余量 |
| 照片语义检索全开 | 8GB | 重排器 bge-reranker-base 首次重排请求再加载 ~1.1GB（惰性，与 CLIP 同纪律）；千级照片导入期间 CLIP+bge 连续编码，并发度默认 2（`engine.images.import.concurrency`） |

**模型清单（photo-semantic-search 后）**：bge-small-zh-v1.5（~90MB，文本+描述路）+ MiniLM 多语备选（~470MB）+ chinese-clip-vit-base-patch16（~400MB，像素路）+ bge-reranker-base（~1.1GB，重排，惰性）——磁盘合计 ~2.1GB，`docker/download_models.py --dry-run` 可随时核对清单。重排可经 `engine.search.rerank.enabled=false` 或请求参数 `"rerank": false` 关闭（省内存/降延迟，精度换可用）。

环境变量可调（容器）：`JAVA_OPTS_SERVER`（JVM 参数，默认 `-Xms256m -Xmx768m`，见 `docker/entrypoint.sh`）。
