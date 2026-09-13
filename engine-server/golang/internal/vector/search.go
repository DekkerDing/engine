// search.go —— 并行分片检索与稳定定序（任务 4.2）。
//
// 与 index.go 的关系：index.go 定义存储结构（Index/Replace/Remove）与串行
// Search（对拍基准）；本文件加"生产轨"——goroutine 分片并行扫描 +
// 局部 Top-K 归并，以及串行/并行共用的稳定定序器。

package vector

import (
	"math"
	"runtime"
	"sort"
	"sync"
)

// ============================================================================
// 稳定定序（串行/并行共用的排序合同）
// ============================================================================

// quantize 分数量化：把浮点分数吸附到 1e-6 精度的整数格点上。
//
// 【为什么"容差定序"要靠量化而不是直接比较】
//   直觉写法是 if |a-b| <= 1e-6 { 按文档序 } else { 按分数序 }——
//   但这个比较不满足传递性：a≈b、b≈c 推不出 a≈c（1e-6 的差值可以链式
//   累积）。非传递比较喂给 sort 是未定义行为——同一份数据两次排序
//   可能产出不同顺序（恰好背叛我们要消灭的"并行不确定性"）。
//   量化成整数格点后是严格全序（整数比较天然传递），浮点噪声
//   （并行分片不改单条点积的累加顺序，噪声只在跨实现比较时出现）
//   被 1e-6 格点吸收，平分落判到 document_id/chunk_index。
//
// 【教学注释 · 这是浮点工程的标准手法】
//   Java 端做"分数容差聚合"时同样要量化（BigDecimal/整数格点），
//   "epsilon 比较"只能用于一次性判定，不能作为排序键。
func quantize(score float64) float64 {
	return math.Round(score * 1e6) // 分数 ∈ [-1,1] → 格点值 ∈ [-1e6,1e6]，float64 整数部分精确到 2^53
}

// hitLess 命中全序：量化分数降序 → document_id 字典序 → chunk_index 升序。
// sort.Slice 的 less 语义：return true 表示 i 应排在 j 前。
func hitLess(a, b Hit) bool {
	qa, qb := quantize(a.Score), quantize(b.Score)
	if qa != qb {
		return qa > qb // 分数降序（量化后严格可比）
	}
	if a.Entry.DocID != b.Entry.DocID {
		return a.Entry.DocID < b.Entry.DocID // 平分第一 tiebreak：文档字典序
	}
	return a.Entry.ChunkIdx < b.Entry.ChunkIdx // 平分第二 tiebreak：块序号
}

// sortHits 按全序排序（原地）。串行 Search 与并行归并用同一函数——
// 对拍测试的意义前提：两边定序合同必须逐字相同。
func sortHits(hits []Hit) {
	sort.Slice(hits, func(i, j int) bool { return hitLess(hits[i], hits[j]) })
}

// ============================================================================
// 并行分片检索
// ============================================================================

// SearchParallel 并行 Top-K 余弦检索——生产轨。
//
// 【三阶段结构】
//   1. 快照（持锁）：空间过滤 + 维度校验，捞全部候选——锁只盖数据采集，
//      不盖计算（否则并行度归零：所有 goroutine 排队等一把锁）。
//   2. 分片计算（无锁）：workers 个 goroutine 各扫一段，各自产出局部 Top-K。
//   3. 归并（无锁）：局部 Top-K 拼接 → 全序排序 → 截断全局 Top-K。
//
// 【正确性论证 · 为什么局部 Top-K 的并集包含全局 Top-K】
//   全局第 k 名在任何分片内的名次都不会低于它全局的名次——
//   把它单独拿出来看，它在自己分片里排第几，全局就至多排第几
//   （其他分片的候选只会把它"顶"到更靠后，不会更靠前）。
//   所以每个分片保留前 K 个，全局 Top-K 必然全数存活到归并阶段。
//
// 【教学注释 · sync.WaitGroup ≈ Java 的 CountDownLatch】
//   wg.Add(1) 计数 +1，goroutine 里 defer wg.Done() 计数 -1，
//   wg.Wait() 阻塞到计数归零——一次性等待 N 个并发任务的完成。
//   Java 里对应 CountDownLatch(N)/countDown()/await()。
//   Go 的惯用法是把 Add 放在启动 goroutine 的那一行之前（不放在
//   goroutine 内部）——否则 Wait 可能先于 Add 执行而直接通过。
func (idx *Index) SearchParallel(queryVec []float64, topK int, sourceType, modelKey string) []Hit {
	return idx.searchParallel(queryVec, topK, sourceType, modelKey, 0) // 0 = 自动（NumCPU）
}

// searchParallel workers 参数化的内部实现——测试可强制 workers=1（退化串行）
// 或指定分片数，验证分片策略不影响结果。workers<=0 时取 runtime.NumCPU()。
func (idx *Index) searchParallel(queryVec []float64, topK int, sourceType, modelKey string, workers int) []Hit {
	if topK <= 0 || len(queryVec) == 0 {
		return nil
	}

	// 归一化查询向量（锁外做——不依赖索引状态）
	queryNorm := Normalize(queryVec)

	// ---- 阶段一：快照候选（持锁，只采集不计算）----
	// indexedEntry 是值拷贝，但其中的 Vector/scoringVec 切片头指向共享底层数组——
	// 并行读取是安全的（没有人会改已入库条目的向量：Replace 是整体替换，
	// 旧切片不会被原地写入）。这是 Go "读写分离"的最常见形态：
	// 写 = 换引用（持锁），读 = 沿引用取不可变数据（无锁）。
	idx.mu.Lock()
	candidates := make([]indexedEntry, 0, idx.size)
	for _, entries := range idx.byDocument {
		for _, item := range entries {
			if sourceType != "" && item.SourceType != sourceType {
				continue
			}
			if modelKey != "" && item.ModelKey != modelKey {
				continue
			}
			if len(item.scoringVec) != len(queryNorm) {
				continue
			}
			candidates = append(candidates, item)
		}
	}
	idx.mu.Unlock()

	if len(candidates) == 0 {
		return nil
	}

	// ---- 阶段二：分片并行打分 ----
	if workers <= 0 {
		workers = runtime.NumCPU()
	}
	if workers > len(candidates) {
		workers = len(candidates) // 分片数不超过候选数（空片无意义）
	}
	shardSize := (len(candidates) + workers - 1) / workers // 向上取整分片

	localHits := make([][]Hit, workers)
	var wg sync.WaitGroup
	for w := 0; w < workers; w++ {
		start := w * shardSize
		end := start + shardSize
		if start >= len(candidates) {
			break // 尾部空片（向上取整可能造出）——不启动 goroutine
		}
		if end > len(candidates) {
			end = len(candidates)
		}

		wg.Add(1)
		// 【教学注释 · 显式传参 vs 闭包捕获】把 shard 与输出槽作为参数
		// 传入而非直接捕获循环变量——老版本 Go（<1.22）的 for 循环变量
		// 是整个循环共享一个实例，闭包捕获会拿到"最终值"；显式传参
		// 在函数调用瞬间固化副本，任何版本语义一致。
		go func(shard []indexedEntry, out *[]Hit) {
			defer wg.Done()
			hits := make([]Hit, 0, len(shard))
			for _, item := range shard {
				score := DotProduct(item.scoringVec, queryNorm)
				if !math.IsNaN(score) {
					hits = append(hits, Hit{Entry: item.Entry, Score: score})
				}
			}
			sortHits(hits) // 局部也用同一全序——局部 Top-K 是全局 Top-K 的超集的前提
			if topK < len(hits) {
				hits = hits[:topK]
			}
			*out = hits // 各 goroutine 写各自的槽——无竞争（wg 保证读在写后）
		}(candidates[start:end], &localHits[w])
	}
	wg.Wait()

	// ---- 阶段三：归并局部 Top-K → 全局排序 → 截断 ----
	var merged []Hit
	for _, hits := range localHits {
		if len(hits) > 0 {
			merged = append(merged, hits...)
		}
	}
	sortHits(merged)
	if topK < len(merged) {
		merged = merged[:topK]
	}
	return merged
}
