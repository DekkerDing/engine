// Package text —— 文本处理工具集：分块、分词、关键词提取（全部高 CPU 场景）。
//
// 【教学注释 · 为什么文本处理是"高 CPU"场景】
//   文本处理大量涉及字符串扫描、字符判断（Unicode 属性检查）、正则匹配、
//   统计计算（TF-IDF）——这些操作几乎不碰磁盘/网络，纯 CPU 密集。
//   Go 的 goroutine 可以并发处理多篇文档：一个文档一个 goroutine，
//   GOMAXPROCS 个 CPU 核心各跑一个 goroutine——这是 Go 对高 CPU 场景的天然优势。
//
// 【教学注释 · Go vs Java 的字符串处理】
//   Java:  String 是不可变对象，频繁拼接产生大量中间对象（用 StringBuilder 规避）
//   Go  :  string 也是不可变的，但 []byte ↔ string 互转成本很低（共享底层数组，
//         前提是不修改 []byte）。而且 Go 的 strings.Builder 就是 Java StringBuilder
//         的等价物——但不需要 import（标准库提供）。
//
// 【教学注释 · Go 的 rune vs byte】
//   byte  = uint8 = 一个字节（适合 ASCII）
//   rune  = int32 = 一个 Unicode 码点（适合中文、emoji、任何非 ASCII）
//   中文一个字符占 3 个字节（UTF-8），但只是一个 rune——
//   用 for range 遍历 string 时，Go 自动解码 UTF-8，每次给一个 rune。
//   这就是"range string = rune 遍历"的甜点：一行代码正确处理中文。

package text

import (
	"math"
	"sort"
	"strings"
	"unicode"
	"unicode/utf8"
)

// ============================================================================
// 常量与默认值
// ============================================================================

const (
	// DefaultChunkSize 默认分块目标大小（字符数）。对齐 Java 端的
	// engine.documents.chunk.target-size 配置，与 MiniLM 512 token
	// 上限匹配（中文约 1 token ≈ 1.5 字符）。
	DefaultChunkSize = 400

	// DefaultOverlapSentences 默认重叠句子数。对齐 Java 端
	// engine.documents.chunk.overlap-sentences 配置。
	// 重叠 1 句是"语义保链"的最小代价——跨块的语义关联不断链。
	DefaultOverlapSentences = 1
)

// ============================================================================
// Chunk —— 文本块的领域对象
// ============================================================================

// Chunk 一个文本块的领域模型，与 Java 端的 TextChunk 字段对齐。
//
// 【教学注释 · Go 结构体的 json tag】
//   `json:"document_id"` 是 struct tag——Go 的元数据注解机制。
//   encoding/json 通过反射读取 tag 来确定字段映射（序列化/反序列化）。
//   这跟 Java 的 @JsonProperty("document_id") 等价，但不必引入 Jackson 依赖。
type Chunk struct {
	DocumentID string `json:"document_id"` // 所属文档 ID
	Index      int    `json:"index"`       // 块在文档内的序号（从 0 开始）
	Text       string `json:"text"`        // 块文本内容
}

// ============================================================================
// Chunker —— 文本分块器（对标 Java 端的 TextChunker）
// ============================================================================

// Chunker 文本分块器——句子对齐滑动窗口算法。
//
// 【算法简述】（与 Java 端 TextChunker 完全一致）：
//   1. 按句末标点（。！？!?；;\n）切分成句子列表
//   2. 顺序攒句子，累计长度 ≥ targetSize 时收口成块
//   3. 下一块从「上一块最后一个句子」开始（重叠保链）
//   4. 单句超长时按 targetSize 硬切（兜底：无标点的长文不绕过限制）
//
// 【教学注释 · Go 的"方法接收者"是 Java 的 this】
//   func (c *Chunker) Chunk(...) 中的 (c *Chunker) 叫接收者（receiver）——
//   等价于 Java 的 this，但 Go 能显式区分"值接收者"和"指针接收者"。
//   指针接收者（*Chunker）：方法内修改字段影响外部（类比 Java 的非 final 实例方法）
//   值接收者（Chunker）  ：方法内操作的是副本，不修改外部（类比不可变对象）
type Chunker struct {
	targetSize       int // 目标块长（字符数）
	overlapSentences int // 相邻块重叠的句子数
}

// NewChunker 创建文本分块器。
//
// 【教学注释 · Go 的"配置模式"选项】
//   这里用最简单的构造参数。Go 社区还有一种 Functional Options 模式：
//   func WithTargetSize(n int) Option { ... }
//   NewChunker(WithTargetSize(400), WithOverlap(1))
//   适合参数多、有默认值的场景。目前只有两个参数，直接传更清晰。
func NewChunker(targetSize int, overlapSentences int) *Chunker {
	// 【教学注释 · Go 的三元表达式替代】
	//   Go 没有 ?: 三元运算符——用 if 做防御性赋值是最惯用的方式。
	//   math.Max 只能用于 float64（Go 1.21+ 才支持泛型 max/min 内置函数）。
	if targetSize < 50 {
		targetSize = DefaultChunkSize
	}
	if overlapSentences < 0 {
		overlapSentences = DefaultOverlapSentences
	}
	return &Chunker{
		targetSize:       targetSize,
		overlapSentences: overlapSentences,
	}
}

// Chunk 执行文本分块（主方法）。
//
// 【教学注释 · Go 的 nil 切片 vs 空切片】
//   var result []Chunk（nil 切片）：len=0, cap=0，JSON 序列化为 null
//   result := make([]Chunk, 0)（空切片）：len=0, cap>0，JSON 序列化为 []
//   这里直接用 nil 切片：返回 nil 在 Go 里惯用表达"没有结果"，
//   且调用方判断 len(result)==0 对 nil 和空切片等价。
func (c *Chunker) Chunk(documentID string, text string) []Chunk {
	var result []Chunk // nil 切片：空文本返回 nil

	// 【教学注释 · strings.TrimSpace 是 strings.Trim(s, " \t\n\r...") 的快捷方式】
	trimmed := strings.TrimSpace(text)
	if trimmed == "" {
		return result
	}

	// 第一步：切句
	sentences := c.splitSentences(trimmed)
	if len(sentences) == 0 {
		return result
	}

	// 第二步：句子对齐滑动窗口
	start := 0 // 当前块的起始句子下标

	for start < len(sentences) {
		var builder strings.Builder // 等价于 Java 的 StringBuilder（零分配优化）
		end := start
		runeLen := 0 // 累计字符数（对拍 Java 的 String.length() 口径）

		// 顺序攒句子直到累计长度 ≥ targetSize
		// 【教学注释 · 判长口径决定对拍成败】
		//   Java String.length() 数的是 UTF-16 单元（BMP 内 = 字符数）；
		//   Go len(string) 数的是 byte（中文一字 3 byte）——直接用会让中文块
		//   比 Java 小三分之二。utf8.RuneCountInString 才是 Java 语义的对齐物。
		for end < len(sentences) {
			sentence := sentences[end]
			sentenceLen := utf8.RuneCountInString(sentence)
			// 如果当前块已有内容且加这句就超长 → 收口
			if runeLen > 0 && runeLen+sentenceLen > c.targetSize {
				break
			}
			builder.WriteString(sentence)
			runeLen += sentenceLen
			end++
		}

		// 写入当前块（兜底硬切超长单句）——块序号由 appendChunk 按 len(result) 自增
		c.appendChunk(&result, documentID, builder.String())

		if end >= len(sentences) {
			break // 已到末尾
		}

		// 下一块起点：回退 overlapSentences 句（重叠保链）
		nextStart := end - c.overlapSentences
		if nextStart <= start {
			nextStart = start + 1 // 至少前进一句（防止死循环）
		}
		start = nextStart
	}

	return result
}

// ============================================================================
// 内部方法
// ============================================================================

// splitSentences 按句末标点切分句子。
//
// 【教学注释 · for range 遍历 string = rune 遍历】
//   for _, ch := range text 中的 ch 是 rune（int32），不是 byte。
//   中文字符、emoji 等占多字节的 Unicode 字符会被正确解码为一个 rune——
//   不需要像 Java 那样用 codePointAt() 手动处理代理对。
//   这是 Go 语言设计中最让中文开发者舒适的特性之一。
func (c *Chunker) splitSentences(text string) []string {
	var sentences []string
	var current strings.Builder

	for _, ch := range text {
		current.WriteRune(ch) // 等价于 Java 的 StringBuilder.append(char)

		// 【教学注释 · Go 的 switch 不需要每个 case 写 break】
		//   这就是 Go 的"自动 break"设计——fallthrough 要显式写关键字。
		switch ch {
		case '。', '！', '？', '!', '?', ';', '；', '\n':
			// 句末标点：收口当前句子
			sentence := strings.TrimSpace(current.String())
			if sentence != "" {
				sentences = append(sentences, sentence)
			}
			current.Reset() // 等价于 Java 的 StringBuilder.setLength(0)
		}
	}

	// 尾巴处理：最后一句可能没有标点结尾
	tail := strings.TrimSpace(current.String())
	if tail != "" {
		sentences = append(sentences, tail)
	}

	return sentences
}

// appendChunk 硬切超长单句——防止无标点长文绕过长度限制。
//
// 【教学注释 · rune 感知硬切（spec 中文正确性硬约束）】
//   text[from:to] 是按 byte 索引的切片——中文一个字符占 3 字节，
//   byte 边界可能正好切在 UTF-8 序列中间，切出乱码（U+FFFD）。
//   正确做法：先 []rune(text) 转码点切片，按 rune 数切块再转回 string。
//   转换有一次 O(n) 分配，但硬切只发生在"无标点超长句"的兜底分支，
//   正常句子对齐路径根本走不到这里——正确性优先于罕见路径的性能近似
//   （原 byte 切版自认"合理近似"，D10 合并裁决按 spec 硬约束修正）。
//
// 【教学注释 · Go 的切片追加 append】
//   append 在容量够用时原地修改，容量不够时分配新底层数组——
//   所以必须接收返回值（*result = append(*result, ...)）。
//   忘记接收 append 的返回值是 Go 新手的头号 bug。
func (c *Chunker) appendChunk(result *[]Chunk, docID string, text string) {
	runes := []rune(text)
	from := 0
	for from < len(runes) {
		to := from + c.targetSize
		if to > len(runes) {
			to = len(runes)
		}
		piece := strings.TrimSpace(string(runes[from:to]))
		if piece != "" {
			*result = append(*result, Chunk{
				DocumentID: docID,
				Index:      len(*result), // 使用实际索引（跨超长句的多个子块）
				Text:       piece,
			})
		}
		from = to
	}
}

// ============================================================================
// Tokenize —— 中文分词（纯 Go 实现，基于标点+字符级别切分）
// ============================================================================

// Tokenize 执行中文分词。
//
// 【教学注释 · 纯 Go 分词的策略】
//   不依赖 jieba 分词库（零外部依赖），但需要正确处理中文：
//   1. 中文按"字"切分（每个汉字独立），因为中文没有空格分词
//   2. 英文按"词"切分（空格/标点分割），因为英文有天然词边界
//   3. search 模式生成 bigram（二字组），模拟 jieba 的搜索引擎模式
//   这比简单按 Unicode 类别切分更实用——至少中文字与字之间能分开。
func (c *Chunker) Tokenize(text string, mode string) []string {
	// 第一步：按字/词粒度切分
	raw := tokenizeSimple(text)

	switch mode {
	case "search":
		// 搜索引擎模式：生成 bigram（二字组）来模拟词粒度
		//   "红塔在公园" → ["红塔","塔在","在公","公园"]
		//   英文词保持原样（已经按空格分好的词粒度）
		return bigrams(raw)
	case "exact":
		// 精确模式：去重后返回（每个字/词独立）
		return uniqueStrings(raw)
	default:
		// "all" 或空：全部返回（含单字单 token）
		return raw
	}
}

// bigram 生成二元组：["红","塔","在","公","园"] → ["红塔","塔在","在公","公园"]
//
// 【教学注释 · Go 切片的"滑动窗口"惯用手法】
//   for i := 0; i < len(slice)-1; i++ { pair := slice[i] + slice[i+1] }
//   这是最简单的"两两相邻"模式——不需要额外库，一行 for 循环就够。
//
// 【教学注释 · 这里绝不能去重（曾埋过的坑）】
//   初版在此处 uniqueStrings 去重——Keywords 拿到去重后的列表统计词频，
//   所有词频恒为 1，TF 排序彻底失效（"频次统计"名存实亡）。
//   search 模式按 spec 语义也不去重（去重是 exact 模式的对比特征）；
//   重复的 bigram 正是 TF 的信息来源。
func bigrams(tokens []string) []string {
	if len(tokens) < 2 {
		return tokens
	}
	var result []string
	for i := 0; i < len(tokens)-1; i++ {
		result = append(result, tokens[i]+tokens[i+1])
	}
	return result
}

// tokenizeSimple 按字/词粒度切分：中文字符逐字切，英文按词切。
//
// 【核心规则】
//   - 中文字符（CJK 统一汉字 U+4E00~U+9FFF）：每个字独立为一个 token
//   - 英文字母/数字：连续字符攒成一个 token（英文词的天然粒度）
//   - 空格/标点：作为分隔符丢弃
//
// 【教学注释 · unicode.In 的范围检查】
//   unicode.In(ch, unicode.Han) 测试字符是否属于"汉字"Unicode 类别——
//   比手动判断 0x4E00~0x9FFF 更完整（含扩展 A~G 区、兼容汉字等）。
func tokenizeSimple(text string) []string {
	var tokens []string
	var current strings.Builder // 连续英文字母/数字的缓冲区

	for _, ch := range text {
		if unicode.Is(unicode.Han, ch) {
			// 中文字符：先收口之前的英文缓冲区，再把汉字单独加进去
			if current.Len() > 0 {
				tokens = append(tokens, current.String())
				current.Reset()
			}
			tokens = append(tokens, string(ch))
		} else if unicode.IsLetter(ch) || unicode.IsDigit(ch) {
			// 英文/数字：攒进缓冲区（英文词粒度）
			current.WriteRune(ch)
		} else {
			// 空格/标点：收口缓冲区，标点本身丢弃
			if current.Len() > 0 {
				tokens = append(tokens, current.String())
				current.Reset()
			}
		}
	}
	// 尾巴处理
	if current.Len() > 0 {
		tokens = append(tokens, current.String())
	}

	return tokens
}

// ============================================================================
// Keywords —— TF-IDF 关键词提取（纯 Go 实现）
// ============================================================================

// Keyword 关键词条目。
type Keyword struct {
	Term   string  // 词条
	Weight float64 // TF-IDF 权重（归一化到 [0,1]）
}

// Keywords 从文本中提取 Top-K 关键词（TF-IDF 风格）。
//
// 【教学注释 · TF-IDF 的 Go 实现】
//   Term Frequency (TF)  = 词 t 在本文档中的出现次数 / 本文档总词数
//   Inverse Document Frequency (IDF) = log(文档总数 / 出现词 t 的文档数)
//   单文档场景下 IDF 无法计算（文档数=1），退化为纯 TF 排序——
//   这就是"TF-IDF 风格"而非"标准 TF-IDF"的原因。
//   将来多文档语料库下可升级为完整 TF-IDF。
func (c *Chunker) Keywords(text string, topK int) []Keyword {
	// 第一步：逐字/逐词切分
	tokens := tokenizeSimple(text)
	if len(tokens) == 0 {
		return nil
	}

	// 第二步：对中文部分生成 bigram 作为候选词（英文词保持原粒度）
	//   bigram("红","塔","在","公","园") → ["红塔","塔在","在公","公园"]
	//   这样"红塔"作为一个词参与频率统计
	candidates := bigrams(tokens)
	if len(candidates) == 0 {
		candidates = tokens // 如果 bigram 空（单 token），回退到原始 token
	}

	// 第三步：统计词频（TF）
	// 【教学注释 · Go 的 map 遍历顺序是随机的】
	//   Go 的 map 遍历顺序是故意随机化的（防止依赖顺序的 bug）。
	//   需要有序输出时必须自己排序。
	freq := make(map[string]int)
	for _, token := range candidates {
		freq[token]++
	}

	totalCandidates := len(candidates)
	if totalCandidates == 0 {
		return nil
	}

	// 第四步：计算 TF 并排序
	type scoredKW struct {
		term  string
		score float64
	}
	var scored []scoredKW
	for term, count := range freq {
		tf := float64(count) / float64(totalCandidates)
		scored = append(scored, scoredKW{term: term, score: tf})
	}

	sort.Slice(scored, func(i, j int) bool {
		return scored[i].score > scored[j].score // 降序：TF 高的在前
	})

	// 第五步：截取 Top-K 并归一化到 [0,1]
	if topK > len(scored) {
		topK = len(scored)
	}

	maxTF := scored[0].score
	results := make([]Keyword, topK)
	for i := 0; i < topK; i++ {
		// 归一化：最大 TF → 1.0，其他按比例缩放
		normalized := scored[i].score / maxTF
		results[i] = Keyword{
			Term:   scored[i].term,
			Weight: math.Round(normalized*10000) / 10000,
		}
	}

	return results
}

// ============================================================================
// 辅助函数
// ============================================================================

// uniqueStrings 字符串切片去重（保持原序）。
//
// 【教学注释 · Go 的 map[T]struct{} 用作 Set】
//   Go 没有 Set 集合类型——惯用方案是 map[T]struct{}（value 为空结构体）。
//   struct{}{} 不占内存（零大小类型！），比 map[T]bool 省内存。
//   这是 Go 社区的标准"Set 替代方案"。
func uniqueStrings(items []string) []string {
	seen := make(map[string]struct{})
	var result []string
	for _, item := range items {
		if _, exists := seen[item]; !exists {
			seen[item] = struct{}{} // 空结构体字面量
			result = append(result, item)
		}
	}
	return result
}