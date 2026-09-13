// toolbox —— Go 工具箱引擎入口（引擎二进制名即 toolbox）。
//
// 【架构位置】Java(engine-server, :8090) 是门面；本进程是嵌入引擎：
//   JVM 经 stdin 写请求帧、从 stdout 读响应帧（JSON 行协议，
//   见 internal/protocol）。日志一律 stderr——stdout 是协议专线。
//
// 【教学注释 · Go vs Java 的 main 入口】
//   Java 入口 = SpringApplication.run——框架接管 main，注解扫描、DI、内嵌容器全自动
//   Go  入口 = 显式三步：创建 Router → 创建 Engine 并注册 → 启动 Server 协议循环
//   每一步都能看到、都能打断点、都能单独测试。"没有魔法"是 Go 的天花板也是地板。
//
// 【场景分工（你选的"Java 门面 + Go 引擎"模式）】
//   Java（门面层）：
//     - HTTP REST 端点（@RequestMapping）
//     - DDD 业务编排（application/domain）
//     - SQLite 持久化（真相库）
//     - 缓存（Caffeine）
//     - 与前端 SPA 交互
//   Go（引擎层）：
//     - 文本分块 / 分词 / 关键词提取（高 CPU · text.*）
//     - 向量索引与余弦检索（高 CPU · vector.*）
//     - 确定性哈希向量降级（高 CPU · hashing.*）
//     - 全文检索（高 CPU · search.* ，待扩展）
//     - 文件批量读写（高 IO · io.* ，待扩展）
//
// 【教学注释 · 零三方依赖策略】
//   标准库已覆盖本引擎全部需要：crypto/sha256、bufio、encoding/json、sync、os
//   → go build 完全离线可跑、无网络下载，与仓库"不稳定网络下断点续传"对齐。

package main

import (
	"fmt"
	"os"

	"engine/gotoolbox/internal/engine"
	"engine/gotoolbox/internal/router"
)

// version 构建期可注入（-ldflags "-X main.version=..."）；默认值。
var version = "0.1.0-dev"

func main() {
	os.Exit(run())
}

// run 独立于 main，便于测试与退出码管理。
//
// 【教学注释 · os.Exit 与 defer 的陷阱】
//   os.Exit 立即终止进程，不执行任何 defer——所以真实逻辑放进 run()，
//   main 只做 Exit 一件事（run 里的 defer 在返回前正常执行完）。
//   这是 Go 社区公认的 main/run 分离模式。
func run() int {
	// =====================================================================
	// 第一步：创建方法注册表（Router）
	// =====================================================================
	r := router.New()

	// =====================================================================
	// 第二步：创建引擎门面，注册所有工具方法
	// =====================================================================
	// 【教学注释 · "手动 DI"的全貌】
	//   这一步就是 Go 版的"依赖注入容器启动"——
	//   Engine 内部持有 text.Chunker、vector.Index 等所有工具实例，
	//   然后逐一注册到 Router。所有依赖关系在这一行代码里全部可见。
	eng := engine.New()
	eng.RegisterAll(r)

	// =====================================================================
	// 第三步：启动协议主循环
	// =====================================================================
	// 【教学注释 · Go 的错误处理惯例】
	//   if err := ...; err != nil { ... } 是 Go 最常见的代码模式——
	//   声明 + 赋值 + 判断一行搞定（这叫"if with a short statement"）。
	//   对比 Java 的 try-catch，Go 的显式 error 检查让"错误可能发生在哪里"
	//   一目了然，但代码行数更多。这是 Go 社区的取舍：可读性 > 简洁性。
	srv := engine.NewServer(r)
	if err := srv.Run(); err != nil {
		fmt.Fprintf(os.Stderr, "[toolbox] 引擎异常退出: %v\n", err)
		return 1
	}

	return 0
}