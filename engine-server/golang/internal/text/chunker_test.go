package text

import (
	"strings"
	"testing"
	"unicode/utf8"
)

// ============================================================================
// Chunk —— 分块（任务 3.1 验收：中文硬切不乱码 + 句子对齐 + 重叠语义）
// ============================================================================

// TestChunkChineseNoPunctuationHardSplit 中文无标点长文硬切不乱码（spec 硬约束）。
//
// 【教学注释 · Go 的表驱动测试】
//   Go 社区标准测试姿势：一张"输入→期望"表跑 for 循环——
//   对比 Java 的 @ParameterizedTest + @CsvSource，零注解零反射。
func TestChunkChineseNoPunctuationHardSplit(t *testing.T) {
	// 1000 个"汉"字无任何标点——必然触发 appendChunk 硬切兜底
	long := strings.Repeat("汉", 1000)
	chunks := NewChunker(400, 1).Chunk("doc-1", long)

	if len(chunks) != 3 {
		t.Fatalf("1000 字硬切应出 3 块（400+400+200），实际 %d 块", len(chunks))
	}
	for i, ch := range chunks {
		if !utf8.ValidString(ch.Text) {
			t.Errorf("第 %d 块不是合法 UTF-8（切在多字节字符中间）: %q", i, ch.Text)
		}
		if strings.ContainsRune(ch.Text, 0xFFFD) {
			t.Errorf("第 %d 块含替换字符 U+FFFD（乱码证据）", i)
		}
	}
	// 块长口径 = rune 数（对齐 Java String.length()）
	wantLens := []int{400, 400, 200}
	for i, want := range wantLens {
		if got := utf8.RuneCountInString(chunks[i].Text); got != want {
			t.Errorf("第 %d 块长度 = %d, 期望 %d", i, got, want)
		}
	}
	// 拼回去应等于原文（硬切不丢字）
	var reassembled strings.Builder
	for _, ch := range chunks {
		reassembled.WriteString(ch.Text)
	}
	if reassembled.String() != long {
		t.Error("硬切块拼接后与原文不一致（丢字/乱序）")
	}
}

// TestChunkSentenceAlignedAndOverlap 句子对齐 + 重叠 1 句保链。
func TestChunkSentenceAlignedAndOverlap(t *testing.T) {
	// 可区分的句子（编号开头），每句 17 rune：第N句内容填充一二三四五六七八九十。
	var sb strings.Builder
	for i := 0; i < 10; i++ {
		sb.WriteString("第" + string(rune('0'+i)) + "句内容填充一二三四五六七八九十。")
	}
	text := sb.String()
	// targetSize=50：2 句 34 字可并入，第 3 句 51 > 50 → 每块 2 句
	chunks := NewChunker(50, 1).Chunk("doc-2", text)

	if len(chunks) < 2 {
		t.Fatalf("10 句应至少 2 块，实际 %d", len(chunks))
	}
	first := chunks[0]
	second := chunks[1]
	// 第一块 = 第0句+第1句；重叠 1 句 → 第二块从第1句开始
	if !strings.HasSuffix(first.Text, "第1句内容填充一二三四五六七八九十。") {
		t.Errorf("第一块应以第1句收尾，实际: %q", first.Text)
	}
	if !strings.HasPrefix(second.Text, "第1句内容填充一二三四五六七八九十。") {
		t.Errorf("第二块应从第1句起（重叠保链），实际: %q", second.Text)
	}
	// 块索引连续从 0 开始
	for i, ch := range chunks {
		if ch.Index != i {
			t.Errorf("块 %d 的 Index = %d，期望连续编号", i, ch.Index)
		}
		if ch.DocumentID != "doc-2" {
			t.Errorf("块 %d 的 DocumentID = %q", i, ch.DocumentID)
		}
	}
}

// TestChunkEmptyAndShort 空文本与短文本边界。
func TestChunkEmptyAndShort(t *testing.T) {
	c := NewChunker(400, 1)
	if got := c.Chunk("d", "   "); got != nil && len(got) != 0 {
		t.Errorf("空白文本应返回空，实际 %v", got)
	}
	if got := c.Chunk("d", ""); len(got) != 0 {
		t.Errorf("空文本应返回空，实际 %v", got)
	}
	one := c.Chunk("d", "短句。")
	if len(one) != 1 || one[0].Text != "短句。" {
		t.Errorf("单短句应原样成块，实际 %v", one)
	}
}

// ============================================================================
// Tokenize —— 分词模式语义（spec 场景：红塔在公园）
// ============================================================================

func TestTokenizeSearchMode(t *testing.T) {
	// spec 场景：search 模式返回 bigram ["红塔","塔在","在公","公园"]
	got := NewChunker(400, 1).Tokenize("红塔在公园", "search")
	want := []string{"红塔", "塔在", "在公", "公园"}
	if len(got) != len(want) {
		t.Fatalf("search 模式分词 = %v, 期望 %v", got, want)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Errorf("token[%d] = %q, 期望 %q", i, got[i], want[i])
		}
	}
}

func TestTokenizeExactMode(t *testing.T) {
	// spec 场景：exact 模式去重单字
	got := NewChunker(400, 1).Tokenize("红塔在公园", "exact")
	want := []string{"红", "塔", "在", "公", "园"}
	if len(got) != len(want) {
		t.Fatalf("exact 模式分词 = %v, 期望 %v", got, want)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Errorf("token[%d] = %q, 期望 %q", i, got[i], want[i])
		}
	}
}

func TestTokenizeMixedChineseEnglish(t *testing.T) {
	got := NewChunker(400, 1).Tokenize("Go语言真棒", "all")
	want := []string{"Go", "语", "言", "真", "棒"}
	if len(got) != len(want) {
		t.Fatalf("中英混排分词 = %v, 期望 %v", got, want)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Errorf("token[%d] = %q, 期望 %q", i, got[i], want[i])
		}
	}
}

// ============================================================================
// Keywords —— TF 关键词
// ============================================================================

func TestKeywordsTopKAndNormalization(t *testing.T) {
	// "红塔" 出现 2 次的文本：bigram 后 "红塔"/"公园" 频次最高 → 权重 1.0
	text := "红塔很高。红塔在公园。公园有花。"
	kws := NewChunker(400, 1).Keywords(text, 3)
	if len(kws) != 3 {
		t.Fatalf("应提取 3 条关键词，实际 %d 条: %v", len(kws), kws)
	}
	if kws[0].Weight != 1.0 {
		t.Errorf("Top-1 权重应归一为 1.0，实际 %v（term=%q）", kws[0].Weight, kws[0].Term)
	}
	// 频次区分度：出现 1 次的词权重必须严格小于 1（去重会抹平频差——回归防线）
	if kws[2].Weight >= 1.0 {
		t.Errorf("低频词权重应 < 1（bigram 先去重导致 TF 失效的回归），实际 %v", kws[2].Weight)
	}
	for _, kw := range kws {
		if kw.Weight < 0 || kw.Weight > 1.0 {
			t.Errorf("权重 %v 越界 [0,1]（term=%q）", kw.Weight, kw.Term)
		}
	}
	// topK 截断
	few := NewChunker(400, 1).Keywords(text, 1)
	if len(few) != 1 {
		t.Errorf("topK=1 应只返回 1 条，实际 %d", len(few))
	}
}
