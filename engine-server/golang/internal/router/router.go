// Package router —— 方法注册表：通用派发的心脏。
//
// 【教学注释 · Go vs Java】
//   Java 的等价物是 Map<String, Function<JsonNode, JsonNode>> + 一个
//   @Component 分发器。Go 这里有两处语言差异值得对照：
//   1) 函数是一等公民：HandlerFunc 直接作为类型声明，不需要函数式接口
//      （Java 的 Function<T,R> 在 Go 里就是一个 func 类型别名）；
//   2) 没有异常：处理器用多返回值 (result, error) 表达成败——错误是值，
//      调用方必须当场处理，编译器不强迫但社区风格强约束（if err != nil）。
//
// 【 panic 兜底】Dispatch 内 recover() 把处理器 panic 转成内部错误帧，
// 保证任何单个方法的崩溃都不会杀死整个引擎进程——等价于 Java 里
// dispatcher 外层 catch (Throwable) 的防线，但 Go 的 defer+recover
// 只在 panic 发生时才付出代价。
package router

import (
	"encoding/json"
	"fmt"

	"engine/gotoolbox/internal/protocol"
)

// HandlerFunc 工具方法的统一形状：进 params（原始 JSON），出结果或协议错误。
// 【教学注释】*ErrorObject 返回 nil 即成功——Go 的"错误是值"风格，
// 对比 Java 抛异常的栈展开路径。
type HandlerFunc func(params json.RawMessage) (interface{}, *protocol.ErrorObject)

// Router 方法注册表。注册只发生在 main 启动期，运行期只读——
// 天然并发安全（对比 Java：不可变 Map 构造后发布，同一纪律）。
type Router struct {
	handlers map[string]HandlerFunc
}

// New 创建空注册表。
func New() *Router {
	return &Router{handlers: make(map[string]HandlerFunc)}
}

// Register 登记一个方法名到处理器。同名重复注册是编程错误，直接 panic
// （启动期暴露，fail-fast；对比 Java 的 IllegalStateException）。
func (r *Router) Register(method string, h HandlerFunc) {
	if _, exists := r.handlers[method]; exists {
		panic(fmt.Sprintf("方法重复注册: %s", method))
	}
	r.handlers[method] = h
}

// Has 报告方法是否已注册（sys.* 内置方法探测用）。
func (r *Router) Has(method string) bool {
	_, ok := r.handlers[method]
	return ok
}

// Dispatch 派发一帧请求：查表 → 调用 → 组装响应帧。
// 未知方法返回 code=1001 错误帧（进程不退出，调用方可继续）；
// 处理器 panic 被 recover 成 code=1003 内部错误帧。
func (r *Router) Dispatch(req protocol.Request) (resp protocol.Response) {
	handler, ok := r.handlers[req.Method]
	if !ok {
		return protocol.NewError(req.ID, protocol.CodeMethodNotFound,
			fmt.Sprintf("方法不存在: %s", req.Method))
	}
	// 【教学注释 · defer+recover】defer 把"函数返回前必做"压栈——无论
	// 正常返回还是 panic 展开，recover() 都能截住 panic 值并就地转成
	// 错误帧返回（命名返回值 resp 让 recover 分支也能赋值返回）。
	defer func() {
		if rec := recover(); rec != nil {
			resp = protocol.NewError(req.ID, protocol.CodeInternal,
				fmt.Sprintf("处理器 panic: %v", rec))
		}
	}()
	result, errObj := handler(req.Params)
	if errObj != nil {
		return protocol.NewError(req.ID, errObj.Code, errObj.Message)
	}
	return protocol.NewResult(req.ID, result)
}
