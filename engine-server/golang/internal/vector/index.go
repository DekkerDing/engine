// Package vector —— 内存向量索引（对标 Java 端的 InMemoryVectorIndex）。
//
// 【教学注释 · Go 的 map 并发安全】
//   Go 的 map 不是并发安全的——多 goroutine 同时读写会触发 fatal error
//   （concurrent map read and map write），这是不可恢复的 panic，
//   不像 Java 的 ConcurrentModificationException 能被 catch。
//   所以 Index 的所有公开方法都需要互斥锁保护——见下面的 sync.Mutex。
//
// 【教学注释 · sync.Mutex vs sync.RWMutex】
//   Mutex（互斥锁）    ：读写互斥，同一时刻只允许一个 goroutine
//   RWMutex（读写锁）：多读不互斥，写与读互斥
//   这里选 Mutex：向量检索的"读"路径计算量远大于锁开销，
//   读写锁的额外复杂度（锁升级、防止写饥饿）不值得。
//   对万级索引的毫秒级检索来说，持锁时间 = 检索时间本身，
//   读写锁提升不了并发度。YAGNI。

package vector

import (
	"math"
	"sync"
)

// ============================================================================
// Entry —— 向量条目的领域模型（与 Java 端 VectorEntry 字段对齐）
// ============================================================================

// Entry 一条向量索引条目——文档中的一个语义块。
//
// 【教学注释 · Go 结构体 vs Java 类】
//   Go 没有 private/protected/public 关键字——首字母大小写决定可见性：
//   DocID 大写 → 导出（public），其他包可访问
//   docID 小写 → 包私有（private），仅本包可访问
//   这比 Java 更简洁，但也意味着"改名 = 改 API"（大小写切换影响外部）。
type Entry struct {
	DocID      string    `json:"doc_id"`      // 所属文档 ID
	ChunkIdx   int       `json:"chunk_idx"`   // 文档内块序号
	Vector     []float64 `json:"vector"`      // 原始向量（存储）
	SourceType string    `json:"source_type"` // "text" | "image"
	ModelKey   string    `json:"model_key"`   // 编码模型键（空间身份证）
}

// ============================================================================
// Hit —— 检索命中结果
// ============================================================================

// Hit 一次检索命中——包含条目和余弦分数。
type Hit struct {
	Entry Entry   // 命中的条目
	Score float64 // 余弦相似度 ∈ [-1, 1]
}

// ============================================================================
// indexedEntry —— 内部索引条目（含预归一化向量）
// ============================================================================

// indexedEntry 是 Entry + 预归一化打分向量的包装。
// 归一化只在插入时做一次，检索时直接用点积（=余弦），
// 避免每次检索都重复归一化查询向量和库向量。
//
// 【教学注释 · Go 的小写结构体 = 包私有】
//   indexedEntry 首字母小写 → 只在 vector 包内可见。
//   外部（测试除外）不关心我们的内部存储结构——
//   这是 Go 的封装方式：用可见性隐藏实现细节。
type indexedEntry struct {
	Entry         // 【教学注释 · Go 的结构体嵌入 = Java 的继承（组合）】
	              // Entry 不加字段名直接嵌入 → indexedEntry 自动拥有 Entry 的所有字段。
	              // 这不是继承（Go 没有继承），而是"语法糖的组合"——
	              // 访问 indexedEntry.DocID 实际访问 indexedEntry.Entry.DocID。
	scoringVec []float64 // 预归一化的打分向量（L2 单位长度，点积=余弦）
}

// ============================================================================
// Index —— 内存向量索引
// ============================================================================

// Index 内存中的向量索引——按文档分组存储，支持全库暴力检索。
//
// 【与 Java 端 InMemoryVectorIndex 的对照】
//   Java 端用 LinkedHashMap<String, List<IndexedEntry>> 按文档分组；
//   Go 端用 map[string][]indexedEntry —— 语义完全一致。
//   删除和替换都是 O(文档块数)，暴力检索是 O(总块数)。
//
// 【教学注释 · Go 的"零值可用"——sync.Mutex 不需要初始化】
//   var mu sync.Mutex 之后直接 mu.Lock()——不需要 NewMutex()。
//   这是 Go 的"Mutex 零值就是未加锁的可用状态"。
//   但 Index 的 byDocument map 必须 make —— map 的零值是 nil，
//   nil map 写入会 panic（读取返回零值，不 panic）。
type Index struct {
	mu         sync.Mutex               // 互斥锁：保护所有读写
	byDocument map[string][]indexedEntry // map[文档ID]内部索引条目列表
	size       int                      // 条目总数（所有文档的块数之和）
}

// NewIndex 创建空的向量索引。
func NewIndex() *Index {
	return &Index{
		byDocument: make(map[string][]indexedEntry),
	}
}

// ============================================================================
// 写入路径
// ============================================================================

// Replace 写入/覆盖一个文档的全部向量条目（幂等）。
//
// 【教学注释 · defer 的"延迟执行"机制】
//   defer idx.mu.Unlock() 会在 Replace 返回前执行——
//   不管函数是正常返回还是 panic，defer 都会执行（栈展开）。
//   这等价于 Java 的 try-finally，但写在加锁的下一行更紧凑。
//
//   但这里用的是显式 Lock/Unlock（不是 defer）：
//   defer 有微小开销（纳秒级），在热路径上不值得；
//   且 Lock/Unlock 之间的代码很短、不会 panic，defer 的"安全网"价值不大。
func (idx *Index) Replace(documentID string, entries []Entry) {
	idx.mu.Lock()
	defer idx.mu.Unlock()

	// 构建内部条目：对每个 Entry 做预归一化
	indexed := make([]indexedEntry, len(entries))
	for i, entry := range entries {
		// 预归一化：插入时做一次，检索时直接用点积
		normVec := Normalize(entry.Vector)
		indexed[i] = indexedEntry{
			Entry:      entry,
			scoringVec: normVec,
		}
	}

	// 替换旧条目并更新计数
	old, existed := idx.byDocument[documentID]
	idx.byDocument[documentID] = indexed
	if existed {
		idx.size -= len(old)
	}
	idx.size += len(indexed)
}

// Remove 删除一个文档的全部向量条目（文档删除联动清理），返回删除的条目数。
// 幂等：删除不存在的文档返回 0（Java 侧"幂等双删无害"同款）。
func (idx *Index) Remove(documentID string) int {
	idx.mu.Lock()
	defer idx.mu.Unlock()

	old, existed := idx.byDocument[documentID]
	if !existed {
		return 0
	}
	idx.size -= len(old)
	delete(idx.byDocument, documentID) // 【教学注释】delete 是内置函数，删 map 的键
	return len(old)
}

// ============================================================================
// 读取路径
// ============================================================================

// Size 返回索引中的条目总数。
func (idx *Index) Size() int {
	idx.mu.Lock()
	defer idx.mu.Unlock()
	return idx.size
}

// IsEmpty 索引是否为空。
func (idx *Index) IsEmpty() bool {
	idx.mu.Lock()
	defer idx.mu.Unlock()
	return len(idx.byDocument) == 0
}

// Search Top-K 余弦检索——暴力扫描全部条目，按分数降序返回。
//
// 【算法】对每个匹配 (sourceType, modelKey) 的条目：
//   1. 归一化查询向量（一次，检索路径上的唯一分配）
//   2. 点积打分（预归一化向量 × 归一化查询向量 = 余弦）
//   3. Top-K 截断
//
// 【教学注释 · Go 的切片排序 vs Java 的 PriorityQueue】
//   Java 用 PriorityQueue 做 Top-K（O(K log N)），Go 这里先全量排序再截断——
//   因为暴力扫 + 全量排序的时间已经由点积主导（O(N×D)），
//   排序的 O(N log N) 是 low-order term。对万级条目来说，
//   sort.Slice 的简洁性胜过 PriorityQueue 的复杂度。
func (idx *Index) Search(queryVec []float64, topK int, sourceType, modelKey string) []Hit {
	idx.mu.Lock()
	defer idx.mu.Unlock()

	if topK <= 0 || len(queryVec) == 0 {
		return nil
	}

	// 归一化查询向量（一次分配，所有条目共用）
	queryNorm := Normalize(queryVec)

	var hits []Hit

	for _, entries := range idx.byDocument {
		for _, item := range entries {
			// 空间过滤（对标 Java 端的双闸门逻辑）
			// sourceType 为空 = 不过滤；modelKey 为空 = 不过滤
			if sourceType != "" && item.SourceType != sourceType {
				continue // 模态不匹配：跳过
			}
			if modelKey != "" && item.ModelKey != modelKey {
				continue // 模型不匹配：跳过（不同空间的向量不可比）
			}
			// 维度校验：点积要求同维
			if len(item.scoringVec) != len(queryNorm) {
				continue
			}

			// 点积打分（预归一化向量 × 归一化查询 = 余弦）
			score := DotProduct(item.scoringVec, queryNorm)

			// 【教学注释 · math.IsNaN 防御】
			//   理论上归一化后的点积不应产生 NaN，但 GUARD CLAUSE 不嫌多——
			//   上游数据可能出错（全零向量归一化后可能得 NaN）。
			if !math.IsNaN(score) {
				hits = append(hits, Hit{Entry: item.Entry, Score: score})
			}
		}
	}

	// 按全序排序（量化分数降序 + 平分 document_id/chunk_index 字典序——
	// 与 SearchParallel 共用 sortHits，串行/并行定序合同逐字相同，
	// 对拍测试才有意义）
	sortHits(hits)

	// 截断 Top-K
	if topK < len(hits) {
		hits = hits[:topK]
	}

	return hits
}

// GetAllEntries 返回所有索引条目的快照（供调试/统计使用）。
//
// 【教学注释 · 浅拷贝的陷阱：切片字段共享底层数组】
//   Entry 是值类型，append 进结果时结构体被复制——但 Vector 是切片，
//   切片头拷贝后仍指向同一底层数组。只做 append 的话，调用方改
//   snapshot[0].Vector[0] 会直接污染索引内部数据。
//   "返回副本"的合同要求深拷贝：Vector 也要 make + copy。
//   （对标 Java：返回 List<VectorEntry> 时若条目持有可变 float[]，
//   同样要 Arrays.copyOf 才算真快照。）
func (idx *Index) GetAllEntries() []Entry {
	idx.mu.Lock()
	defer idx.mu.Unlock()

	var result []Entry
	for _, entries := range idx.byDocument {
		for _, item := range entries {
			entry := item.Entry // 结构体值拷贝：字段级独立
			entry.Vector = make([]float64, len(item.Vector))
			copy(entry.Vector, item.Vector)
			result = append(result, entry)
		}
	}
	return result
}