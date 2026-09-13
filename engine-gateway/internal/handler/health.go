package handler

import (
	"encoding/json"
	"log"
	"net/http"
	"sync"
	"sync/atomic"
	"time"

	"github.com/dekkerding/engine-gateway/internal/model"
)

type HealthHandler struct {
	upstreamHealthURL string
	failureThreshold  int
	client            *http.Client

	mu                sync.RWMutex
	serverUp          bool
	consecutiveFails  atomic.Int32
	engineSection     interface{}
	documentsSection  interface{}
	lastError         string
	lastSuccessAt     *time.Time
}

func NewHealthHandler(upstreamBaseURL string, failureThreshold int, probeInterval time.Duration) *HealthHandler {
	h := &HealthHandler{
		upstreamHealthURL: upstreamBaseURL + "/system/health",
		failureThreshold:  failureThreshold,
		client: &http.Client{
			Timeout: 5 * time.Second,
		},
		lastError: "尚未完成首次探测",
	}

	go h.probeLoop(probeInterval)
	return h
}

func (h *HealthHandler) probeLoop(interval time.Duration) {
	ticker := time.NewTicker(interval)
	defer ticker.Stop()

	h.probe()
	for range ticker.C {
		h.probe()
	}
}

func (h *HealthHandler) probe() {
	resp, err := h.client.Get(h.upstreamHealthURL)
	if err != nil {
		h.recordFailure(err.Error())
		return
	}
	defer resp.Body.Close()

	var envelope struct {
		Code int         `json:"code"`
		Data interface{} `json:"data"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&envelope); err != nil {
		h.recordFailure("JSON 解析失败: " + err.Error())
		return
	}
	if envelope.Code != 0 {
		h.recordFailure("健康接口返回异常信封")
		return
	}

	data, ok := envelope.Data.(map[string]interface{})
	if !ok {
		h.recordFailure("data 段结构异常")
		return
	}

	now := time.Now()
	h.mu.Lock()
	recovered := !h.serverUp
	h.serverUp = true
	h.consecutiveFails.Store(0)
	h.engineSection = data["engine"]
	h.documentsSection = data["documents"]
	h.lastSuccessAt = &now
	h.lastError = ""
	h.mu.Unlock()

	// 转好要快：一次成功立即 UP；只在恢复（DOWN→UP 翻转）时记日志，避免每 10s 刷屏
	if recovered {
		log.Printf("上游 engine-server 探测恢复: UP")
	}
}

// recordFailure 记一次失败：只有连续凑满阈值才转 DOWN（转坏要慢，容忍重启窗口）。
// 日志节奏与 Java 版对齐：未达阈值逐次 info，达阈值翻转时 warn 一次。
func (h *HealthHandler) recordFailure(reason string) {
	fails := h.consecutiveFails.Add(1)
	h.mu.Lock()
	defer h.mu.Unlock()
	h.lastError = reason
	if fails >= int32(h.failureThreshold) {
		if h.serverUp {
			h.serverUp = false
			log.Printf("上游 engine-server 连续 %d 次探测失败，标记 DOWN: %s", fails, reason)
		}
	} else {
		log.Printf("上游探测失败（%d/%d）: %s", fails, h.failureThreshold, reason)
	}
}

func (h *HealthHandler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	h.mu.RLock()
	serverUp := h.serverUp
	engineSection := h.engineSection
	docsSection := h.documentsSection
	lastError := h.lastError
	var lastSuccessStr string
	if h.lastSuccessAt != nil {
		lastSuccessStr = h.lastSuccessAt.Format(time.RFC3339)
	}
	h.mu.RUnlock()

	gateway := map[string]interface{}{
		"application": "engine-gateway",
		"status":      "UP",
	}

	server := map[string]interface{}{
		"application": "engine-server",
		"status":      boolStatus(serverUp),
		"lastError":   nilLastError(lastError),
	}
	if h.lastSuccessAt != nil {
		server["lastSuccessAt"] = lastSuccessStr
	}

	engine := buildEngineSection(engineSection)

	result := map[string]interface{}{
		"status":    overallStatus(serverUp, engine),
		"gateway":   gateway,
		"server":    server,
		"engine":    engine,
		"documents": docsSection,
	}

	w.Header().Set("Content-Type", "application/json;charset=utf-8")
	json.NewEncoder(w).Encode(model.Ok(result))
}

func boolStatus(up bool) string {
	if up {
		return "UP"
	}
	return "DOWN"
}

func nilLastError(err string) interface{} {
	if err == "" {
		return nil
	}
	return err
}

func buildEngineSection(raw interface{}) interface{} {
	if raw == nil {
		return nil
	}
	m, ok := raw.(map[string]interface{})
	if !ok {
		return nil
	}

	okVal, _ := m["ok"].(bool)
	degradedVal, _ := m["degraded"].(bool)

	status := "UP"
	if !okVal {
		status = "DOWN"
	} else if degradedVal {
		status = "DEGRADED"
	}

	return map[string]interface{}{
		"status": status,
		"detail": m,
	}
}

// overallStatus 整链路判定，与 Java 版 HealthController.overallStatus 逐条对齐：
// server DOWN → DOWN（链路断裂）；引擎段缺失（null）视为"状态未知"，不拖垮整体 → UP；
// 引擎存在但非 UP（DEGRADED/DOWN）→ DEGRADED；否则 UP。
func overallStatus(serverUp bool, engine interface{}) string {
	if !serverUp {
		return "DOWN"
	}
	if engine == nil {
		return "UP"
	}
	engineMap, ok := engine.(map[string]interface{})
	if !ok {
		return "DOWN"
	}
	engineStatus, _ := engineMap["status"].(string)
	if engineStatus != "UP" {
		return "DEGRADED"
	}
	return "UP"
}