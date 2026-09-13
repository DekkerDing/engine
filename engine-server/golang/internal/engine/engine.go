// Package engine —— 引擎门面：持有全部工具实例，向 Router 注册方法。
//
// 【教学注释 · Go vs Java 的"组件装配"】
//   Java/Spring：@Component 扫描 + @Autowired 注入——装配发生在框架内部；
//   Go         ：Engine 结构体显式持有工具字段，New() 显式构造，
//                RegisterAll 显式注册——装配全部发生在光天化日之下。
//   对应关系：Engine ≈ @Configuration + 各 @Bean 方法；
//             RegisterAll ≈ BeanNameUrlHandlerMapping 的路由注册表。
//
// 【场景挂载点（任务驱动，逐步填满）】
//   1.2  sys.ping            存活握手
//   1.3  sys.shutdown        优雅退出（由 Server 主循环特判）
//   3.1  text.*              分块/分词/关键词（高 CPU · 手写 internal/text 采用）
//   3.2  hashing.generate    哈希向量降级 + sys.stats
//   4.x  vector.*            向量索引副本 + 并行扫描（高计算 · S1）
package engine

import (
	"encoding/json"
	"strconv"

	"engine/gotoolbox/internal/hashing"
	"engine/gotoolbox/internal/protocol"
	"engine/gotoolbox/internal/router"
	"engine/gotoolbox/internal/text"
	"engine/gotoolbox/internal/vector"
)

// Version 引擎版本：构建期可注入
// go build -ldflags "-X engine/gotoolbox/internal/engine.Version=1.0.0"
// 【教学注释】对比 Java 的 -X 不存在等价物——版本常注入 jar 清单或
// 环境变量；Go 的链接器符号注入是最轻的官方姿势。
var Version = "0.2.0"

// Engine 工具实例的宿主——各场景包的字段一处可见全部依赖。
type Engine struct {
	chunker *text.Chunker
	index   *vector.Index
}

// New 构造引擎。chunker 用 Java 端对齐的默认值（target=400/overlap=1，
// 与 engine.documents.chunk.* 配置语义一致——对拍基准同参）。
func New() *Engine {
	return &Engine{
		chunker: text.NewChunker(text.DefaultChunkSize, text.DefaultOverlapSentences),
		index:   vector.NewIndex(),
	}
}

// textChunkParams / textTokenizeParams / textKeywordsParams
// 请求参数的强类型视图——json.Unmarshal 按 tag 取值，缺省字段零值。
//
// 【教学注释 · 缺省值的处理】
//   Java 侧 Map params.get("top_k") 为 null 时手动给默认值；
//   Go 侧 Unmarshal 后零值（0/""）即"未传"，同样手动兜底——
//   两边的防御姿势一致：协议边界永不信任对端必传。
type textChunkParams struct {
	DocumentID string `json:"document_id"`
	Text       string `json:"text"`
	TargetSize int    `json:"target_size"`
	Overlap    int    `json:"overlap_sentences"`
}

type textTokenizeParams struct {
	Text string `json:"text"`
	Mode string `json:"mode"`
}

type textKeywordsParams struct {
	Text string `json:"text"`
	TopK int    `json:"top_k"`
}

// RegisterAll 把引擎全部工具方法登记进注册表。
// 【教学注释】方法指针 r.Register("text.chunk", e.textChunk)
// 就是 Go 的"方法引用"——无需函数式接口包装。
func (e *Engine) RegisterAll(r *router.Router) {
	// ---- sys.* 内置方法 ----
	// sys.ping：Java 侧启动握手 / 健康探测的存活帧。
	// 【教学注释 · 闭包即 handler】这里传的匿名函数捕获了 Version——
	// Go 闭包对变量的捕获是引用语义，对比 Java lambda 只能捕获终态变量。
	r.Register("sys.ping", func(params json.RawMessage) (interface{}, *protocol.ErrorObject) {
		return map[string]interface{}{
			"status":  "pong",
			"version": Version,
		}, nil
	})

	// ---- text.* 场景一：文本处理（任务 3.1）----
	r.Register("text.chunk", e.textChunk)
	r.Register("text.tokenize", e.textTokenize)
	r.Register("text.keywords", e.textKeywords)

	// ---- hashing.* 场景三：降级向量化（任务 3.2）----
	r.Register("hashing.generate", e.hashingGenerate)

	// ---- vector.* 场景二：索引副本（任务 4.1；search 并行扫描 4.2）----
	r.Register("vector.insert", e.vectorInsert)
	r.Register("vector.delete", e.vectorDelete)
	r.Register("vector.similarity", e.vectorSimilarity)
	r.Register("vector.search", e.vectorSearch)

	// ---- sys.stats 引擎自描述（任务 3.2）----
	// tools 取 r.Methods()（闭包延迟求值——运行期调用时已含 sys.stats 自身）。
	r.Register("sys.stats", func(params json.RawMessage) (interface{}, *protocol.ErrorObject) {
		return map[string]interface{}{
			"engine":       "gotoolbox",
			"version":      Version,
			"vector_count": e.index.Size(), // 4.1 起回填真实行数（闭包引 e，运行期求值）
			"tools":        r.Methods(),
		}, nil
	})
}

// vectorInsertParams vector.insert 的参数视图。
// 条目直接复用 vector.Entry 的 json tag（chunk_idx/vector/source_type/model_key）——
// 协议字段与领域模型同构时不必再造 DTO，Go 的 tag 同时服务两个方向。
type vectorInsertParams struct {
	DocumentID string         `json:"document_id"`
	Entries    []vector.Entry `json:"entries"`
}

// vectorInsert vector.insert：按 document_id 幂等替换全部条目。
// 语义对标 Java VectorStore.save 的"先清后写"——重传不留旧向量。
func (e *Engine) vectorInsert(params json.RawMessage) (interface{}, *protocol.ErrorObject) {
	var p vectorInsertParams
	if err := json.Unmarshal(params, &p); err != nil {
		return nil, &protocol.ErrorObject{Code: 1002, Message: "vector.insert 参数解析失败: " + err.Error()}
	}
	if p.DocumentID == "" {
		return nil, &protocol.ErrorObject{Code: 1002, Message: "vector.insert 缺少必填参数 document_id"}
	}
	// 条目级 doc_id 兜底：协议允许条目不重复携带（顶层统一指定），
	// 未填时继承——冗余容错，两边都填且不一致时以条目自身为准
	for i := range p.Entries {
		if p.Entries[i].DocID == "" {
			p.Entries[i].DocID = p.DocumentID
		}
	}
	e.index.Replace(p.DocumentID, p.Entries)
	return map[string]interface{}{
		"inserted": len(p.Entries), // 0 = "清空该文档"（幂等替换的边界语义）
		"total":    e.index.Size(),
	}, nil
}

// vectorDeleteParams vector.delete 的参数视图。
type vectorDeleteParams struct {
	DocumentID string `json:"document_id"`
}

// vectorDelete vector.delete：删除一个文档的全部条目（幂等——删不存在的不报错）。
func (e *Engine) vectorDelete(params json.RawMessage) (interface{}, *protocol.ErrorObject) {
	var p vectorDeleteParams
	if err := json.Unmarshal(params, &p); err != nil {
		return nil, &protocol.ErrorObject{Code: 1002, Message: "vector.delete 参数解析失败: " + err.Error()}
	}
	if p.DocumentID == "" {
		return nil, &protocol.ErrorObject{Code: 1002, Message: "vector.delete 缺少必填参数 document_id"}
	}
	e.index.Remove(p.DocumentID)
	return map[string]interface{}{
		"deleted": p.DocumentID,
		"total":   e.index.Size(),
	}, nil
}

// vectorSimilarityParams vector.similarity 的参数视图。
type vectorSimilarityParams struct {
	VectorA []float64 `json:"vector_a"`
	VectorB []float64 `json:"vector_b"`
}

// vectorSimilarity vector.similarity：两向量余弦，值域 [-1, 1]。
// 维度校验在 handler 边界做——库函数 CosineSimilarity 对维不一致是 panic
//（编程错误），协议边界必须翻译成 1002 错误帧（对端数据错误）。
func (e *Engine) vectorSimilarity(params json.RawMessage) (interface{}, *protocol.ErrorObject) {
	var p vectorSimilarityParams
	if err := json.Unmarshal(params, &p); err != nil {
		return nil, &protocol.ErrorObject{Code: 1002, Message: "vector.similarity 参数解析失败: " + err.Error()}
	}
	if len(p.VectorA) == 0 || len(p.VectorB) == 0 {
		return nil, &protocol.ErrorObject{Code: 1002, Message: "vector.similarity 缺少必填参数 vector_a/vector_b"}
	}
	if len(p.VectorA) != len(p.VectorB) {
		return nil, &protocol.ErrorObject{Code: 1002, Message: "vector.similarity 维度不一致: " +
			strconv.Itoa(len(p.VectorA)) + " vs " + strconv.Itoa(len(p.VectorB))}
	}
	return map[string]interface{}{
		"score": vector.CosineSimilarity(p.VectorA, p.VectorB),
	}, nil
}

// vectorSearchParams vector.search 的参数视图。
// source_type/model_key 空串 = 不过滤（与 Index 空间闸门的空值语义一致）。
type vectorSearchParams struct {
	Query      []float64 `json:"query"`
	TopK       int       `json:"top_k"`
	SourceType string    `json:"source_type"`
	ModelKey   string    `json:"model_key"`
}

// vectorSearch vector.search：并行分片 Top-K 余弦检索（高计算场景二的主方法）。
// 命中只回 (document_id, chunk_index, score) 三元组——Go 端是计算副本，
// 条目的完整信息（文本/降级标志等）由 Java 端主索引持有，按三元组回表。
type vectorSearchHit struct {
	DocID    string  `json:"document_id"`
	ChunkIdx int     `json:"chunk_index"`
	Score    float64 `json:"score"`
}

func (e *Engine) vectorSearch(params json.RawMessage) (interface{}, *protocol.ErrorObject) {
	var p vectorSearchParams
	if err := json.Unmarshal(params, &p); err != nil {
		return nil, &protocol.ErrorObject{Code: 1002, Message: "vector.search 参数解析失败: " + err.Error()}
	}
	if len(p.Query) == 0 {
		return nil, &protocol.ErrorObject{Code: 1002, Message: "vector.search 缺少必填参数 query"}
	}
	topK := p.TopK
	if topK <= 0 {
		topK = 10 // 与 Java 门面 search(query, topK) 的调用惯例默认一致
	}
	hits := e.index.SearchParallel(p.Query, topK, p.SourceType, p.ModelKey)
	result := make([]vectorSearchHit, 0, len(hits)) // 空切片序列化为 [] 而非 null
	for _, h := range hits {
		result = append(result, vectorSearchHit{
			DocID:    h.Entry.DocID,
			ChunkIdx: h.Entry.ChunkIdx,
			Score:    h.Score,
		})
	}
	return map[string]interface{}{
		"hits":  result,
		"count": len(result),
	}, nil
}

// hashingGenerateParams hashing.generate 的参数视图。
// normalize 缺省 false——bool 零值即"未传"，与 Java 门面 embedBatch
// 显式传 true 的调用习惯互补（门面两级方法已把默认语义钉在 Java 侧）。
type hashingGenerateParams struct {
	Text      string `json:"text"`
	Dim       int    `json:"dim"`
	Normalize bool   `json:"normalize"`
}

// hashingGenerate 哈希向量降级：SHA-256 轮转产出确定性伪向量。
// 响应契约对齐 GoProtocol.HashResult：{vector, dim, degraded:true, text}。
func (e *Engine) hashingGenerate(params json.RawMessage) (interface{}, *protocol.ErrorObject) {
	var p hashingGenerateParams
	if err := json.Unmarshal(params, &p); err != nil {
		return nil, &protocol.ErrorObject{Code: 1002, Message: "hashing.generate 参数解析失败: " + err.Error()}
	}
	if p.Text == "" {
		return nil, &protocol.ErrorObject{Code: 1002, Message: "hashing.generate 缺少必填参数 text"}
	}
	dim := p.Dim
	if dim <= 0 {
		dim = 512 // 默认对齐 bge-small-zh-v1.5（Java GoToolboxProvider.dimension() 同值）
	}
	var vec []float64
	if p.Normalize {
		vec = hashing.GenerateNormalized(p.Text, dim)
	} else {
		vec = hashing.Generate(p.Text, dim)
	}
	return map[string]interface{}{
		"vector":   vec,
		"dim":      dim,
		"degraded": true,
		"text":     p.Text,
	}, nil
}

// textChunk 分块：句子对齐滑动窗口 + rune 感知硬切（中文正确性硬约束）。
// 响应契约对齐 Java GoProtocol.ChunkResult：{chunks:[...], count:n}。
func (e *Engine) textChunk(params json.RawMessage) (interface{}, *protocol.ErrorObject) {
	var p textChunkParams
	if err := json.Unmarshal(params, &p); err != nil {
		return nil, &protocol.ErrorObject{Code: 1002, Message: "text.chunk 参数解析失败: "+err.Error()}
	}
	if p.Text == "" {
		return nil, &protocol.ErrorObject{Code: 1002, Message: "text.chunk 缺少必填参数 text"}
	}
	// 目标块长/重叠可按请求覆盖（Java 端按配置全局定，Go 端帧级可调——对拍时同参即可）
	// 【教学注释 · 零值歧义】int 零值 0 分不清"没传"和"显式传 0"——
	// v1 语义：>0 才视为覆盖（overlap 显式 0 的请求需 v2 用 *int 指针判别）。
	chunker := e.chunker
	if p.TargetSize > 0 || p.Overlap > 0 {
		target := p.TargetSize
		if target <= 0 {
			target = text.DefaultChunkSize
		}
		overlap := p.Overlap
		if overlap <= 0 {
			overlap = text.DefaultOverlapSentences
		}
		chunker = text.NewChunker(target, overlap)
	}
	chunks := chunker.Chunk(p.DocumentID, p.Text)
	return map[string]interface{}{
		"chunks": chunks,
		"count":  len(chunks),
	}, nil
}

// textTokenize 分词：search=bigram / exact=去重 / 默认全量。
// 响应契约对齐 GoProtocol.TokenizeResult：{tokens:[...], count:n, mode:"..."}。
func (e *Engine) textTokenize(params json.RawMessage) (interface{}, *protocol.ErrorObject) {
	var p textTokenizeParams
	if err := json.Unmarshal(params, &p); err != nil {
		return nil, &protocol.ErrorObject{Code: 1002, Message: "text.tokenize 参数解析失败: "+err.Error()}
	}
	if p.Text == "" {
		return nil, &protocol.ErrorObject{Code: 1002, Message: "text.tokenize 缺少必填参数 text"}
	}
	if p.Mode == "" {
		p.Mode = "search" // 与 Java 门面 tokenize() 的默认一致
	}
	tokens := e.chunker.Tokenize(p.Text, p.Mode)
	return map[string]interface{}{
		"tokens": tokens,
		"count":  len(tokens),
		"mode":   p.Mode,
	}, nil
}

// textKeywords 关键词：TF 频次 Top-K，权重归一 [0,1]。
// 响应契约对齐 GoProtocol.KeywordsResult：{keywords:[{term,weight}]}。
//
// 【教学注释 · 关键词结构体的 json tag】
//   Keyword 的 Term/Weight 无 tag 时序列化为 "Term"/"Weight"（大写开头）；
//   Java 端 DTO 期待小写 term/weight——跨语言契约必须钉 tag，这是
//   "协议是双方的事"最直接的教训。
func (e *Engine) textKeywords(params json.RawMessage) (interface{}, *protocol.ErrorObject) {
	var p textKeywordsParams
	if err := json.Unmarshal(params, &p); err != nil {
		return nil, &protocol.ErrorObject{Code: 1002, Message: "text.keywords 参数解析失败: "+err.Error()}
	}
	if p.Text == "" {
		return nil, &protocol.ErrorObject{Code: 1002, Message: "text.keywords 缺少必填参数 text"}
	}
	topK := p.TopK
	if topK <= 0 {
		topK = 10 // Java 门面 keywords(text, topK) 的调用惯例默认
	}
	type kwItem struct {
		Term   string  `json:"term"`
		Weight float64 `json:"weight"`
	}
	keywords := e.chunker.Keywords(p.Text, topK)
	items := make([]kwItem, 0, len(keywords)) // 空切片：JSON 序列化为 [] 而非 null
	for _, k := range keywords {
		items = append(items, kwItem{Term: k.Term, Weight: k.Weight})
	}
	return map[string]interface{}{
		"keywords": items,
	}, nil
}
