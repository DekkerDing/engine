package handler

import (
	"net/http"
	"os"
	"path/filepath"
	"strings"
)

type StaticHandler struct {
	staticDir string
	indexFile string
}

func NewStaticHandler(staticDir string) *StaticHandler {
	return &StaticHandler{
		staticDir: staticDir,
		indexFile: filepath.Join(staticDir, "index.html"),
	}
}

func (h *StaticHandler) ServeFile(path string) http.Handler {
	fullPath := filepath.Join(h.staticDir, path)
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cache-Control", "no-cache")
		http.ServeFile(w, r, fullPath)
	})
}

func (h *StaticHandler) ServeFileWithCache(path, cacheControl string) http.Handler {
	fullPath := filepath.Join(h.staticDir, path)
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cache-Control", cacheControl)
		http.ServeFile(w, r, fullPath)
	})
}

func (h *StaticHandler) FileServer(root string, cacheControl string) http.Handler {
	fs := http.FileServer(http.Dir(filepath.Join(h.staticDir, root)))
	if cacheControl == "" {
		return fs
	}
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cache-Control", cacheControl)
		fs.ServeHTTP(w, r)
	})
}

func (h *StaticHandler) SpaFallback() http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		path := r.URL.Path

		// 防御分支：/api/** 不应走到这里，但显式拒绝更安全
		if strings.HasPrefix(path, "/api/") {
			http.NotFound(w, r)
			return
		}

		// 目录型路径直接返回入口页
		if path == "/" || strings.HasSuffix(path, "/") {
			h.serveIndexHTML(w, r)
			return
		}

		// 真实存在的静态文件 → 原样返回
		fullPath := filepath.Join(h.staticDir, path)
		if !strings.Contains(path, "..") {
			if info, err := os.Stat(fullPath); err == nil && !info.IsDir() {
				w.Header().Set("Cache-Control", "no-cache")
				http.ServeFile(w, r, fullPath)
				return
			}
		}

		// 前端路由 → 回退入口页（HTTP 200，不是 404）
		h.serveIndexHTML(w, r)
	}
}

func (h *StaticHandler) serveIndexHTML(w http.ResponseWriter, r *http.Request) {
	h.ServeIndex(w, r)
}

// ServeIndex 输出入口页（HTTP 200 + no-cache）。
// 【坑】不走 http.ServeFile：它对以 /index.html 结尾的请求会发 301 重定向到 ./
// （标准库的路径规范化行为），而 Java 版是直接 200 返回——为保持契约一致，
// 这里打开文件后用 ServeContent 直接输出（Last-Modified/条件请求语义保留）。
func (h *StaticHandler) ServeIndex(w http.ResponseWriter, r *http.Request) {
	f, err := os.Open(h.indexFile)
	if err != nil {
		http.NotFound(w, r)
		return
	}
	defer f.Close()
	fi, err := f.Stat()
	if err != nil || fi.IsDir() {
		http.NotFound(w, r)
		return
	}
	w.Header().Set("Cache-Control", "no-cache")
	http.ServeContent(w, r, fi.Name(), fi.ModTime(), f)
}