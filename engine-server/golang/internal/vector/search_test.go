package vector

import (
	"math"
	"math/rand"
	"testing"
)

// ============================================================================
// 任务 4.2 验收：随机数据与串行参考实现全量对拍（含平分定序）
// ============================================================================

// seedIndex 用固定种子的伪随机数据填索引（可复现——失败可定位到同一份数据）。
//
// 【教学注释 · 可复现的随机测试】rand.New(rand.NewSource(42)) 造出确定性
// 随机序列：同种子永远同数据。对比 Java 的 new Random(42)——概念完全一致。
// 随机测试发现问题时，种子就是"回到案发现场"的钥匙。
func seedIndex(t *testing.T, docs, chunks, dim int) *Index {
	t.Helper()
	rng := rand.New(rand.NewSource(42))
	idx := NewIndex()
	for d := 0; d < docs; d++ {
		entries := make([]Entry, chunks)
		for c := 0; c < chunks; c++ {
			vec := make([]float64, dim)
			for i := range vec {
				vec[i] = rng.NormFloat64() // 正态分布——比均匀分布更接近真实嵌入的形态
			}
			entries[c] = Entry{
				DocID:      "doc-" + string(rune('a'+d%26)) + "-" + itoaPad(d),
				ChunkIdx:   c,
				Vector:     vec,
				SourceType: "text",
				ModelKey:   "go-hash-degraded",
			}
		}
		idx.Replace(entries[0].DocID, entries)
	}
	return idx
}

// itoaPad 零填充编号（doc-0007 形态）——保证字典序 = 数值序，
// 平分定序断言可以按构造顺序推理。
func itoaPad(n int) string {
	s := ""
	for n > 0 {
		s = string(rune('0'+n%10)) + s
		n /= 10
	}
	for len(s) < 4 {
		s = "0" + s
	}
	return s
}

// TestSearchParallelMatchesSerial 核心对拍：随机数据 120 文档 × 4 块 × 32 维，
// 并行（自动 workers=NumCPU）与串行 Search 在多个 topK 档位下全量一致——
// 位置逐一对应：doc_id/chunk_idx 恒等，score 差 <1e-12（同一条目单次
// DotProduct 的乘加顺序在两条轨上相同，理论上应位级相等，容差留余地）。
func TestSearchParallelMatchesSerial(t *testing.T) {
	idx := seedIndex(t, 120, 4, 32)
	rng := rand.New(rand.NewSource(7)) // 查询向量用不同种子——避免与库内任何条目"天然同构"

	for _, topK := range []int{1, 7, 50, 480} { // 档位覆盖：极小 / 常规 / 半量 / 全量（120×4）
		query := make([]float64, 32)
		for i := range query {
			query[i] = rng.NormFloat64()
		}
		want := idx.Search(query, topK, "text", "go-hash-degraded")
		got := idx.SearchParallel(query, topK, "text", "go-hash-degraded")

		if len(got) != len(want) {
			t.Fatalf("topK=%d：并行命中数 %d 与串行 %d 不一致", topK, len(got), len(want))
		}
		for i := range want {
			if got[i].Entry.DocID != want[i].Entry.DocID || got[i].Entry.ChunkIdx != want[i].Entry.ChunkIdx {
				t.Fatalf("topK=%d 第 %d 位错位：并行 (%s,%d) vs 串行 (%s,%d)",
					topK, i, got[i].Entry.DocID, got[i].Entry.ChunkIdx,
					want[i].Entry.DocID, want[i].Entry.ChunkIdx)
			}
			if math.Abs(got[i].Score-want[i].Score) > 1e-12 {
				t.Fatalf("topK=%d 第 %d 位分数漂移：%v vs %v",
					topK, i, got[i].Score, want[i].Score)
			}
		}
	}
}

// TestSearchParallelWorkersVariants 分片数不改变结果：workers=1（退化串行）、
// 2、8、17（质数——不整除候选数，验证非均匀尾片）与自动档全数一致。
func TestSearchParallelWorkersVariants(t *testing.T) {
	idx := seedIndex(t, 30, 3, 16)
	query := []float64{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16}

	baseline := idx.searchParallel(query, 10, "text", "go-hash-degraded", 1)
	for _, workers := range []int{2, 8, 17, 0} { // 0 = 自动（NumCPU）
		got := idx.searchParallel(query, 10, "text", "go-hash-degraded", workers)
		if len(got) != len(baseline) {
			t.Fatalf("workers=%d 命中数 %d 与基线 %d 不一致", workers, len(got), len(baseline))
		}
		for i := range baseline {
			if got[i].Entry.DocID != baseline[i].Entry.DocID ||
				got[i].Entry.ChunkIdx != baseline[i].Entry.ChunkIdx ||
				math.Abs(got[i].Score-baseline[i].Score) > 1e-12 {
				t.Fatalf("workers=%d 第 %d 位与基线不一致：%+v vs %+v",
					workers, i, got[i], baseline[i])
			}
		}
	}
}

// TestSearchParallelTieBreakOrder 平分定序（spec 硬约束）：全部条目同分
// （同向量 [1,0,0,0]），乱序插入文档——结果必须按 document_id 字典序、
// 同文档内 chunk_index 升序。不确定的 map 遍历序不能渗透到输出。
func TestSearchParallelTieBreakOrder(t *testing.T) {
	idx := NewIndex()
	// 乱序写入（doc-c 先来）——若定序依赖插入序/遍历序，此测试必炸
	for _, doc := range []string{"doc-c", "doc-a", "doc-b"} {
		entries := []Entry{
			{DocID: doc, ChunkIdx: 2, Vector: dim4(1, 0, 0, 0), SourceType: "text", ModelKey: "m"},
			{DocID: doc, ChunkIdx: 0, Vector: dim4(1, 0, 0, 0), SourceType: "text", ModelKey: "m"},
			{DocID: doc, ChunkIdx: 1, Vector: dim4(1, 0, 0, 0), SourceType: "text", ModelKey: "m"},
		}
		idx.Replace(doc, entries)
	}

	hits := idx.SearchParallel(dim4(1, 0, 0, 0), 9, "text", "m")
	if len(hits) != 9 {
		t.Fatalf("应命中全部 9 条，实际 %d", len(hits))
	}
	expect := []struct {
		doc   string
		chunk int
	}{
		{"doc-a", 0}, {"doc-a", 1}, {"doc-a", 2},
		{"doc-b", 0}, {"doc-b", 1}, {"doc-b", 2},
		{"doc-c", 0}, {"doc-c", 1}, {"doc-c", 2},
	}
	for i, e := range expect {
		if hits[i].Entry.DocID != e.doc || hits[i].Entry.ChunkIdx != e.chunk {
			t.Fatalf("第 %d 位应为 (%s,%d)，实际 (%s,%d)",
				i, e.doc, e.chunk, hits[i].Entry.DocID, hits[i].Entry.ChunkIdx)
		}
	}
}

// TestSearchParallelToleranceTie 容差平分（1e-6 格点吸附）：两条目分数差
// 约 5e-7（< 容差）——严格分数序会先 doc-b（1.0）后 doc-a（≈0.9999995）；
// 容差定序把它们视为平分 → 字典序 doc-a 先。这是"容差生效"的直接证据。
func TestSearchParallelToleranceTie(t *testing.T) {
	idx := NewIndex()
	// 与查询 [1,0,0,0] 的余弦：doc-a≈0.99999950（夹角 ~0.057°），doc-b=1.0
	idx.Replace("doc-a", []Entry{
		{DocID: "doc-a", ChunkIdx: 0, Vector: dim4(1, 0.001, 0, 0), SourceType: "text", ModelKey: "m"},
	})
	idx.Replace("doc-b", []Entry{
		{DocID: "doc-b", ChunkIdx: 0, Vector: dim4(1, 0, 0, 0), SourceType: "text", ModelKey: "m"},
	})

	if math.Abs(1-CosineSimilarity(dim4(1, 0.001, 0, 0), dim4(1, 0, 0, 0))) >= 1e-6 {
		t.Fatal("前置校验失败：构造的分数差应小于 1e-6，测试前提不成立")
	}

	hits := idx.SearchParallel(dim4(1, 0, 0, 0), 2, "text", "m")
	if len(hits) != 2 {
		t.Fatalf("应命中 2 条，实际 %d", len(hits))
	}
	if hits[0].Entry.DocID != "doc-a" || hits[1].Entry.DocID != "doc-b" {
		t.Fatalf("容差平分应按字典序 doc-a 先于 doc-b（尽管分数更低），实际 [%s, %s]",
			hits[0].Entry.DocID, hits[1].Entry.DocID)
	}
	// 分数本身不被量化污染——原始分数透传给调用方（量化只用于排序键）
	if hits[1].Score != 1.0 {
		t.Fatalf("doc-b 的原始分数应恰为 1.0，实际 %v", hits[1].Score)
	}
}

// TestSearchParallelScoreGapAboveTolerance 分数差超过容差时严格按分数序——
// 容差是"吸附"不是"抹平"：2e-3 的角度差（余弦差 ~2e-6 > 1e-6）必须分出先后。
func TestSearchParallelScoreGapAboveTolerance(t *testing.T) {
	idx := NewIndex()
	idx.Replace("doc-a", []Entry{
		{DocID: "doc-a", ChunkIdx: 0, Vector: dim4(1, 0.002, 0, 0), SourceType: "text", ModelKey: "m"},
	})
	idx.Replace("doc-b", []Entry{
		{DocID: "doc-b", ChunkIdx: 0, Vector: dim4(1, 0, 0, 0), SourceType: "text", ModelKey: "m"},
	})

	hits := idx.SearchParallel(dim4(1, 0, 0, 0), 2, "text", "m")
	if hits[0].Entry.DocID != "doc-b" {
		t.Fatalf("分数差 >1e-6 应严格分数序（doc-b=1.0 先行），实际先 %s", hits[0].Entry.DocID)
	}
}

// TestSearchParallelEdges 边界合同：空查询向量 / topK<=0 / 空索引 /
// 空间过滤不命中——一律空结果而非 panic。
func TestSearchParallelEdges(t *testing.T) {
	idx := seedIndex(t, 3, 2, 8)

	if got := idx.SearchParallel(nil, 10, "text", "go-hash-degraded"); got != nil {
		t.Fatalf("空查询向量应返回 nil，实际 %v", got)
	}
	if got := idx.SearchParallel(dim4(1, 0, 0, 0), 0, "text", "go-hash-degraded"); got != nil {
		t.Fatalf("topK=0 应返回 nil，实际 %v", got)
	}
	if got := idx.SearchParallel(dim4(1, 0, 0, 0), 10, "audio", "nope"); len(got) != 0 {
		t.Fatalf("不存在的空间应 0 命中，实际 %d 条", len(got))
	}
	empty := NewIndex()
	if got := empty.SearchParallel(dim4(1, 0, 0, 0), 10, "text", "m"); got != nil {
		t.Fatalf("空索引应返回 nil，实际 %v", got)
	}
}
