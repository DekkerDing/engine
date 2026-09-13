# ============================================================
# Dockerfile — 本地构建轨（日常迭代）
# ------------------------------------------------------------
# 【前提】先在本机完成两个 jar 的构建（前端产物已内嵌 gateway jar）：
#     ./gradlew :engine-server:bootJar :engine-gateway:bootJar
# 【构建】（在仓库根目录执行，构建上下文 = 仓库根）：
#     docker build -f docker/Dockerfile -t text-vector-engine:local .
# 【运行】：
#     docker run -d -p 8090:8090 -v engine-data:/app/data \
#          --name engine text-vector-engine:local
#
# 【分层策略】按"变更频率从低到高"排序，最大化层缓存复用：
#     系统依赖(几乎不变) → pip 依赖(很少变) → 模型权重(很少变)
#     → 应用 jar(每次迭代变)  ← 只有这层会因改代码而重建
# 模型层约 600MB、torch 层约 900MB，放前面使日常迭代构建秒级完成。
#
# 【运行时无 Node】镜像内没有 node/npm——前端产物已编译为静态文件
# 内嵌 engine-gateway.jar（spec: 运行时镜像不含 Node）。
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

# 中文日志/文件名兜底：容器内统一 UTF-8（对应 gateway-routing/全链路中文编码）
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

# ---------- 第 4 层：入口脚本（低频变更，置于 jar 前） ----------
COPY docker/entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh

# ---------- 第 5 层：应用 jar（日常迭代只重建这层） ----------
COPY engine-server/build/libs/engine-server.jar /app/engine-server.jar
COPY engine-gateway/build/libs/engine-gateway.jar /app/engine-gateway.jar

# 对外仅发布网关端口；server(:8081) 只在容器内回环，永不 EXPOSE（spec: 单容器全栈交付）
EXPOSE 8090
# 有状态数据统一目录（SQLite/上传文档/Lucene 索引），挂卷持久化
VOLUME /app/data

# 编排器健康探测：探测对外入口 gateway；start-period 给首次模型加载留足时间
HEALTHCHECK --interval=30s --timeout=5s --start-period=120s --retries=3 \
    CMD curl -sf http://127.0.0.1:8090/actuator/health || exit 1

ENTRYPOINT ["/app/entrypoint.sh"]
