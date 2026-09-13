# Go Toolbox 协议规范

> 对齐文件：`golang/internal/protocol/protocol.go` ␤ `go/GoProtocol.java` | 版本 0.2.0

---

## 一、传输层

```
通信方式：stdin / stdout（标准输入输出流）
编码格式：JSON（UTF-8）
帧分隔：换行符 \n（一行一帧）
最大帧长：64 MB（向量批量灌入留足余量）
错误码分类：
  1000 段 → 协议级（方法不存在 / 参数格式错 / 内部 panic）
  2000 段 → 工具方法级（hashing 路径拒绝 / 文件 IO）
```

### 关键纪律

| 规则 | 解释 |
|------|------|
| stdout 是协议专线 | 每行一个 JSON 响应帧，写完必须立即 Flush——不 Flush 则 Java `readLine()` 永远等不到 = 死锁 |
| stderr 是日志专线 | 引擎日志一律 stderr，Java 端桥接到 SLF4J `[go-err]` |
| 空行容忍 | Go Server 主循环 `TrimSpace` 后跳过空行——Java 侧偶发的尾部换行不致崩 |
| id 逐帧配对 | 请求带 id，响应带相同 id——串行模型下 id 可预测，但显式携带符合协议语义 |

---

## 二、帧结构

### 请求帧（stdin）

```json
{"id":1,"method":"sys.ping","params":{}}
{"id":2,"method":"text.chunk","params":{"document_id":"doc-1","text":"..."}}
{"id":3,"method":"vector.search","params":{"query":[0.1,0.2],"top_k":10}}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `id` | int64 | 是 | 请求序号（Go 侧 `json:"id"`；Java 侧 `GoProtocol.Response` 按 id 配对） |
| `method` | string | 是 | 方法名（点分隔命名空间：`text.chunk` / `vector.search`） |
| `params` | object | 否 | 参数透传 JSON——各方法自行解析，协议层不关心结构 |

### 成功帧（stdout）

```json
{"id":1,"result":{"status":"pong","version":"0.2.0"}}
{"id":2,"result":{"chunks":[...],"count":3}}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | int64 | 与请求帧配对 |
| `result` | object | 方法返回值——结构由各方法定义（协议层不关心） |

### 错误帧（stdout）

```json
{"id":1,"error":{"code":1001,"message":"方法不存在: x.y"}}
{"id":2,"error":{"code":1002,"message":"vector.search 缺少必填参数 query"}}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `error.code` | int | 数字错误码 |
| `error.message` | string | 人读错误描述 |

### 特殊帧

```json
{"id":N,"method":"sys.shutdown","params":{}}
→ {"id":N,"result":{"status":"bye"}}  // 引擎优雅退出
```

---

## 三、错误码表

### 1000 段：协议级

| 常量 | 值 | 含义 | 触发条件 |
|------|-----|------|---------|
| `CodeMethodNotFound` | 1001 | 方法名不在注册表 | `router.Dispatch` 查 map 未命中 |
| `CodeInvalidParams` | 1002 | 参数格式/缺必填 | 各 handler 内部 `json.Unmarshal` 失败或空值校验 |
| `CodeInternal` | 1003 | 处理器内部错误 | handler 内 `panic` 被 `recover` 兜住 |

### 2000 段：工具方法级

| 常量 | 值 | 含义 |
|------|-----|------|
| `CodePathRejected` | 2001 | hashing 文件 IO 路径越界拒绝 |
| `CodeFileIO` | 2002 | hashing 文件 IO 读写失败 |

---

## 四、方法列表（8 个）

### sys.*（内置，3 个）

| 方法 | params | result | 说明 |
|------|--------|--------|------|
| `sys.ping` | `{}` | `{"status":"pong","version":"0.2.0"}` | 存活握手 |
| `sys.stats` | `{}` | `{"engine":"gotoolbox","version":"0.2.0","vector_count":N,"tools":[...]}` | 引擎自描述 |
| `sys.shutdown` | `{}` | `{"status":"bye"}` | 优雅退出（Server 主循环特判，不进注册表） |

### text.*（3 个）

| 方法 | 必要 params | 可选 params | result 结构 |
|------|------------|-------------|------------|
| `text.chunk` | `document_id`, `text` | `target_size`(int), `overlap_sentences`(int) | `{"chunks":[{"document_id","index","text"}],"count":N}` |
| `text.tokenize` | `text` | `mode`("search"=bigram / "exact"=去重 / 默认"all") | `{"tokens":[...],"count":N,"mode":"..."}` |
| `text.keywords` | `text` | `top_k`(int, 默认10) | `{"keywords":[{"term","weight"}]}` |

### vector.*（4 个）

| 方法 | 必要 params | 可选 params | result 结构 |
|------|------------|-------------|------------|
| `vector.insert` | `document_id`, `entries` | — | `{"inserted":N,"total":N}` |
| `vector.delete` | `document_id` | — | `{"removed":N,"total":N}` |
| `vector.search` | `query`(float64[]) | `top_k`(默认10), `source_type`, `model_key` | `{"hits":[{"document_id","chunk_index","score"}],"count":N}` |
| `vector.similarity` | `vector_a`, `vector_b` | — | `{"score":float}` |

### hashing.*（1 个）

| 方法 | 必要 params | 可选 params | result 结构 |
|------|------------|-------------|------------|
| `hashing.generate` | `text` | `dim`(默认512), `normalize`(bool, 默认false) | `{"vector":[...],"dim":N,"degraded":true,"text":"..."}` |

---

## 五、Java 侧帧结构（GoProtocol.java）

Java 侧 DTO 与 Go 侧 protocol.go 逐字段对齐，使用嵌套静态类：

```java
// 发送请求
GoProtocol.Request req = new GoProtocol.Request();
req.id = 1;
req.method = "text.chunk";
req.params = Map.of("document_id", "doc-1", "text", "...");

// 接收响应
GoProtocol.Response resp = GoProtocol.parseResponse(jsonLine);
if (resp.isError()) {
    // 处理 resp.error.code / resp.error.message
} else {
    GoProtocol.ChunkResult r = GoProtocol.extractResult(resp, GoProtocol.ChunkResult.class);
    // 使用 r.chunks / r.count
}
```

### DTO 类对照

| Go struct / 响应 key | Java DTO | 用途 |
|---------------------|---------|------|
| `Request` | `GoProtocol.Request` | 请求帧 |
| `Response` | `GoProtocol.Response` | 响应帧 |
| `result.chunks` | `GoProtocol.ChunkResult` | text.chunk 响应 |
| `result.tokens` | `GoProtocol.TokenizeResult` | text.tokenize 响应 |
| `result.keywords` | `GoProtocol.KeywordsResult` | text.keywords 响应 |
| `result.hits` | `GoProtocol.VectorSearchResult` | vector.search 响应 |
| `result.score` | `GoProtocol.SimilarityResult` | vector.similarity 响应 |
| `result.{vector,dim,degraded,text}` | `GoProtocol.HashResult` | hashing.generate 响应 |
| `result.{engine,version,vector_count,tools}` | `GoProtocol.StatsResult` | sys.stats 响应 |
| `result.{inserted,total}` / `result.{removed,total}` | `Map.get()` 直接取 | vector.insert/delete 响应 |

---

## 六、Java 通道层

```
GoChannel (interface)
  ├─ channelName()  → "go-stdio"
  ├─ start()        → 拉起子进程 + 握手
  ├─ close()        → shutdown + 杀进程
  ├─ isAlive()      → 进程存活检测
  └─ send(method, params) → GoProtocol.Response

GoStdioChannel (实现)
  ├─ writer         → BufferedWriter → stdin
  ├─ responseQueue  → BlockingQueue → 读线程 → stdout
  └─ call()         → synchronized 串行写 → 队列 poll 取
```

| Java 组件 | 对标 Python 组件 | 差异 |
|-----------|-----------------|------|
| `GoChannel` | `PythonChannel` | Go 只有 1 个 send 方法（协议统一）；Python 每个 op 独立方法签名 |
| `GoStdioChannel` | `StdioChannel` | Go 不需要 ping 探活轮询（无模型加载）；启动握手机制更简单 |
| `GoProcessLauncher` | `PythonProcessLauncher` | Go 不需要环境变量 PYTHONIOENCODING/PYTHONUNBUFFERED；二进制探测优先级不同 |
| `GoToolboxProvider` | `ChannelEmbeddingProvider` | Go 实现全部场景（文本/向量/哈希）；Python 每个 provider 只管一个场景 |
| `GoVectorReplica` | (无对标) | Go 独有：索引副本同步 + 并行检索 |