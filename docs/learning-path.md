# 学习路径（learning-path）—— 写给会一点 Java 的你

> 本仓库本身就是教材：Java（DDD 业务）+ Python（向量化引擎）+ React（前端）三栈同栖。
> 本文按"从你已会的 Java 出发"组织学习顺序，**每个阶段都指向仓库内的具体文件**——
> 读完一节就打开对应文件对照看，不要只看文档。

---

## 0. 全景：这个仓库有什么可学的

```
你已经会的                          你将要学的
─────────────                      ─────────────
Java 8 / Spring Boot    ──────▶    Python 3（后端脚本 + 模型推理）
                                   React 18 + TypeScript（前端工程）
                                   Go 1.21+（嵌入计算引擎，第六阶段）
DDD 分层思想（本仓库 Java 侧）──▶   同样的分层纪律应用到 Python 与前端
```

建议节奏：每阶段 1~2 天，改一个文件跑一次 `bootRun` 看效果——**动手比通读有效**。

---

## 第一阶段：Python 语言基础（半天）

### 1.1 从 Java 到 Python 的心智转换

| Java | Python | 仓库样例 |
|------|--------|----------|
| `class Foo { }` | `class Foo:` | `engine-server/python/core/embeddings.py` |
| 强类型声明 `String s` | 动态类型 + 类型注解 `s: str` | `core/embeddings.py`（注解齐全） |
| `List<String>` | `list[str]` | 同上 |
| interface + impl | `Protocol` / duck typing | `core/registry.py`（槽位注册） |
| `try/catch` | `try/except` | `core/embeddings.py`（降级路径） |
| null | `None` | 全仓库 |
| Maven/Gradle | pip + requirements.txt | `python/requirements.txt` |
| JUnit | pytest（本仓库以手测/IT 为主） | — |

### 1.2 导览（按此顺序读）

1. `engine-server/python/core/registry.py` —— 最小的一个：怎么用 dict 注册"模型槽位"（对照 Java 的枚举 + 工厂；注意 clip 槽位已是三槽之一）
2. `engine-server/python/core/tokenizer.py` —— 函数式风格：jieba 分词封装
3. `engine-server/python/core/embeddings.py` —— **核心**：类 + 惰性加载 + 降级（哈希向量）+ 确定性语义；读三遍
4. `engine-server/python/core/vision.py` —— 跨模态对照读物：CLIP 双塔（图片/文本各一塔、同一向量空间）。与 `embeddings.py` 逐条对齐同款纪律——惰性加载、模型不可用哈希降级、确定性；差异只在"喂进去的是 PIL 图片而非字符串"
5. `engine-server/python/env_check.py` —— 环境自检脚本（学"防御式启动"）

练习：给 `tokenizer.py` 加一个"返回前 N 个字符"的辅助函数，然后
`python -c "from core.tokenizer import ..."` 手测。

---

## 第二阶段：Python 作为"引擎服务"（1 天）

理解 Python 不是脚本而是**被 Java 进程托管的长驻服务**：

1. `server_py4j.py` —— 主通道入口：
   - 看懂 `EngineFacade`（每个方法 = Java 可远程调用的 API，JSON 字符串进出）
   - 看懂 `main()` 的端口 bind 重试（Windows TIME_WAIT 坑，注释详尽）
2. `server_stdio.py` —— 备通道入口：对照差异——stdout 变成协议专线、JSON 行协议、日志全走 stderr
3. Java 侧对读（理解"托管"）：
   - `infrastructure/python/PythonProcessLauncher.java` —— 进程拉起/优雅停止
   - `infrastructure/python/Py4jChannel.java` —— 连接轮询/健康检查/自动重启（**含两处实战踩坑注释：异常遮蔽与半成品清理，值得精读**）
   - `infrastructure/python/FailoverChannel.java` —— 主备切换状态机（设计模式：组合 + 装饰器）

练习：手测 stdio 协议——
`echo '{"op":"未知操作"}' | python engine-server/python/server_stdio.py`，
观察错误信封（spec: 未知操作返回错误信封）。

---

## 第三阶段：前端三件套最小集（1 天，从零补 HTML/CSS/JS）

如果你没写过网页，先建立"浏览器在渲染什么"的直觉：

1. 打开 `engine-server/frontend/index.html` —— 只有一个 `<div id="root">` 和一个 script：现代前端 = JS 接管一切
2. `engine-server/frontend/src/layouts/AppLayout.css` —— **全站布局的唯一坐标系**（CSS Grid 二维栅格，注释画了示意图）；改一下 `208px` → `260px`，`npm run dev` 看侧边栏变宽
3. `engine-server/frontend/src/main.tsx` + `App.tsx` —— React 的入口与路由表（对照 Spring 的 `@RequestMapping` 集中声明）

概念对照：
- HTML 结构 → Java 的"数据结构"（静态骨架）
- CSS 样式 → "视图配置"（类名 = 键，规则 = 值；Grid 区域名 ≈ 常量提取）
- JS/TS 行为 → "控制器逻辑"（事件驱动 ≈ 回调）

---

## 第四阶段：React + TypeScript 工程化（1~2 天）

按数据流方向读（自底向上）：

```
api/（怎么拿数据） → hooks/（怎么自动拿） → components/（怎么展示原子） → pages/（怎么组装页面）
```

1. `api/client.ts` —— 统一 axios 实例：信封解析（code≠0 抛错）+ 超时 + 错误 toast。
   对照 Java：这就是前端的 `GlobalExceptionHandler` 反向操作
2. `api/types.ts` —— DTO 类型定义，与后端响应字段一一对应（camelCase 对齐）
3. `hooks/usePolling.ts` —— 轮询 hook：文档状态自动流转的秘密
4. `components/StatusBadge.tsx` / `HighlightText.tsx` / `DegradedBanner.tsx` —— 三个最小共享组件，看 props 用法（对照 Java 的构造注入）
5. `pages/documents/` —— 最完整的页面：上传进度、列表轮询、删除确认、详情
6. `pages/dashboard/` —— 健康卡片 + 调用链示意（理解前端如何展示"系统状态"）

练习：把仪表盘轮询间隔从配置改一遍，或者给检索页加一个"清空历史"按钮——小改动走通"改 → dev 热更 → 验证"循环。

---

## 第五阶段：全链路串联（半天，毕业项目）

带着一个问题读代码：**"用户搜索'红塔'到看到高亮结果，发生了什么？"**

```
1. frontend src/pages/search/     用户提交 → api/search.ts → POST /api/search
2. server interfaces.web          资源链：/api/** 优先控制器匹配，非 /api 走静态/SPA 回退
3. server SearchController        @RequestMapping("/api/search") + @Valid 校验
4. SearchApplicationService       缓存查询 → RRF 融合编排
5. ChannelEmbeddingProvider       查询向量化 →（Py4J）→ python EngineFacade.embedTexts
6. core/embeddings.py             bge-small-zh-v1.5 编码 512 维
7. InMemoryVectorIndex            内存余弦 top-k（语义路）
8. LuceneFullTextIndex            SmartCN 全文匹配 + 高亮偏移（字面路）
9. 响应沿原路返回                  {hits:[{text, score, source, highlights}], took}
10. HighlightText.tsx             前端按偏移渲染高亮
```

每步打开对应文件找到那几行。能讲清楚这条链，本仓库的核心你就掌握了。

**毕业加试（跨模态版）**：把同样的问题换成**"用户搜'红塔与小花'到看到照片卡片，发生了什么？"**——
链路主干同上，分岔点在第 4 步（`modality=image` 路由到 `doImageSearch`）：
CLIP 文本塔编码查询（`core/vision.py`）→ 仅扫 image 空间（`(sourceType, modelKey)` 双闸门）
→ 单路直出（无 RRF、无全文）→ `ImageSearchPage.tsx` 缩略图卡片。两条链的差异本身就是
"模态路由"设计的教学样本。

---

## 第六阶段：Go 语言与嵌入引擎（1.5 天，gotoolbox）

> 仓库第四栈：Go 作为**嵌入计算引擎**活在 `engine-server/golang/`——
> 承接文本处理、哈希降级向量、并行向量检索三组高 CPU 工具。动机与设计
> 详见 [gotoolbox-design](file:///F:/workspace/engine/docs/reqforge-gotoolbox-design.md)
> （其第 5 节是本阶段的详版路线图），行为契约见
> [gotoolbox-spec](file:///F:/workspace/engine/docs/reqforge-gotoolbox-spec.md)。

### 6.1 心智转换（30 分钟）

| Java | Go | 仓库样例 |
|------|-----|----------|
| class + package | 包 = 目录，首字母大写即 public | `golang/internal/router/router.go` |
| 异常 throw/catch | `err` 返回值逐层检查 | `golang/internal/protocol/` |
| try-finally | `defer` | `golang/cmd/toolbox/main.go` |
| interface + implements | 接口**隐式实现**（结构体长得像就行） | `golang/internal/router/` 的 Handler |
| Thread + CountDownLatch | goroutine + `sync.WaitGroup` | `golang/internal/vector/search.go` |
| String.charAt | `[]rune(s)`（`len(s)` 是**字节数**！） | `golang/internal/text/` |

### 6.2 走读路线（按依赖顺序，每站 30-60 分钟）

1. **协议循环**：`golang/cmd/toolbox/main.go` + `golang/internal/protocol/`——
   读一行 JSON → 查注册表 → 写一行 JSON。最小完整闭环，对照 Jackson 的
   struct tag（`json:"id"`）。
2. **注册表**：`golang/internal/router/`——`map[string]Handler` 一张表就是
   Spring 的方法表；"新增方法零协议改动"的秘密。
3. **注册全家福**：`golang/internal/engine/engine.go`——11 个方法的参数
   结构体与错误翻译（panic → 1002 帧）。
4. **中文处理**：`golang/internal/text/`——string 是 byte 序列，中文一个字
   3 字节；rune 感知硬切对照 Java `TextChunker`（`domain/service/`）。
5. **确定性哈希**：`golang/internal/hashing/`——降级向量的实现体（Java 侧
   无对应实现，Go 专属）。
6. **副本索引**：`golang/internal/vector/index.go`——map delete 内置函数、
   切片共享底层数组的深拷贝陷阱。
7. **并行扫描 ⭐**：`golang/internal/vector/search.go`——三阶段（持锁快照 →
   goroutine 分片无锁算 → WaitGroup 归并）+ 量化定序；Java 对应物
   `InMemoryVectorIndex.topK()` 与 Go `sortHits` 逐字同款。
8. **回到 Java**：`infrastructure/go/` 五件套（GoProcessLauncher →
   GoStdioChannel → GoProtocol → GoVectorReplica / GoToolboxProvider）+
   `infrastructure/search/InMemoryVectorIndex` 的路由与三态降级。

**毕业考**：给引擎加 `text.length`（返回 rune 数与 byte 数）——Go 一个函数 +
一行注册 + 一个单测，Java 加 DTO 与门面方法，协议层零改动。

### 6.3 动手开关（体验两级门控）

```bash
# 真进程管道手测（Go 版 echo）
$ cd engine-server/golang && echo '{"id":1,"method":"sys.ping","params":{}}' | go run ./cmd/toolbox
# 开启态启动（Go 引擎拉起，健康段 goToolbox=UP）
$ java -jar engine-server/build/libs/engine-server.jar \
    --spring.profiles.active=go-toolbox --engine.go.enabled=true
# 健康观测
$ curl -s localhost:8090/api/system/health | python -m json.tool   # 找 goToolbox 段
```

---

## 附录 A：概念速查表（Java ↔ Python ↔ 前端）

| 概念 | Java（本仓库） | Python | 前端 | Go（gotoolbox） |
|------|----------------|--------|------|-----------------|
| 模块化 | package / Gradle 模块 | package（目录 + `__init__.py`）/ pip | ES Module / npm | 包 = 目录 / go.mod（零三方依赖） |
| 依赖注入 | Spring `@Autowired` 构造注入 | 显式传参（无容器） | props / hooks | 显式传参 + main 三步装配 |
| 配置 | application.yml | 环境变量（`ENGINE_*`） | vite 环境文件 | 无配置（参数即帧） |
| 异常体系 | EngineException + 全局 handler | raise + Java 侧翻译 | client.ts 统一抛错 + toast | err 返回值 + error 帧（1001/1002） |
| 异步 | 固定线程池 | 单线程事件循环足够 | async/await（Promise） | goroutine + WaitGroup |
| 并发原语 | synchronized / BlockingQueue | GIL 下队列 | — | sync.WaitGroup / channel |
| 日志 | slf4j/logback | logging → stderr | console → 浏览器 devtools | stderr（stdout 是协议专线） |
| 测试 | JUnit + IT 任务 | 手测/echo 冒烟 | build 零错 + 手测 | go test（`-race` 常开）+ 管道手测 |

## 附录 B：上手命令速查

```bash
# 环境（Windows）
$ scripts/env-check.bat                          # JDK/Python/Node 三件套体检
# 后端（单 JAR 合体，唯一进程 :8090，含 Python 加载 30-60s）
$ ./gradlew :engine-server:bootRun
# 前端开发态（热更新，/api 代理到 :8090）
$ cd engine-server/frontend && npm run dev
# Python 手测
$ echo '{"op":"stats"}' | python engine-server/python/server_stdio.py
# 测试
$ ./gradlew :engine-server:test                  # 单测
$ ./gradlew :engine-server:channelIT             # 通道集成测试（需 Python 环境）
# Go（gotoolbox）
$ cd engine-server/golang && go test ./...       # Go 单测（含 -race 见各包）
$ ./gradlew :engine-server:buildGoToolbox        # 双平台交叉编译（指纹缓存）
```
