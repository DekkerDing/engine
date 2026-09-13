#!/usr/bin/env bash
# ============================================================
# entrypoint.sh — 单 JAR 合体进程入口（PID 1）
# ------------------------------------------------------------
# 【职责】（对应 spec: single-jar-deployment/单进程容器）
#   1. 前置检查（jar 与 python 脚本目录就位、数据卷可写）
#   2. 导出运行环境（模型离线开关、HF 镜像）
#   3. exec 启动唯一应用进程 java（静态资源 + /api/** + actuator
#      + Python 向量化子进程，全部由这一个进程承载）
#
# 【优雅停机 · exec 模式说明】旧双进程时代 entrypoint 用「后台起双进程 +
#   wait -n + trap 转发 SIGTERM」托管；单 JAR 合体后只剩一个应用进程，
#   改用 exec java 直接把 PID 1 让给 JVM——SIGTERM 由 JVM 直收（无需
#   trap 转发），触发 Spring shutdown hook → PythonProcessLauncher.stop()
#   → Python 子进程连带优雅终止；进程退出码原样透传给容器（崩溃路径
#   docker inspect 可见真实退出码）。JVM 在 PID 1 下同样响应 SIGTERM
#   （shutdown hooks 与 PID 无关），这是官方 JVM 镜像的标准做法。
#
# 【Java 被强杀时 Python 子进程会孤儿吗】SIGKILL/OOM 场景 shutdown hook
#   不会跑，Python 子进程确实短暂孤儿——但 PID 1（java）已死意味着容器
#   本身退出，内核会清掉该 PID 命名空间内的全部进程，不存在跨容器残留。
#
# 【数据卷】全部有状态数据统一落 /app/data（SQLite、上传文档、Lucene
#   索引），容器删除重建后挂同一卷即可恢复检索能力。
# ============================================================
set -u  # 引用未定义变量即报错

# ---------- 常量与可调参数 ----------
APP_DIR="/app"
DATA_DIR="${APP_DIR}/data"
SERVER_JAR="${APP_DIR}/engine-server.jar"
PYTHON_DIR="${APP_DIR}/python"

# JVM 参数可经环境变量覆盖
JAVA_OPTS_SERVER="${JAVA_OPTS_SERVER:--Xms256m -Xmx768m}"

# ---------- 工具函数 ----------
log() { echo "[entrypoint] $*"; }

# ---------- 前置检查 ----------
[ -f "$SERVER_JAR" ] || { log "错误：找不到 $SERVER_JAR";  exit 1; }
[ -f "${PYTHON_DIR}/server_stdio.py" ] || { log "错误：找不到 python 脚本目录 ${PYTHON_DIR}"; exit 1; }
mkdir -p "$DATA_DIR"

# 模型已烘进镜像（/app/models）——离线模式既满足"断网首启可用"，
# 又防止运行期偷偷联网探测拖慢启动；权重缺失时会显式进入哈希降级而非静默下载
# SENTENCE_TRANSFORMERS_HOME 管文本模型；HF_HOME 管 transformers 系（CLIP）——
# 两者共享 /app/models/hub 的 huggingface_hub 布局（见 download_models.py 注释）
export SENTENCE_TRANSFORMERS_HOME="${SENTENCE_TRANSFORMERS_HOME:-/app/models}"
export HF_HOME="${HF_HOME:-/app/models}"
export HF_HUB_OFFLINE="${HF_HUB_OFFLINE:-1}"
export HF_ENDPOINT="${HF_ENDPOINT:-https://hf-mirror.com}"

# ---------- 启动唯一的 java 进程（PID 1 让渡） ----------
# Lucene 索引显式指进数据卷：application.yml 默认 lucene-index 是 CWD 相对
# 路径（会落 /app/lucene-index 出卷）；sqlite/documents 两项默认已是
# data/ 前缀（/app/data/...）天然入卷，无需覆盖
log "启动单 JAR 合体进程（java，:8090 唯一端口）..."
cd "$APP_DIR"
exec java $JAVA_OPTS_SERVER -jar "$SERVER_JAR" \
  --engine.lucene.index-path="${DATA_DIR}/lucene-index"
