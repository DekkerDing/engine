// server.go —— 协议主循环：stdin 读请求帧，stdout 写响应帧。
//
// 骨架期（任务 1.1）：Run 仅占位返回，验证工程可编译；
// 任务 1.2 在此落地 JSON 行协议循环与 sys.ping，
// 任务 1.3 落地 sys.shutdown 特判与启动横幅。
package engine

import (
	"engine/gotoolbox/internal/router"
)

// Server 协议服务器：持有方法注册表，跑 stdin→stdout 主循环。
type Server struct {
	router *router.Router
}

// NewServer 组装协议服务器。
func NewServer(r *router.Router) *Server {
	return &Server{router: r}
}

// Run 阻塞跑主循环直至退出条件（1.2 落地：stdin EOF / 1.3 落地：sys.shutdown）。
func (s *Server) Run() error {
	return nil // 骨架占位
}
