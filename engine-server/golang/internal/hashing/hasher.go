// Package hashing —— 确定性哈希向量（模型不可用时的降级方案）。
//
// 【教学注释 · 为什么需要哈希向量】
//   当真实 Embedding 模型（bge/CLIP）不可用时，用文本的确定性哈希产生
//   一个"伪向量"——保证同文本同向量、纵横进程一致，且带 degraded 标志。
//   这跟微服务架构的"断路器"思想一脉相承：降级运行不断线。

package hashing

import (
	"crypto/sha256"
	"encoding/binary"
	"fmt"
	"math"
)

// Generate 从文本产生确定性伪向量（维度=targetDim）。
//
// 【算法】SHA-256("text:round") → 每 4 字节映射到 [-1, 1)——
//   与 Java/Python 端完全一致，保证跨平台确定性。
//
// 【教学注释 · sha256.Sum256 返回 [32]byte 固定长度数组】
//   不是切片！固定数组长度是类型的一部分——这点和 Java 数组不同。
func Generate(text string, targetDim int) []float64 {
	if targetDim <= 0 {
		targetDim = 512 // 默认与 bge-small-zh-v1.5 对齐
	}
	vec := make([]float64, targetDim)

	for round := 0; round*8 < targetDim; round++ {
		input := fmt.Sprintf("%s:%d", text, round)
		hash := sha256.Sum256([]byte(input))

		for j := 0; j < 8; j++ {
			idx := round*8 + j
			if idx >= targetDim {
				break
			}
			u := binary.BigEndian.Uint32(hash[j*4 : j*4+4])
			// 【教学注释】BigEndian 是跨平台确定性的关键——
			//   x86 小端和 ARM 可变字节序若用 native endian 会出不同结果。
			vec[idx] = float64(u)/float64(math.MaxUint32)*2 - 1 // [-1, 1)
		}
	}
	return vec
}

// GenerateNormalized 产生哈希向量并归一化到 L2 单位长度。
// 归一块后点积=余弦的语义才成立。
func GenerateNormalized(text string, targetDim int) []float64 {
	vec := Generate(text, targetDim)
	var sumSq float64
	for _, v := range vec {
		sumSq += v * v
	}
	norm := math.Sqrt(sumSq)
	if norm > 1e-12 {
		for i := range vec {
			vec[i] /= norm
		}
	}
	return vec
}