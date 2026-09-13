package vector

import (
	"math"
	"testing"
)

// ============================================================================
// 测试脚手架
// ============================================================================

// dim4 造一个 4 维向量（测试用小维度——语义与 512 维完全一致，只是跑得快）。
func dim4(a, b, c, d float64) []float64 {
	return []float64{a, b, c, d}
}

// textEntry 造一条 text 空间的条目（source_type/model_key 的默认组合）。
func textEntry(docID string, chunkIdx int, v []float64) Entry {
	return Entry{
		DocID:      docID,
		ChunkIdx:   chunkIdx,
		Vector:     v,
		SourceType: "text",
		ModelKey:   "go-hash-degraded",
	}
}

// ============================================================================
// 任务 4.1 验收三项：insert 幂等 / 删除不命中 / 相似度对角线 = 1
// ============================================================================

// TestReplaceIdempotent insert（Replace）按 docId 幂等替换：
// 同一文档二次写入不双计——重传不留旧向量（对标 Java VectorStore.save 的"先清后写"）。
func TestReplaceIdempotent(t *testing.T) {
	idx := NewIndex()

	idx.Replace("doc-1", []Entry{
		textEntry("doc-1", 0, dim4(1, 0, 0, 0)),
		textEntry("doc-1", 1, dim4(0, 1, 0, 0)),
	})
	if got := idx.Size(); got != 2 {
		t.Fatalf("首次写入后 Size 应为 2，实际 %d", got)
	}

	// 二次写入（块数变少）：旧条目整体让位，不叠加
	idx.Replace("doc-1", []Entry{
		textEntry("doc-1", 0, dim4(1, 1, 0, 0)),
	})
	if got := idx.Size(); got != 1 {
		t.Fatalf("幂等替换后 Size 应为 1（不双计），实际 %d", got)
	}

	// 替换后检索只能命中新向量：新向量与 [1,1,0,0] 归一化后同向（余弦=1）
	hits := idx.Search(dim4(1, 1, 0, 0), 10, "text", "go-hash-degraded")
	if len(hits) != 1 || hits[0].Entry.ChunkIdx != 0 {
		t.Fatalf("替换后应只命中新条目 chunk0，实际 %+v", hits)
	}
	// 旧向量的"专属方向"不再命中（[0,1,0,0] 与新条目余弦 = 0.707，仍在但分数不同——
	// 换个断言：总条目数已是 1，旧 chunk1 已消失）
	for _, h := range hits {
		if h.Entry.ChunkIdx == 1 {
			t.Fatalf("旧 chunk1 应已被替换清除，却仍在索引中")
		}
	}
}

// TestRemoveDeleteMiss vector.delete 后检索不再命中（文档删除联动清理的原子语义）。
func TestRemoveDeleteMiss(t *testing.T) {
	idx := NewIndex()
	idx.Replace("doc-1", []Entry{textEntry("doc-1", 0, dim4(1, 0, 0, 0))})
	idx.Replace("doc-2", []Entry{textEntry("doc-2", 0, dim4(0, 1, 0, 0))})

	if got := idx.Size(); got != 2 {
		t.Fatalf("两文档写入后 Size 应为 2，实际 %d", got)
	}

	idx.Remove("doc-1")

	if got := idx.Size(); got != 1 {
		t.Fatalf("删除后 Size 应为 1，实际 %d", got)
	}
	// 注意断言口径：Go 端 Search 无分数阈值（min-vector-score 在 Java 门面）——
	// 正交向量（余弦=0）也会命中排底。所以断言的是"命中里没有 doc-1"而非"0 命中"
	hits := idx.Search(dim4(1, 0, 0, 0), 10, "text", "go-hash-degraded")
	for _, h := range hits {
		if h.Entry.DocID == "doc-1" {
			t.Fatalf("被删文档不应再命中，却出现于结果中（score=%v）", h.Score)
		}
	}
	// 邻居不受影响
	hits = idx.Search(dim4(0, 1, 0, 0), 10, "text", "go-hash-degraded")
	if len(hits) != 1 || hits[0].Entry.DocID != "doc-2" {
		t.Fatalf("未删除文档应正常命中，实际 %+v", hits)
	}

	// 幂等删除：删不存在的文档不 panic 不计数（Java 侧"幂等双删无害"同款）
	idx.Remove("no-such-doc")
	if got := idx.Size(); got != 1 {
		t.Fatalf("删除不存在的文档不应改变 Size，实际 %d", got)
	}
}

// TestCosineDiagonal 相似度对角线 = 1：任意非零向量与自身的余弦恰为 1。
// 这是余弦度量的自反性合同——对角线都不等于 1，整个检索排序都是空中楼阁。
func TestCosineDiagonal(t *testing.T) {
	vectors := [][]float64{
		dim4(1, 0, 0, 0),
		dim4(3, -4, 5, 6),               // 含负分量
		{0.1, 0.2, 0.3, 0.4, 0.5, 0.6}, // 非 4 维（维度无关性）
		{7},                             // 一维退化情形
	}
	for i, v := range vectors {
		if got := CosineSimilarity(v, v); math.Abs(got-1) > 1e-9 {
			t.Errorf("向量 %d 与自身的余弦应为 1，实际 %v", i, got)
		}
	}
	// 对角线合同在索引检索路径同样成立（预归一化点积 = 余弦）
	idx := NewIndex()
	idx.Replace("d", []Entry{textEntry("d", 0, dim4(2, 3, 4, 5))})
	hits := idx.Search(dim4(2, 3, 4, 5), 1, "text", "go-hash-degraded")
	if len(hits) != 1 || math.Abs(hits[0].Score-1) > 1e-9 {
		t.Fatalf("查询向量 == 条目向量时检索分数应为 1，实际 %+v", hits)
	}
}

// ============================================================================
// 空间闸门与边界（对标 Java InMemoryVectorIndex 的双闸门）
// ============================================================================

// TestSearchSpaceGate 空间过滤：(source_type, model_key) 双闸门——
// 不同空间的向量不可比，混 space 命中是正确性 bug 而非性能问题。
func TestSearchSpaceGate(t *testing.T) {
	idx := NewIndex()
	idx.Replace("doc-t", []Entry{textEntry("doc-t", 0, dim4(1, 0, 0, 0))})
	idx.Replace("doc-i", []Entry{{
		DocID:      "doc-i",
		ChunkIdx:   0,
		Vector:     dim4(1, 0, 0, 0),
		SourceType: "image",
		ModelKey:   "clip-stub",
	}})

	// 同向量、不同空间：互不可见
	if hits := idx.Search(dim4(1, 0, 0, 0), 10, "text", "go-hash-degraded"); len(hits) != 1 || hits[0].Entry.DocID != "doc-t" {
		t.Fatalf("text 空间只应命中 doc-t，实际 %+v", hits)
	}
	if hits := idx.Search(dim4(1, 0, 0, 0), 10, "image", "clip-stub"); len(hits) != 1 || hits[0].Entry.DocID != "doc-i" {
		t.Fatalf("image 空间只应命中 doc-i，实际 %+v", hits)
	}
	// 空 modelKey = 不过滤模态（全空间扫描——Java 端同语义）
	if got := len(idx.Search(dim4(1, 0, 0, 0), 10, "", "")); got != 2 {
		t.Fatalf("空过滤应命中全部 2 条，实际 %d", got)
	}
	// 不存在的空间：空结果而非 panic
	if got := len(idx.Search(dim4(1, 0, 0, 0), 10, "audio", "x")); got != 0 {
		t.Fatalf("不存在的空间应 0 命中，实际 %d", got)
	}
}

// TestSearchDimensionMismatch 维度不匹配的条目跳过而非 panic——
// 索引可能同时存在 512 维（text）与 4 维（测试），点积必须先验维。
func TestSearchDimensionMismatch(t *testing.T) {
	idx := NewIndex()
	idx.Replace("d4", []Entry{textEntry("d4", 0, dim4(1, 0, 0, 0))})
	idx.Replace("d2", []Entry{textEntry("d2", 0, []float64{1, 0})})
	// 4 维查询：d2 条目静默跳过
	hits := idx.Search(dim4(1, 0, 0, 0), 10, "text", "go-hash-degraded")
	if len(hits) != 1 || hits[0].Entry.DocID != "d4" {
		t.Fatalf("维度不匹配应跳过，只命中 d4，实际 %+v", hits)
	}
}

// TestSearchZeroVector 零向量的两个安全合同：
// 余弦无意义 → 0（而非 NaN）；零向量条目点积=0 非 NaN，不污染结果排序。
func TestSearchZeroVector(t *testing.T) {
	if got := CosineSimilarity(dim4(0, 0, 0, 0), dim4(1, 0, 0, 0)); got != 0 {
		t.Fatalf("零向量余弦应返回 0，实际 %v", got)
	}
	idx := NewIndex()
	idx.Replace("z", []Entry{textEntry("z", 0, dim4(0, 0, 0, 0))})
	hits := idx.Search(dim4(1, 0, 0, 0), 10, "text", "go-hash-degraded")
	if len(hits) != 1 {
		t.Fatalf("零向量条目应命中（score=0 排底），实际 %d 条", len(hits))
	}
	if math.IsNaN(hits[0].Score) || hits[0].Score != 0 {
		t.Fatalf("零向量条目分数应为 0（非 NaN），实际 %v", hits[0].Score)
	}
}

// TestSearchTopKTruncation Top-K 截断与降序排序（4.2 并行化前的串行基线锁）。
func TestSearchTopKTruncation(t *testing.T) {
	idx := NewIndex()
	idx.Replace("doc", []Entry{
		textEntry("doc", 0, dim4(1, 0, 0, 0)),   // 与查询夹角 0
		textEntry("doc", 1, dim4(1, 1, 0, 0)),   // 45°
		textEntry("doc", 2, dim4(0, 1, 0, 0)),   // 90°
		textEntry("doc", 3, dim4(-1, 0, 0, 0)),  // 180°（负相关垫底）
	})
	hits := idx.Search(dim4(1, 0, 0, 0), 2, "text", "go-hash-degraded")
	if len(hits) != 2 {
		t.Fatalf("topK=2 应恰好返回 2 条，实际 %d", len(hits))
	}
	if hits[0].Entry.ChunkIdx != 0 || hits[1].Entry.ChunkIdx != 1 {
		t.Fatalf("应按分数降序取最相关两条，实际 chunk 序 [%d, %d]",
			hits[0].Entry.ChunkIdx, hits[1].Entry.ChunkIdx)
	}
	if hits[0].Score <= hits[1].Score {
		t.Fatalf("结果须降序：%v 先于 %v", hits[0].Score, hits[1].Score)
	}
}

// TestGetAllEntriesSnapshot GetAllEntries 返回快照副本——
// 修改返回切片不影响索引内部状态（防御性拷贝合同）。
func TestGetAllEntriesSnapshot(t *testing.T) {
	idx := NewIndex()
	idx.Replace("d", []Entry{textEntry("d", 0, dim4(1, 0, 0, 0))})

	snapshot := idx.GetAllEntries()
	snapshot[0].DocID = "tampered"
	snapshot[0].Vector[0] = 999

	fresh := idx.GetAllEntries()
	if fresh[0].DocID != "d" || fresh[0].Vector[0] != 1 {
		t.Fatalf("外部修改快照不应影响索引内部：DocID=%s Vector[0]=%v",
			fresh[0].DocID, fresh[0].Vector[0])
	}
}
