#!/usr/bin/env bash
# ============================================================
# entrypoint.sh — 容器内全栈进程编排（PID 1）
# ------------------------------------------------------------
# 【职责】（对应 spec: container-deployment/容器内进程编排）
#   1. 按依赖顺序启动：engine-server 先起 → 等其 /actuator/health 就绪
#      → engine-gateway 再起（网关在业务服务健康后对外可用）
#   2. 优雅停机：trap SIGTERM/SIGINT，转发给 server(Java) 与 gateway(Go) 进程；
#      engine-server 的 shutdown hook 会连带优雅终止其 Python 子进程
#   3. 不静默带病运行：任一子进程退出 → 容器随之以非零码退出，
#      便于编排器（docker restart / k8s）感知并重启
#
# 【为什么两个应用进程都后台起、主脚本 wait？】
#   "gateway 前台托管"的本质是 entrypoint（PID 1）持续存活不退出。
#   两个进程均后台启动后用 wait -n 监听任一退出——既保住 PID 1 的
#   前台语义，又能同时感知 server 崩溃（若只 wait gateway，server 死了
#   网关还在空转，违反"关键进程崩溃即容器退出"）。
#
# 【数据卷】全部有状态数据统一落 /app/data（SQLite、上传文档、Lucene
#   索引），容器删除重建后挂同一卷即可恢复检索能力。
# ============================================================
set -u  # 引用未定义变量即报错（不用 -e：wait 被信号打断返回非零是正常路径）

# ---------- 常量与可调参数 ----------
APP_DIR="/app"
DATA_DIR="${APP_DIR}/data"
SERVER_JAR="${APP_DIR}/engine-server.jar"
# Go 网关：单二进制，工作目录须在 /app（config.yaml 与 static/ 都按相对路径读取）
GATEWAY_BIN="${APP_DIR}/engine-gateway"

# 健康等待：首次启动模型从盘加载可能要 30-60s+，给足 180s
HEALTH_URL="http://127.0.0.1:8081/actuator/health"
HEALTH_TIMEOUT_SECONDS="${HEALTH_TIMEOUT_SECONDS:-180}"
HEALTH_INTERVAL_SECONDS="${HEALTH_INTERVAL_SECONDS:-2}"

# 优雅停机宽限：SIGTERM 后等待子进程（server 连带其 Python 子进程）退出的上限
SHUTDOWN_GRACE_SECONDS="${SHUTDOWN_GRACE_SECONDS:-30}"

# JVM 参数可经环境变量覆盖（仅 server 需要；网关是 Go 二进制，无 JVM）
JAVA_OPTS_SERVER="${JAVA_OPTS_SERVER:--Xms256m -Xmx768m}"

SERVER_PID=""
GATEWAY_PID=""

# ---------- 工具函数 ----------
log() { echo "[entrypoint] $*"; }

# 优雅停全组：TERM → 宽限等待 → 超时 KILL
# server 优先（它持有 Python 子进程与 SQLite/Lucene 文件句柄）
shutdown_all() {
  log "正在停止全部进程（SIGTERM，宽限 ${SHUTDOWN_GRACE_SECONDS}s）..."
  [ -n "$GATEWAY_PID" ] && kill -TERM "$GATEWAY_PID" 2>/dev/null
  [ -n "$SERVER_PID" ] && kill -TERM "$SERVER_PID" 2>/dev/null

  deadline=$(( $(date +%s) + SHUTDOWN_GRACE_SECONDS ))
  for pid in "$GATEWAY_PID" "$SERVER_PID"; do
    [ -z "$pid" ] && continue
    while kill -0 "$pid" 2>/dev/null; do
      if [ "$(date +%s)" -ge "$deadline" ]; then
        log "进程 $pid 未在宽限期内退出，强制 KILL"
        kill -KILL "$pid" 2>/dev/null
        break
      fi
      sleep 1
    done
  done
  log "全部进程已停止"
}

# trap 在 wait 期间到达：先停全组，再由主流程以 exit_code 退出
on_signal() {
  log "收到停止信号，开始优雅停机"
  shutdown_all
  SIGNALLED=1
}

# ---------- 前置检查 ----------
[ -f "$SERVER_JAR" ]  || { log "错误：找不到 $SERVER_JAR";  exit 1; }
[ -x "$GATEWAY_BIN" ] || { log "错误：找不到 $GATEWAY_BIN（或无执行权限）"; exit 1; }
mkdir -p "$DATA_DIR"

# 模型已烘进镜像（/app/models）——离线模式既满足"断网首启可用"，
# 又防止运行期偷偷联网探测拖慢启动；权重缺失时会显式进入哈希降级而非静默下载
# SENTENCE_TRANSFORMERS_HOME 管文本模型；HF_HOME 管 transformers 系（CLIP）——
# 两者共享 /app/models/hub 的 huggingface_hub 布局（见 download_models.py 注释）
export SENTENCE_TRANSFORMERS_HOME="${SENTENCE_TRANSFORMERS_HOME:-/app/models}"
export HF_HOME="${HF_HOME:-/app/models}"
export HF_HUB_OFFLINE="${HF_HUB_OFFLINE:-1}"
export HF_ENDPOINT="${HF_ENDPOINT:-https://hf-mirror.com}"

SIGNALLED=0
trap on_signal TERM INT

# ---------- 1. 启动 engine-server（:8081，容器内回环，不对外发布）----------
log "启动 engine-server（python 子进程随其拉起）..."
cd "$APP_DIR"
java $JAVA_OPTS_SERVER -jar "$SERVER_JAR" \
  --engine.lucene.index-path="${DATA_DIR}/lucene-index" &
SERVER_PID=$!
log "engine-server PID: $SERVER_PID"

# ---------- 2. 等待 server 健康（未就绪则不带货上网关）----------
log "等待 server 健康（最长 ${HEALTH_TIMEOUT_SECONDS}s）：GET $HEALTH_URL"
waited=0
until curl -sf -m 3 "$HEALTH_URL" >/dev/null 2>&1; do
  # server 进程等待期间就死了（如端口冲突/数据库损坏）→ 立即失败，不带病继续
  if ! kill -0 "$SERVER_PID" 2>/dev/null; then
    log "错误：server 进程在健康等待期间退出"
    wait "$SERVER_PID"
    log "server 退出码: $?（容器终止）"
    exit 1
  fi
  if [ "$waited" -ge "$HEALTH_TIMEOUT_SECONDS" ]; then
    log "错误：server 健康等待超时（${HEALTH_TIMEOUT_SECONDS}s），容器终止"
    shutdown_all
    exit 1
  fi
  sleep "$HEALTH_INTERVAL_SECONDS"
  waited=$(( waited + HEALTH_INTERVAL_SECONDS ))
done
log "server 已就绪"

# ---------- 3. 启动 engine-gateway（:8090，容器唯一对外端口）----------
log "启动 engine-gateway（Go 二进制，SIGTERM 走优雅停机）..."
cd "$APP_DIR"   # 网关按相对路径读 config.yaml 与 static/
"$GATEWAY_BIN" &
GATEWAY_PID=$!
log "engine-gateway PID: $GATEWAY_PID"

# ---------- 4. 托管等待：任一子进程退出 → 全组退出 ----------
# wait -n：任一子进程退出即返回（bash ≥ 4.3，jammy 为 5.1 满足）
wait -n
EXIT_CODE=$?

if [ "$SIGNALLED" -eq 1 ]; then
  # 优雅停机路径（docker stop）：shutdown_all 已在 trap 中完成
  log "优雅停机完成，容器退出"
  exit 0
fi

# 崩溃路径：不静默带病运行——停掉幸存进程，以崩溃者退出码（非零）退出容器
log "检测到进程异常退出（码 $EXIT_CODE），停止其余进程并使容器非零退出"
shutdown_all
exit "$EXIT_CODE"
