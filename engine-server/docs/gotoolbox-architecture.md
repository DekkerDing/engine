# Go Toolbox 引擎架构手册

> 版本：0.2.0 | 最后更新：2026-09-13 | 场景分工：Java 门面 + Go 嵌入引擎

---

## 一、架构全景

```
┌────────────────────────────────────────────────────────┐
│  Java (Spring Boot :8090) — 门面层                     │
│  ├─ HTTP REST 端点 (@RequestMapping)                   │
│  ├─ DDD 业务编排 (application/domain)                   │
│  ├─ SQLite 持久化（真相库）                              │
│  ├─ Caffeine 缓存                                       │
│  └─ 与前端 SPA 交互                                     │
├────────────────────────────────────────────────────────┤
│  Go toolbox 引擎（子进程） — 引擎层                       │
│  ├─ text.*   高 CPU ─ 文本分块/分词/关键词               │
│  ├─ vector.* 高 CPU ─ 向量索引/并行检索/相似度            │
│  ├─ hashing.* 高 CPU ─ SHA-256 哈希降级向量              │
│  └─ io.*     高 IO  ─ 文件批处理（待扩展）                │
├────────────────────────────────────────────────────────┤
│  通信协议：stdin/stdout JSON 行（一行一帧）               │
│  日志专线：stderr（不混 stdout 协议流）                   │
└────────────────────────────────────────────────────────┘
```

### 为什么选"Java 门面 + Go 引擎"？

| 维度 | Java（门面层） | Go（引擎层） |
|------|---------------|-------------|
| 核心职责 | HTTP / 业务编排 / 持久化 / 缓存 | 高 CPU 计算 / 高 IO 操作 |
| 启动速度 | 3~10s（Spring 容器） | <0.1s（编译二进制） |
| 并发模型 | 线程池 + 同步 IO | goroutine + 零分配热路径 |
| 部署形态 | fat jar | 单一可执行文件（toolbox.exe） |
| 第三方依赖 | Spring / Jackson / SQLite | **零依赖**（纯 Go 标准库） |

---

## 二、项目结构

```
engine-server/
├── golang/                             ← Go 引擎源码
│   ├── cmd/toolbox/main.go             ← 入口：三步组装 → Router → Engine → Server
│   └── internal/
│       ├── protocol/protocol.go        ← JSON 行协议帧定义
│       ├── router/router.go            ← 方法注册表 + panic 兜底
│       ├── engine/
│       │   ├── server.go               ← 协议主循环：stdin ␤ dispatch ␤ stdout
│       │   └── engine.go               ← 引擎门面：持有所有工具，8 个方法处理器
│       ├── text/
│       │   ├── chunker.go              ← 分块 / 分词 / 关键词
│       │   └── chunker_test.go         ← 分块 & 分词 & 关键词 测试
│       ├── vector/
│       │   ├── similarity.go           ← 点积、L2 范数、余弦、归一化
│       │   ├── index.go                ← 内存索引（写入/删除/串行检索）
│       │   ├── search.go               ← 并行分片检索 + 容差定序
│       │   ├── index_test.go           ← 索引 测试
│       │   └── search_test.go          ← 并行检索 测试
│       └── hashing/
│           ├── hasher.go               ← SHA-256 确定性哈希向量
│           └── hasher_test.go          ← 哈希向量 测试
│
├── src/main/java/.../infrastructure/go/   ← Java 适配器
│   ├── protocol/GoProtocol.java        ← JSON 帧 DTO（对齐 Go protocol.go）
│   ├── GoChannel.java                  ← 通道接口
│   ├── GoProcessLauncher.java          ← 进程管家
│   ├── GoStdioChannel.java             ← stdin/stdout 通道实现
│   ├── GoToolboxProvider.java          ← EmbeddingProvider 降级实现
│   └── GoVectorReplica.java            ← 索引副本客户端（场景二）
│
└── docs/                               ← 本手册
    ├── gotoolbox-architecture.md        ← 架构总览（你正在读）
    ├── gotoolbox-protocol.md            ← 协议规范
    └── gotoolbox-modules.md             ← 模块参考
```

---

## 三、场景分工（三个独立场景，可分别启用）

### 场景一：文本处理（高 CPU）

| Go 方法 | 用途 | Java 调用入口 | 启用条件 |
|---------|------|--------------|----------|
| `text.chunk` | 句子对齐滑动窗口分块 | `GoToolboxProvider.chunkText()` | Profile `go-toolbox` |
| `text.tokenize` | 中文分词（bigram / exact） | `GoToolboxProvider.tokenize()` | Profile `go-toolbox` |
| `text.keywords` | TF 关键词提取 Top-K | `GoToolboxProvider.keywords()` | Profile `go-toolbox` |

### 场景二：向量检索加速（高 CPU · 并行分片）

| Go 方法 | 用途 | Java 调用入口 | 启用条件 |
|---------|------|--------------|----------|
| `vector.insert` | 文档条目幂等替换 | `GoVectorReplica.replace()` | `engine.go.enabled=true` |
| `vector.delete` | 文档条目删除 | `GoVectorReplica.remove()` | `engine.go.enabled=true` |
| `vector.search` | 并行 Top-K 余弦检索 | `GoVectorReplica.search()` | `engine.go.enabled=true` |
| `vector.similarity` | 两向量余弦 | `GoVectorReplica.similarity()` | `engine.go.enabled=true` |

> **注意**：场景二使用 `@ConditionalOnProperty(name = "engine.go.enabled")` 装配，与场景一的 `@Profile("go-toolbox")` 是独立开关。场景二是"副本同步"：摄取时把条目复制进 Go，检索时发查询收 `(doc_id, chunk_index, score)` 三元组——Go 只做计算，不做回表。

### 场景三：降级向量化（高 CPU · 断网兜底）

| Go 方法 | 用途 | Java 调用入口 | 启用条件 |
|---------|------|--------------|----------|
| `hashing.generate` | SHA-256 确定性伪向量 | `GoToolboxProvider.embedBatch()` | Profile `go-toolbox` |

---

## 四、启动流程

### Go 端（cmd/toolbox/main.go）

```
1. router.New()        → 创建方法注册表
2. engine.New()        → 创建引擎门面（持有 Chunker + Index）
3. eng.RegisterAll(r)  → 注册所有方法处理器
4. server.Run()        → 阻塞主循环：stdin 逐帧读 → router.Dispatch → stdout 写帧
```

### Java 端

```
1. GoProcessLauncher.resolve() → 定位二进制（toolbox.exe）或 go run 源码
2. GoProcessLauncher.launch()  → 拉起子进程，stderr 桥接日志
3. GoStdioChannel.start()      → 握手 ping，确认就绪
4. GoStdioChannel.send()       → call() 方法：写 stdin → 等队列 → 读 stdout
```

---

## 五、教学注释体系

本项目为 Go 学习者设计，注释覆盖以下维度：

| 注释类型 | 示例 | 位置 |
|---------|------|------|
| **Go vs Java 对照** | "Go 的 defer+recover = Java 的 try-finally" | router.go |
| **语言特性解释** | "rune 遍历 = 自动 UTF-8 解码" | chunker.go |
| **设计决策说明** | "为什么互斥锁而非读写锁" | index.go |
| **坑位标注** | "bigram 去重导致 TF 失效的教训" | chunker.go |
| **性能注释** | "Lock/Unlock 显式写不 defer——热路径省纳秒" | index.go |
| **协议契约** | "stdout 不 Flush = Java readLine 死锁" | server.go |
| **测试脚手架** | "可复现的随机测试：种子就是案发现场的钥匙" | search_test.go |

---

## 六、构建与运行

### 开发态（go run）

```bash
cd engine-server/golang
echo '{"id":1,"method":"sys.ping","params":{}}' | go run ./cmd/toolbox/
```

### 生产态（编译二进制）

```bash
cd engine-server/golang
go build -o toolbox ./cmd/toolbox/

# 注入版本号
go build -ldflags "-X engine/gotoolbox/internal/engine.Version=1.0.0" -o toolbox ./cmd/toolbox/
```

### 运行测试

```bash
go test ./... -v
```

### 零依赖验证

```bash
go mod graph          # 应输出空（标准库不出现）
go build -mod=readonly ./...  # 离线编译验证
```