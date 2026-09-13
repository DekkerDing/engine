package hashing

import (
	"math"
	"testing"
)

// TestGenerateDeterministic 确定性（spec 硬约束）：同文本同维度两次调用逐元素相等。
func TestGenerateDeterministic(t *testing.T) {
	a := Generate("红塔在公园", 64)
	b := Generate("红塔在公园", 64)
	if len(a) != 64 || len(b) != 64 {
		t.Fatalf("维度应为 64，实际 %d/%d", len(a), len(b))
	}
	for i := range a {
		if a[i] != b[i] {
			t.Fatalf("第 %d 元素不稳定：%v vs %v", i, a[i], b[i])
		}
	}
}

// TestGenerateDims 维度：默认 512 / 显式覆盖 / 非法值回落默认。
func TestGenerateDims(t *testing.T) {
	if got := len(Generate("x", 0)); got != 512 {
		t.Errorf("dim=0 应回落默认 512，实际 %d", got)
	}
	if got := len(Generate("x", -3)); got != 512 {
		t.Errorf("负 dim 应回落默认 512，实际 %d", got)
	}
	if got := len(Generate("x", 33)); got != 33 {
		t.Errorf("显式 dim=33 应生效（非 8 倍数也须覆盖），实际 %d", got)
	}
}

// TestGenerateValueRange 值域 [-1, 1)：BigEndian 映射的合同。
func TestGenerateValueRange(t *testing.T) {
	vec := Generate("任意文本", 512)
	for i, v := range vec {
		if v < -1 || v >= 1 {
			t.Errorf("元素 %d 越界 [-1,1)：%v", i, v)
		}
	}
}

// TestGenerateTextSensitivity 不同文本产出不同向量（哈希的最基本灵敏性）。
func TestGenerateTextSensitivity(t *testing.T) {
	a := Generate("红塔", 64)
	b := Generate("白塔", 64)
	diff := 0
	for i := range a {
		if a[i] != b[i] {
			diff++
		}
	}
	if diff < 32 {
		t.Errorf("两文本哈希向量应有过半元素不同，实际仅 %d/64", diff)
	}
}

// TestGenerateNormalizedL2 归一化语义（spec 场景）：L2 模长 = 1（容差内）。
func TestGenerateNormalizedL2(t *testing.T) {
	vec := GenerateNormalized("红塔在公园", 512)
	var sumSq float64
	for _, v := range vec {
		sumSq += v * v
	}
	if math.Abs(math.Sqrt(sumSq)-1) > 1e-9 {
		t.Errorf("归一后 L2 模长应为 1，实际 %v", math.Sqrt(sumSq))
	}
}

// TestGenerateNormalizedPreservesDirection 归一只缩放不改方向（对拍时
// normalize=true/false 两版应逐元素成比例）。
func TestGenerateNormalizedPreservesDirection(t *testing.T) {
	raw := Generate("方向一致性", 128)
	norm := GenerateNormalized("方向一致性", 128)
	ratio := norm[0] / raw[0]
	for i := range raw {
		got := norm[i] / raw[i]
		if math.Abs(got-ratio) > 1e-12 {
			t.Errorf("元素 %d 比例漂移：%v vs %v", i, got, ratio)
		}
	}
}
