# 后端代码规范（backend-standards）

> 本文是 engine-server / engine-gateway 的 Java 侧强约束。新代码合入前逐条对照；
> 与 `docs/architecture.md`（分层职责）配合阅读，本文管"怎么写"，那边管"放哪里"。
> 每条规范尽量给出仓库内的真实样例——照着写，不要照着想象写。

---

## 1. 命名规范

### 1.1 类命名

| 类型 | 后缀/模式 | 仓库样例 |
|------|-----------|----------|
| REST 控制器 | `XxxController` | `DocumentController` |
| 应用服务（用例） | `XxxApplicationService` | `SearchApplicationService` |
| 领域服务 | `XxxDomainService` / `XxxChunker` 等业务名 | `TextChunker` |
| 领域端口（接口） | `XxxProvider` / `XxxStore` / `XxxIndex` / `XxxRepository` | `EmbeddingProvider` `VectorStore` |
| 端口实现 | `技术名 + 端口名` | `SqliteVectorStore` `LuceneFullTextIndex` `ChannelEmbeddingProvider` |
| DTO | `XxxRequest` / `XxxResponse` / `XxxResult` / `XxxDetail` | `SearchRequest` `DocumentDetail` |
| 异常 | `XxxException` | `EngineException` |
| 配置属性 | `XxxProperties` | `UpstreamProperties`（gateway） |

### 1.2 方法与变量

- 方法名动词开头：`embedTexts` / `deleteDocument` / `topK`（布尔查询可省略 is 前缀）
- 布尔变量/字段用 `is/has/can` 前缀：`isAlive()` `degraded`（对齐 Python 端字段名时例外）
- 常量 `UPPER_SNAKE_CASE`；配置键全小写中划线：`engine.python.call-timeout-seconds`
- Python 侧相反（snake_case），跨语言边界字段统一 **camelCase JSON**（见 §5）

### 1.3 包结构

包名 = 层 + 职责：`application.dto`、`infrastructure.python.protocol`、`interfaces.rest.dto`。
新增类先问"这是哪层的什么职责"，包名自然确定。

---

## 2. 分层边界（DDD 四层）

```
interfaces → application → domain ← infrastructure
```

| 规则 | 说明 |
|------|------|
| domain 零框架依赖 | 不 import Spring/SQLite/Lucene/py4j 任何类型；只允许 JDK 标准库 |
| 依赖倒置 | domain 定义端口接口（`domain.repository` 包），infrastructure 实现；application 只面向端口编程 |
| interfaces 不写业务 | Controller 只做：参数校验 → 调用用例 → 信封包装。出现 if 业务分支即为越界 |
| application 不碰技术细节 | 不出现 SQL/Lucene/py4j 类型；只编排端口调用与事务/异步 |
| infrastructure 不做决策 | 实现类不含业务规则（何时降级、块多大是 domain/application 的事） |

**反面清单**（review 时直接打回）：
- Controller 里 new 业务对象/写状态机
- domain 里出现 `org.springframework.*` import
- application 直接 import `SqliteVectorStore`（应依赖 `VectorStore` 端口）
- infrastructure 抛 HTTP 语义异常（应抛 `EngineException`，由 interfaces 翻译）

---

## 3. 错误码分段与 API 信封

### 3.1 统一信封 `ApiResponse{code, message, data}`

```java
// 成功
{"code": 0, "message": "ok", "data": {...}}
// 失败
{"code": 1000, "message": "query: 不能为空", "data": null}
```

### 3.2 双层错误语义（关键设计）

| 层 | 管什么 | 值域 |
|----|--------|------|
| HTTP 状态码 | 传输层怎么了 | 400 / 404 / 500 / 503 |
| 信封 code | **谁的责任** | 分段整数 |

映射（`GlobalExceptionHandler.envelopeCode`）：

| HTTP | 信封分段 | 责任 | 典型场景 |
|------|----------|------|----------|
| 400 | **1xxx**（1000） | 参数错误 | 空/超限查询、@Valid 失败、上传超 50MB |
| 404 | **2xxx**（2000） | 领域规则 | 文档不存在、删除已删资源 |
| 503 | **3xxx**（3000） | Python 下游 | 引擎不可达、维度不匹配拦截 |
| 其他 | **5xxx**（5000） | 系统错误 | 未预期异常（兜底） |

前端按信封分段决定交互（1xxx 提示改输入、3xxx 提示稍后重试）；HTTP 状态供网关/监控/重试策略用。两层不可混用——**禁止**把 500 塞进信封 code 而HTTP 返回 200。

### 3.3 异常处理约定

- 业务代码只抛 `EngineException` 及其工厂：`badRequest()` / `notFound()` / `internal()` / `downstream()`
- 工厂方法自带 HTTP 语义，`GlobalExceptionHandler` 统一翻译，**Controller/Service 不写 try-catch 返回错误响应**
- 日志分级：预期业务错误 `warn`（无堆栈）；未预期 `error`（异常对象必须作为最后一个 log 参数，否则 logback 不打堆栈）
- 未预期异常对外统一话术"服务内部错误，请查看服务端日志"——**堆栈不外泄**

---

## 4. 配置约定

- 自定义配置统一挂 `engine.*`（server）/ `gateway.*`（gateway）命名空间，与 Spring 官方键隔离
- 每个可调参数必须带默认值：`@Value("${engine.python.py4j-port:25335}")`
- 改行为优先改配置，其次才是改代码；新增配置必须在 `application.yml` 写注释说明量纲与影响
- Profile 控制通道装配：`failover`（默认）/ `py4j` / `stdio`

---

## 5. 跨语言边界（Java ↔ Python）

- **只传 JSON 字符串**：Py4J/stdio 方法签名只用 `String`/`List<String>`，两端各自反序列化成强类型（协议类：`PythonProtocol` ↔ python 端 dict）
- JSON 字段统一 camelCase（Python 端 `ensure_ascii=False` 保中文原样）
- Python 端异常直接 raise，Java 端翻译成 `EngineException.downstream`——**错误不吞**
- stdio 协议纪律：stdout 只走协议行（日志一律 stderr）、未知 op 返回错误信封、写完即 flush

---

## 6. 测试约定

### 6.1 布局与命名

- 单测与被测类同包：`SqliteVectorStore` ↔ `SqliteVectorStoreTest`
- 集成测试后缀 `IntegrationTest`（如 `PythonChannelIntegrationTest`，经 `gradlew :engine-server:channelIT` 独立任务跑，不打进日常 build）
- 测试方法名中文描述场景：`上传txt文档_最终到达INDEXED状态()`——可读性优先于命名教条

### 6.2 分层测试策略

| 层 | 测什么 | 手段 | 现有样例 |
|----|--------|------|----------|
| domain/infra 单测 | 纯逻辑：编解码、排序、分块 | 无 Spring 依赖，直接 new | `VectorBlobCodecTest`（BLOB 往返精度） |
| application 单测 | 用例编排：状态机、失败路径 | **假端口注入**（fake EmbeddingProvider），不起 Spring | `DocumentApplicationServiceTest` |
| channel 集成 | 真 Python 进程：双通道回退 | 独立 IT 任务，本机需 Python 环境 | `PythonChannelIntegrationTest` |
| REST 冒烟 | 四接口 + 4xx 场景 | curl（验收记录留档） | 7.1 手测记录 |

### 6.3 原则

- **不 mock 值对象**，只 fake 端口（`EmbeddingProvider`）——领域对象直接构造
- 涉及精度的断言（向量/分数）用误差容忍而非精确相等
- 每修一个 bug，先补一个能复现它的测试（今日实例：Py4J 冷启动竞态）

---

## 7. 日志约定

- 日志框架 slf4j + logback（Spring 默认），**禁止** `System.out.println`（Python 桥接输出例外，见 `PythonProcessLauncher` 的 `[python-err]` 桥接）
- 中文消息允许（本项目面向中文读者），但关键定位信息（类名/端口/ID）保持原样
- 拉起子进程/资源切换等生命周期事件必须 `info` 一行（如"Py4J 通道就绪 (127.0.0.1:25335)"）——运维靠它判断启动进度

---

## 8. gateway 侧补充

- 网关是哑管道：**不解析业务 body**（multipart 自动解析已关闭，`application.yml` 有踩坑注释）
- 反代必须 `InputStream` 流式透传（SSE 前置兼容），禁止 `String` 整包读
- OkHttp 单例注入（`OkHttpConfig`），禁止在 Controller 里 new client
- 上游错误原样透传（状态码 + 错误体），**不吞错不改写**
