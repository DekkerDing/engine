# ============================================================
# Dockerfile — 本地构建轨（日常迭代）
# ------------------------------------------------------------
# 【前提】先在本机完成单 JAR 合体构建（前端产物与 python 脚本已打进 jar）：
#     ./gradlew :engine-server:bootJar
#     （buildFrontend/copyFrontendDist/packagePython 已被 bootJar 自动依赖）
# 【构建】（在仓库根目录执行，构建上下文 = 仓库根）：
#     docker build -f docker/Dockerfile -t text-vector-engine:local .
# 【运行】：
#     docker run -d -p 8090:8090 -v engine-data:/app/data \
#          --name engine text-vector-engine:local
#
# 【单 JAR 合体】一个 fat jar、一个 Java 进程、一个 8090 端口承载全部：
#   REST API(/api/**) + 前端静态资源(BOOT-INF/classes/static/) + SPA 回退
#   + actuator/health + Python 向量化子进程。原 Go 网关进程与 :8081 内部
#   端口随合体消失（对外 HTTP 契约逐字节不变）。
#
# 【分层策略】按"变更频率从低到高"排序，最大化层缓存复用：
#     系统依赖(几乎不变) → pip 依赖(很少变) → 模型权重(很少变)
#     → 入口脚本+python 脚本(低频) → 应用 jar(每次迭代变)
# 模型层约 600MB、torch 层约 900MB，放前面使日常迭代构建秒级完成。
#
# 【运行时无 Node / 无 Go 工具链】前端已编译进 jar 的 static/，镜像内没有
# node/npm，也没有 golang——运行时只需要 JRE + python3（spec: 运行时镜像
# 不含 Node）。
# ============================================================

# 基础镜像：temurin 8-jre + Ubuntu 22.04 (jammy)
# 【为什么不是 alpine】alpine 的 musl libc 装不上 torch cpu 轮子（需 glibc）；
# temurin 8-jre-jammy 仍在维护期且自带 glibc（design.md D8）
FROM eclipse-temurin:8-jre-jammy

# ---------- 构建参数（可用 --build-arg 覆盖的国内加速项） ----------
# torch 必须走 cpu 专用源：PyPI 主源的 linux 轮子捆绑 CUDA 依赖（+数 GB 的
# nvidia-cudnn 等），cpu 源的轮子约 200MB 且无 GPU 依赖。本机开发实测同款
# torch 2.13.0+cpu。若有更快的 cpu 轮子镜像可覆盖此地址
ARG TORCH_INDEX_URL=https://download.pytorch.org/whl/cpu
# pypi 主源可换国内：--build-arg PYPI_INDEX_URL=https://mirrors.aliyun.com/pypi/simple
ARG PYPI_INDEX_URL=https://pypi.org/simple
# 模型下载默认走国内 HF 镜像（构建期网络出口）
ARG HF_ENDPOINT=https://hf-mirror.com

# ---------- 第 1 层：系统依赖（几乎不变） ----------
# python3/pip：向量化引擎运行时；curl：entrypoint 健康探测 + HEALTHCHECK；
# python-is-python3：让 `python` 命令指向 python3（Java 侧 engine.python.command
# 配置默认值是 python，装此包后镜像内无需改配置）
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        python3 python3-pip python-is-python3 curl \
    && rm -rf /var/lib/apt/lists/*

# 中文日志/文件名兜底：容器内统一 UTF-8（对应全链路中文编码要求）
ENV LANG=C.UTF-8 LC_ALL=C.UTF-8

# ---------- 第 2 层：pip 依赖（随 requirements.txt 变更才重建） ----------
WORKDIR /app
COPY engine-server/python/requirements.txt /app/requirements.txt
# 两步安装：先 torch（cpu 源、大而稳），再其余依赖（torch 已满足不重复下载）。
# --no-cache-dir 避免 pip 缓存驻留镜像层
RUN pip3 install --no-cache-dir torch --index-url "${TORCH_INDEX_URL}" \
    && pip3 install --no-cache-dir -r /app/requirements.txt -i "${PYPI_INDEX_URL}"

# ---------- 第 3 层：模型权重（约 1GB，模型清单不变则命中缓存） ----------
# 构建期预下载并实例化校验 → 运行期直接命中，断网首启可用
# （spec: 模型权重内置与离线运行）
# 双缓存根共用 /app/models/hub：SENTENCE_TRANSFORMERS_HOME 管文本模型，
# HF_HOME 管 transformers 系（CLIP）——布局细节见 download_models.py 注释
ENV HF_ENDPOINT=${HF_ENDPOINT}
COPY docker/download_models.py /app/download_models.py
RUN python3 /app/download_models.py /app/models
ENV SENTENCE_TRANSFORMERS_HOME=/app/models
ENV HF_HOME=/app/models

# ---------- 第 4 层：入口脚本 + Python 脚本目录（低频变更，置于 jar 前） ----------
COPY docker/entrypoint.sh /app/entrypoint.sh
# 【为什么 jar 内已有 python/ 还要 COPY 到文件系统】fat jar 把 python/ 打进
# BOOT-INF/classes/ 满足"单 jar 资产自包含"，但 PythonProcessLauncher 按文件
# 系统路径探测脚本目录（./python ← 容器内 CWD=/app 命中），不读 jar 内资源。
# 旧双进程架构同样缺这一层——Python 通道在容器内从未真正可用，本次合体补齐
COPY engine-server/python/ /app/python/
RUN chmod +x /app/entrypoint.sh

# ---------- 第 5 层：应用（日常迭代只重建这层） ----------
# 单 JAR 合体：一个 fat jar 携带 API + 前端静态产物 + python 脚本资产
COPY engine-server/build/libs/engine-server.jar /app/engine-server.jar

# 唯一端口：静态资源 + /api/** + actuator 同源提供（原网关 :8090 契约零变动）
EXPOSE 8090
# 有状态数据统一目录（SQLite/上传文档/Lucene 索引），挂卷持久化
VOLUME /app/data

# 编排器健康探测：直连本进程 actuator（路径与原网关透传端点一致，部署脚本零改动）；
# start-period 给首次模型加载留足时间
HEALTHCHECK --interval=30s --timeout=5s --start-period=120s --retries=3 \
    CMD curl -sf http://127.0.0.1:8090/actuator/health || exit 1

ENTRYPOINT ["/app/entrypoint.sh"]
