// Package engine —— 引擎门面：持有全部工具实例，向 Router 注册方法。
//
// 【教学注释 · Go vs Java 的"组件装配"】
//   Java/Spring：@Component 扫描 + @Autowired 注入——装配发生在框架内部；
//   Go         ：Engine 结构体显式持有工具字段，New() 显式构造，
//                RegisterAll 显式注册——装配全部发生在光天化日之下。
//   对应关系：Engine ≈ @Configuration + 各 @Bean 方法；
//             RegisterAll ≈ BeanNameUrlHandlerMapping 的路由注册表。
//
// 【场景挂载点（任务驱动，逐步填满）】
//   1.2  sys.ping            存活握手
//   1.3  sys.shutdown        优雅退出（由 Server 主循环特判）
//   3.x  hashing.*           文件流式哈希（高 IO · S2）
//   4.x  vector.*            向量索引副本 + 并行扫描（高计算 · S1）
//   v2   text.* / search.*   分块/全文等候补场景
package engine

import (
	"encoding/json"

	"engine/gotoolbox/internal/protocol"
	"engine/gotoolbox/internal/router"
)

// Version 引擎版本：构建期可注入
// go build -ldflags "-X engine/gotoolbox/internal/engine.Version=1.0.0"
// 【教学注释】对比 Java 的 -X 不存在等价物——版本常注入 jar 清单或
// 环境变量；Go 的链接器符号注入是最轻的官方姿势。
var Version = "0.2.0"

// Engine 工具实例的宿主。当前骨架为空——各场景包落地时在结构体加字段、
// New() 里构造、RegisterAll() 里注册，一处可见全部依赖。
type Engine struct{}

// New 构造引擎（工具实例随场景任务逐步注入）。
func New() *Engine {
	return &Engine{}
}

// RegisterAll 把引擎全部工具方法登记进注册表。
// 【教学注释】方法指针 r.Register("hashing.batch", e.hashingBatch)
// 就是 Go 的"方法引用"——无需函数式接口包装。
func (e *Engine) RegisterAll(r *router.Router) {
	// ---- sys.* 内置方法 ----
	// sys.ping：Java 侧启动握手 / 健康探测的存活帧。
	// 【教学注释 · 闭包即 handler】这里传的匿名函数捕获了 Version——
	// Go 闭包对变量的捕获是引用语义，对比 Java lambda 只能捕获终态变量。
	r.Register("sys.ping", func(params json.RawMessage) (interface{}, *protocol.ErrorObject) {
		return map[string]interface{}{
			"status":  "pong",
			"version": Version,
		}, nil
	})
	// 场景方法随 3.x/4.x 任务注册（hashing.* / vector.* / text.*）
}
