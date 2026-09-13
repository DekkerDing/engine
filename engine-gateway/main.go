package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"github.com/dekkerding/engine-gateway/internal/config"
	"github.com/dekkerding/engine-gateway/internal/handler"
	"github.com/dekkerding/engine-gateway/internal/middleware"
)

func main() {
	configPath := flag.String("config", "config.yaml", "config file path")
	flag.Parse()

	cfg, err := config.Load(*configPath)
	if err != nil {
		log.Fatalf("failed to load config: %v", err)
	}

	staticHandler := handler.NewStaticHandler(cfg.Static.Dir)

	proxyHandler, err := handler.NewProxyHandler(
		cfg.Upstream.BaseURL,
		cfg.Upstream.ConnectTimeout,
		cfg.Upstream.ReadTimeout,
		cfg.Upstream.MaxBodyBytes,
	)
	if err != nil {
		log.Fatalf("failed to create proxy handler: %v", err)
	}

	healthHandler := handler.NewHealthHandler(
		cfg.Upstream.BaseURL,
		cfg.Health.FailureThreshold,
		cfg.Health.ProbeInterval,
	)

	mux := http.NewServeMux()

	// 1. health exact match (higher priority than proxy wildcard)
	mux.Handle("GET /api/system/health", healthHandler)

	// 2. API reverse proxy (StripPrefix + ReverseProxy)
	mux.Handle("/api/", http.StripPrefix("/api", proxyHandler))

	// 3. assets with long cache
	// 【关键】StripPrefix 不可省：FileServer 的根已是 static/assets，若不剥掉
	// URL 里的 /assets 前缀，会去 static/assets/assets/ 下找文件 → 404 白屏
	mux.Handle("/assets/", middleware.AssetCacheHeader(
		http.StripPrefix("/assets", staticHandler.FileServer("assets", "")),
	))

	// 4. entry files no-cache
	mux.HandleFunc("GET /index.html", staticHandler.ServeIndex)
	mux.HandleFunc("GET /favicon.ico", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cache-Control", "no-cache")
		http.ServeFile(w, r, cfg.Static.Dir+"/favicon.ico")
	})

	// 5. actuator 兼容端点：容器 HEALTHCHECK 探测口（docker/Dockerfile curl :8090/actuator/health）。
	// 与 Java 版 Spring Boot actuator 语义一致：网关自身能响应即 UP（不反映上游状态）。
	mux.HandleFunc("GET /actuator/health", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json;charset=utf-8")
		w.Write([]byte(`{"status":"UP"}`))
	})

	// 6. SPA fallback (catch-all)
	mux.HandleFunc("/", staticHandler.SpaFallback())

	addr := fmt.Sprintf(":%d", cfg.Server.Port)
	log.Printf("engine-gateway starting on %s", addr)
	log.Printf("upstream: %s", cfg.Upstream.BaseURL)
	log.Printf("static dir: %s", cfg.Static.Dir)

	srv := &http.Server{
		Addr:    addr,
		Handler: logMiddleware(mux),
		// 【关键】Read/WriteTimeout 置 0（不限）：它们是"从请求头读完起算"的绝对期限，
		// 会掐断总时长超过 60s 的流式响应/慢推理。Java 版 OkHttp readTimeout 是
		// "每次读到数据即续期"（见 application.yml 注释），首字节超时已由 Transport 的
		// ResponseHeaderTimeout（=read_timeout）对齐；连接空闲由 IdleTimeout 管。
		ReadTimeout:  0,
		WriteTimeout: 0,
		IdleTimeout:  120 * time.Second,
	}

	// 优雅停机：SIGINT/SIGTERM → srv.Shutdown 等待在途请求收尾（entrypoint.sh 的
	// trap TERM 编程依赖此行为；Java 版由 Spring Boot shutdown hook 等价提供）
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	errCh := make(chan error, 1)
	go func() {
		if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			errCh <- err
		}
	}()

	select {
	case err := <-errCh:
		log.Fatalf("server stopped: %v", err)
	case <-ctx.Done():
		log.Printf("收到停止信号，开始优雅停机（宽限 10s）...")
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		if err := srv.Shutdown(shutdownCtx); err != nil {
			log.Printf("优雅停机未完成（宽限期到），强制退出: %v", err)
		} else {
			log.Printf("优雅停机完成")
		}
	}
}

func logMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !strings.HasPrefix(r.URL.Path, "/assets/") {
			log.Printf("%s %s", r.Method, r.URL.Path)
		}
		next.ServeHTTP(w, r)
	})
}