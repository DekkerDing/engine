# Go Toolbox 模块参考手册

> 对齐源码：`golang/internal/` 下所有 `.go` 文件 | 版本 0.2.0

---

## 一、text 模块（文本处理）

### 文件：`text/chunker.go`

**包导入链**：`math`, `sort`, `strings`, `unicode`, `unicode/utf8`（全部标准库）

#### 常量

| 常量 | 值 | 说明 |
|------|-----|------|
| `DefaultChunkSize` | 400 | 默认分块目标大小（字符数），对齐 Java `engine.documents.chunk.target-size` |
| `DefaultOverlapSentences` | 1 | 默认重叠句子数，对齐 Java `engine.documents.chunk.overlap-sentences` |

#### 结构体

| 类型 | 字段 | 说明 |
|------|------|------|
| `Chunk` | `DocumentID`, `Index`, `Text` | 文本块领域模型，json tag 对齐 Java `TextChunk` |
| `Chunker` | `targetSize`, `overlapSentences` | 分块器实例（句子对齐滑动窗口算法） |

#### 核心方法

| 方法 | 签名 | 说明 |
|------|------|------|
| `NewChunker` | `(targetSize, overlapSentences int) *Chunker` | 构造分块器，负值自动回退默认值 |
| `Chunk` | `(documentID, text string) []Chunk` | 主方法：句子对齐 + 滑动窗口分块 |
| `Tokenize` | `(text, mode string) []string` | 中文分词：search=bigram / exact=去重 / 默认全量 |
| `Keywords` | `(text string, topK int) []Keyword` | TF 频次 Top-K 关键词，权重归一 [0,1] |

#### 算法要点

```
Chunk 算法：
  1. splitSentences() → 按句末标点（。！？!?;；\n）切句
  2. 顺序攒句子，累计 rune 数 ≥ targetSize 时收口成块
  3. 下一块从「上一块最后一个句子」开始 → 重叠保链
  4. 单句超长 → appendChunk() 按 targetSize 硬切（rune 感知，不乱码）

Tokenize 算法：
  1. tokenizeSimple() → 中文字符逐字切，英文按词攒
  2. search 模式 → bigram("红","塔","在","公","园") → ["红塔","塔在","在公","公园"]
  3. exact 模式 → uniqueStrings → ["红","塔","在","公","园"]

Keywords 算法：
  1. bigram 生成候选词
  2. 统计频率（TF）
  3. sort.Slice 降序排序
  4. Top-K 截断 + 归一化到 [0,1]
```

#### 测试覆盖（chunker_test.go）

| 测试用例 | 验证点 |
|---------|--------|
| `TestChunkChineseNoPunctuationHardSplit` | 1000 字无标点硬切不乱码（U+FFFD），块长 400+400+200，拼回原文一致 |
| `TestChunkSentenceAlignedAndOverlap` | 10 句 targetSize=50，第2块从第1句起（重叠保链），索引连续编号 |
| `TestChunkEmptyAndShort` | 空白/空文本/单短句边界 |
| `TestTokenizeSearchMode` | "红塔在公园" → ["红塔","塔在","在公","公园"] |
| `TestTokenizeExactMode` | 去重单字 ["红","塔","在","公","园"] |
| `TestTokenizeMixedChineseEnglish` | "Go语言真棒" → ["Go","语","言","真","棒"] |
| `TestKeywordsTopKAndNormalization` | 最高频词权重=1.0，低频词<1，权重域 [0,1] |

---

## 二、vector 模块（向量计算）

### 文件：`vector/similarity.go` — 向量数学原语

**包导入链**：`math`（标准库）

| 函数 | 签名 | 复杂度 | 说明 |
|------|------|--------|------|
| `DotProduct` | `(a, b []float64) float64` | O(n) | 点积（内积），维度不一致 panic |
| `L2Norm` | `(v []float64) float64` | O(n) | L2 欧几里得范数（向量长度） |
| `CosineSimilarity` | `(a, b []float64) float64` | O(n) | 余弦相似度 ∈ [-1,1]，零向量返回 0 |
| `Normalize` | `(v []float64) []float64` | O(n) | L2 归一化（分配新切片），零向量返回全 0 |
| `NormalizeInPlace` | `(v []float64)` | O(n) | 原地归一化（零分配），零向量保持原样 |

---

### 文件：`vector/index.go` — 内存索引（串行基轨）

**包导入链**：`math`, `sync`（标准库）

#### 结构体

| 类型 | 字段 | 说明 |
|------|------|------|
| `Entry` | `DocID`, `ChunkIdx`, `Vector`, `SourceType`, `ModelKey` | 向量条目（JSON tag 对齐 Java `VectorEntry`） |
| `Hit` | `Entry`, `Score` | 检索命中（条目 + 余弦分数） |
| `Index` | `mu`, `byDocument`, `size` | 内存索引：sync.Mutex 保护 + map[文档ID]条目列表 |

**内部类型**：
- `indexedEntry`：`Entry` 结构体嵌入 + `scoringVec` 预归一化打分向量。包私有（首字母小写）。

#### 核心方法

| 方法 | 签名 | 说明 |
|------|------|------|
| `NewIndex` | `() *Index` | 创建空索引 |
| `Replace` | `(documentID string, entries []Entry)` | 幂等替换一个文档的全部条目（"先清后写"语义），预归一化写入 |
| `Remove` | `(documentID string) int` | 删除文档全部条目，返回删除数（0 = 不存在 = 幂等） |
| `Size` | `() int` | 条目总数 |
| `IsEmpty` | `() bool` | 索引是否为空 |
| `Search` | `(query []float64, topK int, sourceType, modelKey string) []Hit` | 串行暴力检索：归一查询 → 点积打分 → sortHits → Top-K |
| `GetAllEntries` | `() []Entry` | 深拷贝快照（Vector 也 copy，外部修改不污染内部） |

#### 空间闸门

`Search` 和 `SearchParallel` 的参数 `sourceType` / `modelKey` 实现双重过滤：
- 空串 = 不过滤（全空间扫描）
- 非空 = 精确匹配（`item.SourceType != sourceType` → 跳过）

这等价于 Java `InMemoryVectorIndex` 的"双闸门逻辑"——不同模型空间的向量不可比。

#### 测试覆盖（index_test.go）

| 测试用例 | 验证点 |
|---------|--------|
| `TestReplaceIdempotent` | 同文档二次写入不双计；替换后旧条目消失；Size 准确 |
| `TestRemoveDeleteMiss` | 删除后检索不再命中；邻居不受影响；删不存在文档幂等 |
| `TestCosineDiagonal` | 任意向量与自身余弦 = 1（含 4 维 / 6 维 / 1 维退化情形） |
| `TestSearchSpaceGate` | source_type/model_key 双闸门互不可见；空过滤全量命中 |
| `TestSearchDimensionMismatch` | 维度不匹配的条目静默跳过（不 panic） |
| `TestSearchZeroVector` | 零向量余弦 = 0（非 NaN）；零向量条目 score=0 命中 |
| `TestSearchTopKTruncation` | Top-K 降序排序；chunk 序正确 |
| `TestGetAllEntriesSnapshot` | 外部修改快照不影响索引内部（防御性拷贝） |

---

### 文件：`vector/search.go` — 并行检索（生产轨）

**包导入链**：`math`, `runtime`, `sort`, `sync`（标准库）

#### 核心函数

| 函数 | 说明 |
|------|------|
| `quantize(score float64) float64` | 分数量化到 1e-6 格点——浮点噪声吸附，保证排序键的传递性 |
| `hitLess(a, b Hit) bool` | 全序比较：量化分数降序 → doc_id 字典序 → chunk_index 升序 |
| `sortHits(hits []Hit)` | sort.Slice 包装，串行/并行共用同一全序合同 |

#### 核心方法

| 方法 | 说明 |
|------|------|
| `SearchParallel(query, topK, sourceType, modelKey)` | 公开接口：自动 workers=NumCPU |
| `searchParallel(query, topK, sourceType, modelKey, workers)` | 内部实现：workers 参数可测试（0=自动 / 1=退化串行 / N=指定分片） |

#### 三阶段算法

```
阶段一：快照（持锁）
  遍历 byDocument → 空间过滤 + 维度校验 → 收集 candidates 切片
  （只采集不计算——锁只盖数据采集，不盖计算）

阶段二：分片打分（无锁）
  candidates 按 workers 均分
  每个 goroutine：DotProduct 点积 → sortHits → 局部 Top-K
  各 goroutine 写各自的输出槽——无竞争

阶段三：归并（无锁）
  拼接局部 Top-K → sortHits → 全局 Top-K 截断

正确性论证：
  全局第 k 名在任何分片内的名次 ≤ k（其他分片的候选只会把它顶后，不会提前）
  ∴ 每个分片保留前 k 个，全局 Top-K 全数存活到归并阶段
```

#### 容差定序

```
问题：浮点噪声使接近的分数比较不满足传递性
  a≈b (差 5e-7) 且 b≈c (差 5e-7) 但 a≠c (差 1e-6+)
  用 epsilon 比较喂 sort → 未定义行为，同一数据两次排序可能不同

方案：量化到 1e-6 格点（整数全序天然传递）
  quantize(s) = round(s * 1e6)
  格点值 ∈ [-1e6, 1e6] → 不超过 float64 的 2^53 精确表示范围
```

#### 测试覆盖（search_test.go）

| 测试用例 | 验证点 |
|---------|--------|
| `TestSearchParallelMatchesSerial` | 120文档×4块×32维随机数据，topK∈{1,7,50,480}，并串行逐条对拍一致 |
| `TestSearchParallelWorkersVariants` | workers∈{1,2,8,17,0} 各分片数结果相同（含非整除尾片） |
| `TestSearchParallelTieBreakOrder` | 同分乱序插入 → doc_id 字典序 + chunk_index 升序（9条定序合同） |
| `TestSearchParallelToleranceTie` | 分数差 <1e-6 → 容差平分按字典序（doc-a 先于 doc-b 尽管分数更低） |
| `TestSearchParallelScoreGapAboveTolerance` | 分数差 >1e-6 → 严格分数序（doc-b=1.0 先行） |
| `TestSearchParallelEdges` | nil查询/topK=0/空索引/不命中空间 → 空结果非panic |

---

## 三、hashing 模块（哈希降级向量）

### 文件：`hashing/hasher.go`

**包导入链**：`crypto/sha256`, `encoding/binary`, `fmt`, `math`（全部标准库）

#### 函数

| 函数 | 签名 | 说明 |
|------|------|------|
| `Generate` | `(text string, targetDim int) []float64` | SHA-256 轮转产生确定性伪向量，值域 [-1, 1)，负维度回退 512 |
| `GenerateNormalized` | `(text string, targetDim int) []float64` | 同上 + L2 归一化（点积=余弦语义成立） |

#### 确定性机理

```
算法：for round = 0; round*8 < targetDim; round++:
        SHA-256(text + ":" + round) → 32 byte
        每 4 byte 用 BigEndian.Uint32 解析为 u32
        映射: float64(u32) / MaxUint32 * 2 - 1 → [-1, 1)

确定性保证：
  - SHA-256：密码学标准哈希，跨平台完全确定
  - BigEndian：无论 x86 小端还是 ARM 可变序，结果相同
  - 同文本同维度永远输出同向量（跨 Java/Python/Go）
```

#### 测试覆盖（hasher_test.go）

| 测试用例 | 验证点 |
|---------|--------|
| `TestGenerateDeterministic` | 同文本两次调用逐元素相等 |
| `TestGenerateDims` | dim=0→512 / dim=-3→512 / dim=33→33（非 8 倍数也覆盖） |
| `TestGenerateValueRange` | 512 维全部 ∈ [-1, 1) |
| `TestGenerateTextSensitivity` | "红塔" vs "白塔" → 过半元素不同 |
| `TestGenerateNormalizedL2` | L2 模长 = 1（容差 1e-9） |
| `TestGenerateNormalizedPreservesDirection` | normalize=true/false 两版逐元素成比例 |

---

## 四、engine 模块（门面层）

### 文件：`engine/engine.go`

**结构**：`Engine` 持有 `*text.Chunker` + `*vector.Index`。

**Version**：`var Version = "0.2.0"`，构建期可注入（`-ldflags "-X ...engine.Version=1.0.0"`）。

**RegisterAll** 注册 8 个方法（不含 `sys.shutdown`，该帧由 Server 主循环特判）：

| 方法 | 处理器 | params 结构体 |
|------|--------|--------------|
| `sys.ping` | 匿名闭包 | `json.RawMessage`（忽略） |
| `sys.stats` | 匿名闭包 | `json.RawMessage`（忽略） |
| `text.chunk` | `e.textChunk` | `textChunkParams` |
| `text.tokenize` | `e.textTokenize` | `textTokenizeParams` |
| `text.keywords` | `e.textKeywords` | `textKeywordsParams` |
| `vector.insert` | `e.vectorInsert` | `vectorInsertParams` |
| `vector.delete` | `e.vectorDelete` | `vectorDeleteParams` |
| `vector.similarity` | `e.vectorSimilarity` | `vectorSimilarityParams` |
| `vector.search` | `e.vectorSearch` | `vectorSearchParams` |
| `hashing.generate` | `e.hashingGenerate` | `hashingGenerateParams` |

### 文件：`engine/server.go`

**常量**：`maxFrameBytes = 64 * 1024 * 1024`（64MB 单帧上限）

**结构**：`Server` 持有 `*router.Router` + `sync.Mutex` 写锁。

**Run() 主循环流程**：
1. 打横幅（version/platform/gomaxprocs → stderr）
2. `bufio.NewScanner(os.Stdin)` + `Buffer(64KB, 64MB)`
3. `bufio.NewWriter(os.Stdout)` + `defer out.Flush()`
4. for Scanner.Scan：TrimSpace → json.Unmarshal → dispatch → write
5. EOF 或 sys.shutdown → return

---

## 五、protocol 模块

### 文件：`protocol/protocol.go`

| 类型 | 字段 | 说明 |
|------|------|------|
| `Request` | `ID`, `Method`, `Params`(json.RawMessage) | Java→Go 请求帧 |
| `Response` | `ID`, `Result`(interface{}), `Error`(*ErrorObject) | Go→Java 响应帧 |
| `ErrorObject` | `Code`, `Message` | 错误体 |

| 构造器 | 签名 |
|--------|------|
| `NewResult` | `(id int64, result interface{}) Response` |
| `NewError` | `(id int64, code int, message string) Response` |

---

## 六、router 模块

### 文件：`router/router.go`

**HandlerFunc 类型**：`func(params json.RawMessage) (interface{}, *protocol.ErrorObject)`

| 方法 | 说明 |
|------|------|
| `Register(method, handler)` | 登记方法（同名重复 panic，fail-fast） |
| `Has(method) bool` | 探测方法是否已注册 |
| `Methods() []string` | 已注册方法名列表（字典序稳定） |
| `Dispatch(req) Response` | 查表 → 调用 → 组装响应帧；panic 被 recover 成 1003 错误帧 |