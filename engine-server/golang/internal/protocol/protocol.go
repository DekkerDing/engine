// Package protocol —— Java↔Go 通道的 JSON 行协议帧定义。
//
// 【教学注释 · Go vs Java】
//   Java 侧的对应物是 infrastructure/python/protocol/PythonProtocol.java：
//   Jackson 的 JsonNode/ObjectNode 手工拼帧。Go 用 struct + json tag 让
//   encoding/json 自动完成结构与 JSON 的互转——字段名映射、可空控制都
//   写在 struct tag 里，没有注解处理器、没有反射配置文件。
//
// 【协议契约（与 docs/reqforge-gotoolbox-spec.md 对齐）】
//   请求帧（stdin，一行一帧）：  {"id":1,"method":"sys.ping","params":{...}}
//   响应帧（stdout，一行一帧）： {"id":1,"result":{...}}
//                            或 {"id":1,"error":{"code":1001,"message":"..."}}
//   id 逐帧配对；stdout 是协议专线，引擎日志一律走 stderr（不得混流）。
package protocol

import "encoding/json"

// 错误码表：1000 段协议级、2000 段工具方法级。
// 【教学注释】Go 没有 enum 关键字——一组带类型的常量就是枚举的惯用形态，
// iota 自增省去逐个赋值（对比 Java 的 enum 实例列表）。
const (
	CodeMethodNotFound = 1001 + iota // 方法名不在注册表中
	CodeInvalidParams                // params 无法解码为方法所需的形状
	CodeInternal                     // 处理器内部错误（含 panic 兜底）
)

// 2000 段：hashing 工具的业务错误（越界拒绝 / 文件 IO）
const (
	CodePathRejected = 2001
	CodeFileIO       = 2002
)

// Request Java→Go 请求帧。
type Request struct {
	ID     int64  `json:"id"`
	Method string `json:"method"`
	// Params 原样保存为未解码的字节切片（json.RawMessage）——协议层只解
	// 帧结构，各工具方法自行把 Params 解成自己的形状。等价于 Java 里
	// 透传 JsonNode 而不是提前绑定 POJO：新增方法零协议改动，
	// "方法注册表 + 通用派发"的通用性就建立在这上面。
	Params json.RawMessage `json:"params,omitempty"`
}

// ErrorObject 错误响应体：code 数字语义 + 人读 message。
type ErrorObject struct {
	Code    int    `json:"code"`
	Message string `json:"message"`
}

// Response Go→Java 响应帧。Result 与 Error 互斥，omitempty 保证
// 成功帧不带 error 键、失败帧不带 result 键（JSON 更干净，Java 侧判定简单）。
type Response struct {
	ID     int64        `json:"id"`
	Result interface{}  `json:"result,omitempty"`
	Error  *ErrorObject `json:"error,omitempty"`
}

// NewResult 构造成功帧。
func NewResult(id int64, result interface{}) Response {
	return Response{ID: id, Result: result}
}

// NewError 构造失败帧。
func NewError(id int64, code int, message string) Response {
	return Response{ID: id, Error: &ErrorObject{Code: code, Message: message}}
}
