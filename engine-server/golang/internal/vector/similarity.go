// Package vector —— 向量数学运算与内存索引（全部高 CPU 场景）。
//
// 【教学注释 · 为什么向量操作是"高 CPU"场景】
//   余弦相似度 = 点积  / (|A| × |B|)，而点积是 O(n) 的浮点乘法累加——
//   512 维 × 10000 条 = 512 万次乘加运算。纯 CPU 密集，零 IO。
//   Go 在这里的优势：
//   1. 编译成原生代码（非 JIT 预热），冷启动就是满速
//   2. 无 GC 停顿干扰浮点循环（Go 的 GC 是并发的，<1ms 停顿）
//   3. goroutine 天然适合"每条查询向量独立跑"的并行检索模式
//
// 【教学注释 · Go 的 float64 vs float32】
//   Java 端 Embedding 使用 float[]（float32）存储向量以节省内存。
//   Go 这里用 float64：余弦计算涉及大量浮点累加，float64 精度高
//   且现代 CPU 的 double 乘加延迟与 float 基本一样。
//   float32 在 Go 里主要出现在与外部系统交互时（如读取 Java 端存储的
//   float32 little-endian BLOB），到计算路径上转为 float64。
//   这是"存储精度"和"计算精度"的分离——Go 允许你显式做这个决策。

package vector

import "math"

// ============================================================================
// 向量数学原语（对标 Java 端 VectorizationDomainService）
// ============================================================================

// DotProduct 计算两个向量的点积（内积）。
//
// 【教学注释 · Go 的 for range 遍历切片】
//   for i, v := range a 中 i 是索引、v 是元素值的副本——
//   修改 v 不影响 a[i]。这是 Go 的"值语义"：range 默认复制元素。
//   对于 float64 切片，复制一个元素代价极小（8 字节）。
//
// 【教学注释 · 性能考量 · 为什么不用 SIMD】
//   Go 编译器不自动 SIMD 向量化（C/C++ 编译器会）。
//   对于 512 维以下的点积，纯 Go 循环已足够快（纳秒级）。
//   万级条目 × 512 维的暴力检索在 Go 里是毫秒级——
//   这种规模还不需要汇编/SIMD 优化。YAGNI（You Ain't Gonna Need It）。
func DotProduct(a, b []float64) float64 {
	// 防御：维度不想等不应出现在生产路径，但库函数不该假设调用方正确
	if len(a) != len(b) {
		panic("DotProduct: 向量维度不一致")
	}
	var sum float64 // Go 的零值初始化：float64 的零值是 0.0
	for i := range a {
		// 【教学注释 · Go 的浮点累加不会自动提升类型】
		//   float64 + float64 = float64（不会像 Java 那样 float+float 可能到 double）
		//   因为这里两个操作数都是 float64，类型完全匹配。
		sum += a[i] * b[i]
	}
	return sum
}

// L2Norm 计算向量的 L2 范数（欧几里得长度）。
//
// 【教学注释 · math.Sqrt 来自标准库 math】
//   Go 的 math 包对标 Java 的 java.lang.Math——提供基础数学函数。
//   但 math.Sqrt 的参数和返回值都是 float64（没有 float32 版本）。
//   这是 Go 标准库设计哲学："保持简单"——只有 float64 一种浮点数学。
func L2Norm(v []float64) float64 {
	var sumSq float64
	for _, x := range v {
		sumSq += x * x
	}
	return math.Sqrt(sumSq)
}

// CosineSimilarity 计算两个向量的余弦相似度。
//
// 【公式】cos(θ) = (A·B) / (|A| × |B|)
//   值域 [-1, 1]：1=完全相同方向，0=正交，-1=完全相反方向。
//
// 【教学注释 · math.Max 的"防止 NaN 传播"用法】
//   分母接近 0 时（零向量或极短向量），除法会得 NaN 或 Inf。
//   math.Max(denom, 1e-12) 把分母钳制在极小正数——
//   返回 0.0 而不是 NaN（零向量与任何向量的余弦无意义，0 是最安全的默认值）。
func CosineSimilarity(a, b []float64) float64 {
	if len(a) != len(b) {
		panic("CosineSimilarity: 向量维度不一致")
	}
	dot := DotProduct(a, b)
	normA := L2Norm(a)
	normB := L2Norm(b)
	denom := normA * normB
	if denom < 1e-12 {
		return 0.0 // 零向量：余弦无意义 → 返回 0（正交的最安全解释）
	}
	return dot / denom
}

// Normalize 把向量归一化到 L2 单位长度。
//
// 【教学注释 · Go 的"修改入参" vs "返回新切片"】
//   这里返回新切片（不修改入参）：函数式风格，避免副作用。
//   代价是每次归一化都会分配新内存——对于索引构建（一次性）可以接受，
//   对于高频查询路径（每条查询向量都要归一化），可以改为原地修改版本。
func Normalize(v []float64) []float64 {
	norm := L2Norm(v)
	if norm < 1e-12 {
		// 零向量：返回全 0 副本
		result := make([]float64, len(v))
		copy(result, v)
		return result
	}
	result := make([]float64, len(v))
	for i, x := range v {
		result[i] = x / norm
	}
	return result
}

// NormalizeInPlace 原地归一化（不分配新内存）。
//
// 【教学注释 · Go 的"原地修改"惯例】
//   方法名带 InPlace 后缀明确告知调用方"我会改你的数据"——
//   这是一种文档约定，不是编译器强制。Go 社区重视命名来表达语义。
func NormalizeInPlace(v []float64) {
	norm := L2Norm(v)
	if norm < 1e-12 {
		return // 零向量：保持原样
	}
	for i := range v {
		v[i] /= norm
	}
}