// server.go —— 协议主循环：stdin 逐行读请求帧，stdout 逐帧写响应。
//
// 【协议契约】stdout 是协议专线——每行一个 JSON 响应帧，写完必须立即
// Flush（Java 侧 BufferedReader.readLine() 阻塞等待换行 + 数据到位；
// 忘记 Flush 是 stdio 协议的头号死锁来源）。引擎日志一律 stderr。
//
// 【教学注释 · Go vs Java 的流处理】
//   Java: BufferedReader.readLine() 阻塞读一行，返回 null 表示流结束
//   Go  : bufio.Scanner 的 Scan() 布尔推进 + Text()/Bytes() 取当前行，
//         Scan() 返回 false 即 stdin EOF（等价于 readLine() == null）
//   关键差异在缓冲上限：Scanner 默认单行最大 64KB——向量帧（base64 编码的
//   float 批量）远超此限，必须显式 Buffer() 扩容，否则静默截断报错。
package engine

import (
	"bufio"
	"bytes"
	"encoding/json"
	"fmt"
	"os"
	"runtime"
	"sync"

	"engine/gotoolbox/internal/protocol"
	"engine/gotoolbox/internal/router"
)

// maxFrameBytes 单帧上限 64MB：vscan.index.replace 批量灌入时
// （如 100 块 × 512 维 × 4 字节 → base64 膨胀 1.33 倍）留足余量。
const maxFrameBytes = 64 * 1024 * 1024

// Server 协议服务器：持有方法注册表，跑 stdin→stdout 主循环。
type Server struct {
	router *router.Router

	// writerMu 写锁：主循环当前串行，锁是给未来并发 handler 留的防线
	// （对比 StdioChannel 的 synchronized call()——协议层串行，进程内并行）。
	writerMu sync.Mutex
}

// NewServer 组装协议服务器。
func NewServer(r *router.Router) *Server {
	return &Server{router: r}
}

// Run 阻塞跑主循环。退出路径有二：stdin EOF（对端关闭）或 sys.shutdown
// （优雅谢幕帧——先回 bye 响应再退出，退出码恒 0）。
func (s *Server) Run() error {
	// 启动横幅：版本 / 平台 / 并行度，打 stderr（stdout 是协议专线）。
	// 【教学注释 · runtime 包】GOOS/GOARCH 是编译期钉死的平台常量，
	// NumCPU() 是运行时探测——对应 Java 的 os.name/os.arch 与
	// Runtime.getRuntime().availableProcessors()。
	fmt.Fprintf(os.Stderr, "[toolbox] 启动 version=%s platform=%s/%s gomaxprocs=%d\n",
		Version, runtime.GOOS, runtime.GOARCH, runtime.NumCPU())

	in := bufio.NewScanner(os.Stdin)
	in.Buffer(make([]byte, 64*1024), maxFrameBytes)

	out := bufio.NewWriter(os.Stdout)
	// 【教学注释 · defer 与显式 Flush 并存】defer 兜底最终冲刷，
	// 但协议正确性要求逐帧 Flush——defer 只是"别忘记最后一刷"的保险。
	defer out.Flush()

	for in.Scan() {
		line := bytes.TrimSpace(in.Bytes())
		if len(line) == 0 {
			continue // 空行容忍：Java 侧偶发的尾部换行不致崩
		}

		var req protocol.Request
		if err := json.Unmarshal(line, &req); err != nil {
			// 坏帧无法拿到 id → 以 id=0 回错误帧；进程不退出（容错边界）
			s.write(out, protocol.NewError(0, protocol.CodeInvalidParams,
				fmt.Sprintf("帧解析失败: %v", err)))
			continue
		}

		// sys.shutdown 特判：不进注册表——"让循环退出"是主循环自己的
		// 权限，handler 只能算业务方法（回调无法触达 Run 的控制流，
		// 这与 Java 里 listener 停不掉事件循环是同一条边界）。
		if req.Method == "sys.shutdown" {
			s.write(out, protocol.NewResult(req.ID, map[string]string{"status": "bye"}))
			fmt.Fprintf(os.Stderr, "[toolbox] 收到 sys.shutdown，优雅退出\n")
			return nil
		}

		// 未知方法由 Router 返回 1001 错误帧，进程继续服务
		resp := s.router.Dispatch(req)
		s.write(out, resp)
	}

	if err := in.Err(); err != nil {
		return fmt.Errorf("stdin 读取失败: %w", err)
	}
	return nil // EOF = 对端关闭，正常谢幕
}

// write 线程安全地写一帧并立即 Flush。
// 【教学注释 · %w 错误包装】错误链的标准姿势，等价于 Java 的
// new Exception("...", cause) 保留根因；调用方 errors.Is/As 可溯源。
func (s *Server) write(out *bufio.Writer, resp protocol.Response) {
	s.writerMu.Lock()
	defer s.writerMu.Unlock()
	data, err := json.Marshal(resp)
	if err != nil {
		// 响应都由本包构造，Marshal 失败近乎不可能；兜底写死帧防悬挂
		data = []byte(fmt.Sprintf(`{"id":%d,"error":{"code":%d,"message":"响应序列化失败"}}`,
			resp.ID, protocol.CodeInternal))
	}
	out.Write(append(data, '\n'))
	out.Flush() // 不 Flush = Java readLine() 永远等不到这行 = 死锁
}
