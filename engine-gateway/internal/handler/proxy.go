package handler

import (
	"encoding/json"
	"log"
	"net"
	"net/http"
	"net/http/httputil"
	"net/url"
	"os"
	"time"

	"github.com/dekkerding/engine-gateway/internal/model"
)

type ProxyHandler struct {
	reverseProxy *httputil.ReverseProxy
	maxBodyBytes int64
}

func NewProxyHandler(upstreamURL string, connectTimeout, readTimeout time.Duration, maxBodyBytes int64) (*ProxyHandler, error) {
	target, err := url.Parse(upstreamURL)
	if err != nil {
		return nil, err
	}

	p := &ProxyHandler{
		maxBodyBytes: maxBodyBytes,
	}

	director := func(req *http.Request) {
		req.URL.Scheme = target.Scheme
		req.URL.Host = target.Host
		req.Host = target.Host
	}

	transport := &http.Transport{
		DialContext: (&net.Dialer{
			Timeout: connectTimeout,
		}).DialContext,
		ResponseHeaderTimeout: readTimeout,
	}

	p.reverseProxy = &httputil.ReverseProxy{
		Director:      director,
		Transport:     p.wrapTransport(transport),
		ErrorHandler:  p.errorHandler,
		FlushInterval: -1,
	}

	return p, nil
}

type roundTripperFunc func(*http.Request) (*http.Response, error)

func (f roundTripperFunc) RoundTrip(req *http.Request) (*http.Response, error) {
	return f(req)
}

var errBodyTooLarge = &bodyTooLargeError{}

type bodyTooLargeError struct{}

func (e *bodyTooLargeError) Error() string { return "request body exceeds limit" }
func (e *bodyTooLargeError) Timeout() bool { return false }
func (e *bodyTooLargeError) Temporary() bool { return false }

func (p *ProxyHandler) wrapTransport(next http.RoundTripper) http.RoundTripper {
	return roundTripperFunc(func(req *http.Request) (*http.Response, error) {
		if req.ContentLength > p.maxBodyBytes {
			return nil, errBodyTooLarge
		}
		return next.RoundTrip(req)
	})
}

func (p *ProxyHandler) errorHandler(w http.ResponseWriter, r *http.Request, err error) {
	log.Printf("proxy error: %s %s -> %v", r.Method, r.URL.Path, err)

	if _, ok := err.(*bodyTooLargeError); ok {
		// 文案与 server 侧 GlobalExceptionHandler 逐字一致（50MB），用户在任一拦截层看到同一句话
		writeError(w, http.StatusBadRequest, 1000, "文件超过大小限制（50MB）")
		return
	}

	var status int
	var msg string

	if isTimeout(err) {
		status = http.StatusGatewayTimeout
		msg = "连接上游服务超时"
	} else {
		status = http.StatusBadGateway
		msg = "上游服务不可用（engine-server 未启动或端口不通）"
	}

	writeError(w, status, 5001, msg)
}

func isTimeout(err error) bool {
	if os.IsTimeout(err) {
		return true
	}
	if netErr, ok := err.(net.Error); ok && netErr.Timeout() {
		return true
	}
	return false
}

func writeError(w http.ResponseWriter, httpStatus, code int, message string) {
	resp := model.Error(code, message)
	w.Header().Set("Content-Type", "application/json;charset=utf-8")
	w.WriteHeader(httpStatus)
	json.NewEncoder(w).Encode(resp)
}

func (p *ProxyHandler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	p.reverseProxy.ServeHTTP(w, r)
}