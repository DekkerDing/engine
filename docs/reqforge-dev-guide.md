# reqforge — 开发手册

> 面向：后端开发、前端开发、Flutter 开发 | 版本：1.3（单 JAR 合体） | 日期：2026-09-13

---

## 目录

1. [架构总览](#1-架构总览)
2. [项目结构与模块职责](#2-项目结构与模块职责)
3. [后端开发指南 (engine-server)](#3-后端开发指南-engine-server)
4. [前端开发指南 (frontend)](#4-前端开发指南-frontend)
5. [APP 开发指南 (reqforge_app)](#5-app-开发指南-reqforge_app)
6. [API 接口文档](#6-api-接口文档)
7. [启动与运行](#7-启动与运行)
8. [打包与部署](#8-打包与部署)
9. [扩展开发指南](#9-扩展开发指南)
10. [常见开发问题](#10-常见开发问题)

---

## 1. 架构总览

### 1.1 系统拓扑

```
浏览器 (React SPA)                    APP (Flutter)
     │                                     │
     │ HTTP 请求                            │ HTTP 请求
     ▼                                     ▼
engine-server (:8090)  ← 单 JAR 合体（唯一进程、唯一端口）
  ├─ /api/** 业务 API（控制器映射直接带前缀）
  ├─ 静态资源托管 + SPA 回退（jar 内 BOOT-INF/classes/static/）
  ├─ actuator/health（容器 HEALTHCHECK 探测口）
  ├─ DDD 四层：interfaces → application → domain ← infrastructure
  ├─ SQLite（engine.db）
  ├─ Lucene 全文索引
  └─ Python 引擎（Py4J :25335 / stdio 备通道）
        └─ bge-small-zh-v1.5 文本向量化
```

### 1.2 核心模块对应关系

| 模块 | 技术栈 | 端口 | 职责 |
|------|--------|------|------|
| engine-server | Java 8 + Spring Boot + SQLite + Lucene + Py4J | **8090**（唯一） | 需求 CRUD、渲染规约、工件管理、导出 + 静态资源 + SPA 回退 + 健康聚合 |
| engine-server/frontend | React 18 + TypeScript + Ant Design 5 + Vite 5 | — | Web 前端 UI（server 的资产，src 之外） |
| reqforge_app | Flutter 3.x + Dart | — | Android/iOS APP |

> **v1.1 变更**：`engine-gateway` 已由 Java/Spring Boot 替换为 Go/net/http 实现。
> **v1.2 变更**：旧 Java gateway 源码已移除（保留在 git 历史），`engine-gateway/` 目录
> 即为 Go 工程；前端源码迁至仓库根 `frontend/`。对外端口、路由、契约完全不变。
> **v1.3 变更（单 JAR 合体）**：Go 网关退役——职能（静态资源、SPA 回退、缓存头、
> 健康聚合、/api 前缀）内化为 engine-server 的 `interfaces.web` 三件套与控制器前缀映射；
> `frontend/` 移入 `engine-server/frontend/`；对外端口、路由、契约完全不变。
> 详见 [合体设计文档](file:///F:/workspace/engine/docs/reqforge-single-jar-design.md)。

### 1.3 DDD 分层依赖方向

```
interfaces (REST 适配器)      ← 外部世界第一站，参数校验 + 信封包装
    │  调用
    ▼
application (用例编排)         ← 事务边界、状态机推进、缓存
    │  调用（只依赖端口接口）
    ▼
domain (领域核心)              ← 纯业务规则，零框架依赖
    ▲  实现（依赖倒置）
    │
infrastructure (技术细节)      ← SQLite/Lucene/Python/文件解析
```

**关键规则**（详见 [backend-standards.md](file:///F:/workspace/engine/docs/backend-standards.md)）：
- domain 不 import 任何 Spring/SQLite/Lucene/py4j 类型
- application 只面向 domain 端口编程，不碰 infrastructure 具体类
- interfaces 只做协议转换，不含业务判断

---

## 2. 项目结构与模块职责

### 2.1 engine-server 后端源码结构

```
engine-server/src/main/java/io/github/dekkerding/engine/
├── ServerApplication.java                    # Spring Boot 入口
├── interfaces/                               # 接口层（REST 适配器）
│   └── rest/
│       ├── RequirementController.java        # 需求 CRUD、提交、渲染、导出 API
│       ├── DocumentController.java           # 文档上传/管理 API
│       ├── SearchController.java             # 检索 API
│       ├── SystemController.java             # 系统健康 API
│       ├── ImageController.java              # 图片上传 API
│       ├── ImageBatchController.java         # 图片批量导入 API
│       ├── GlobalExceptionHandler.java       # 全局异常 → ApiResponse 翻译
│       ├── ApiResponse.java                  # 统一响应信封 {code, message, data}
│       └── dto/                              # REST 层 DTO
│           ├── RequirementDto.java           # 需求增/改 DTO
│           ├── RequirementDetail.java        # 需求详情响应
│           ├── DocumentDetail.java
│           ├── SearchResult.java
│           └── PageResult.java
├── application/                              # 应用层（用例编排）
│   ├── RequirementApplicationService.java    # 需求用例：创建/提交/渲染/导出
│   ├── DocumentApplicationService.java       # 文档用例：上传→解析→索引
│   ├── SearchApplicationService.java         # 检索用例：混合检索+RRF融合
│   ├── ImageApplicationService.java          # 图片用例
│   ├── ImageImportApplicationService.java    # 图片批量导入用例
│   ├── SystemQueryService.java               # 系统健康查询
│   ├── EmbeddingProviderRegistry.java        # 向量化提供者注册（按模态路由）
│   ├── event/                                # 应用事件
│   │   └── SearchCacheInvalidationEvent.java
│   └── dto/                                  # 应用层 DTO
│       ├── RequirementDetail.java
│       ├── DocumentDetail.java
│       ├── ImageDetail.java
│       ├── SearchHit.java
│       └── SearchResult.java
├── domain/                                   # 领域层（核心业务规则，零框架依赖）
│   ├── exception/
│   │   └── EngineException.java              # 领域异常工厂
│   ├── model/                                # 领域模型
│   │   ├── requirement/                      # ★ 需求模块核心模型
│   │   │   ├── RequirementSubmission.java    # 需求聚合根（ID+表单+状态+工件）
│   │   │   ├── RequirementForm.java          # 需求表单（3步结构）
│   │   │   ├── RequirementIR.java            # 需求中间表示（渲染输入）
│   │   │   ├── RequirementStatus.java        # 需求状态枚举
│   │   │   ├── MdArtifact.java               # 规约工件（版本化）
│   │   │   ├── RenderTarget.java             # 渲染目标枚举
│   │   │   └── Attachment.java               # 附件
│   │   ├── document/                         # 文档模块
│   │   ├── image/                            # 图片模块
│   │   ├── vector/                           # 向量模块
│   │   └── search/                           # 检索模块
│   ├── repository/                           # 端口接口（domain 定义，infra 实现）
│   │   ├── RequirementRepository.java        # 需求持久化端口
│   │   ├── MdArtifactRepository.java         # 工件持久化端口
│   │   ├── AttachmentRepository.java         # 附件元数据端口
│   │   ├── AttachmentStore.java              # 附件物理存储端口
│   │   ├── DocumentRepository.java
│   │   ├── VectorStore.java
│   │   └── EmbeddingProvider.java
│   └── service/                              # 领域服务（纯函数）
│       ├── RequirementValidator.java         # ★ 闸门校验器（提交完整性检查）
│       └── renderer/                         # ★ 渲染器
│           ├── SpecRenderer.java             # 渲染器端口接口
│           ├── VibecodingRenderer.java       # VibeCoding 渲染器 → TASK.md
│           └── OpenSpecRenderer.java         # OpenSpec 渲染器 → .yaml + 多 .md
├── infrastructure/                           # 基础设施层（端口实现）
│   ├── persistence/                          # SQLite 数据访问
│   │   ├── SqliteRequirementRepository.java  # ★ 需求仓储实现（JSON 序列化）
│   │   ├── SqliteMdArtifactRepository.java   # ★ 工件仓储实现
│   │   ├── SqliteAttachmentRepository.java   # 附件元数据仓储
│   │   ├── SqliteDocumentRepository.java
│   │   ├── SqliteVectorStore.java
│   │   ├── SqliteConnectionManager.java      # SQLite 连接管理
│   │   ├── DatabaseMigrator.java             # Schema 版本化迁移
│   │   ├── RequirementJson.java              # 需求 JSON 序列化/反序列化
│   │   └── VectorBlobCodec.java              # 向量 BLOB 编解码
│   ├── requirement/                          # ★ 需求基础设施
│   │   ├── LocalFileAttachmentStore.java     # 本地文件附件存储
│   │   └── AttachmentFormatGuard.java        # 附件格式白名单校验
│   ├── python/                               # Python 引擎通道
│   ├── go/                                   # Go 引擎通道（gotoolbox，D10 正典）
│   │   ├── GoProcessLauncher.java            #   二进制定位链五级（直指→环境变量→golang 直用→go run→classpath 解压）
│   │   ├── GoStdioChannel.java               #   stdio 通道（毒丸/串行/迟到帧）+ 健康探活
│   │   ├── protocol/GoProtocol.java          #   帧 DTO（键名契约单源）
│   │   ├── GoToolboxProvider.java            #   降级向量化门面（第二级门控 go-toolbox Profile）
│   │   └── GoVectorReplica.java              #   索引副本门面（第一级门控 engine.go.enabled）
│   ├── fulltext/                             # Lucene 全文索引
│   ├── document/                             # 文档解析（txt/docx/pdf）
│   └── search/                               # 内存向量索引（Go 轨路由 + 本地兜底同源）
├── python/                                   # Python 引擎源码（server 资产）
│   ├── server_py4j.py                        # Py4J 通道入口
│   ├── server_stdio.py                       # stdio 通道入口
│   └── core/
└── golang/                                   # Go 工具箱引擎源码（server 资产，Go 1.21+ 零三方依赖）
    ├── go.mod                                #   module engine/gotoolbox
    ├── cmd/toolbox/main.go                   #   入口：装配 router → 注册方法 → 协议循环
    └── internal/                             #   protocol / router / engine / text / hashing / vector
```

### 2.2 interfaces.web 静态三件套（原网关职能的内化）

Go 网关退役后，其四项职能由 `engine-server` 的 `interfaces.web` 原生承担（对外契约逐字节不变）：

```
engine-server/src/main/java/.../interfaces/web/
├── WebStaticConfig.java        # 三段 resource handler：/assets/**、/index.html+/favicon.ico
│                               # （noCache）、/** 兜底挂 SpaFallbackResolver
├── SpaFallbackResolver.java    # SPA 回退：api/|actuator/ 前缀拒绝→404；目录型→入口页；
│                               # 文件存在→原样；前端路由→index.html 200
└── AssetCacheFilter.java       # /assets/* → public, max-age=31536000, immutable
                                #（手写头——Spring 5.3 无 CacheControl.immutable()）
```

| 原网关组件 | 内化后的 Java 组件 | 契约要点 |
|-----------|-------------------|----------|
| `handler/proxy.go`（/api 剥前缀反代） | 控制器类级 `@RequestMapping("/api/...")` | API 全在 `/api/**` 命名空间（D1 终案——Filter 剥前缀与同名前端路由冲突，实测推翻） |
| `handler/static.go` | `WebStaticConfig` + `SpaFallbackResolver` | 路由表见 [合体规约](file:///F:/workspace/engine/docs/reqforge-single-jar-spec.md) |
| `middleware/cache.go` | `AssetCacheFilter` | 缓存头策略逐字节一致 |
| `handler/health.go`（周期探测） | `SystemController` 直调 `SystemQueryService` | 合体信封五段；实时计算替代探测缓存 |

Go 网关的设计档案见 [历史文档](file:///F:/workspace/engine/docs/reqforge-gateway-go-design.md)（已被合体取代）。

### 2.3 frontend 前端源码结构

```
frontend/src/
├── main.tsx                                  # Vite 入口
├── App.tsx                                   # 路由定义
├── vite-env.d.ts                             # Vite 类型声明
├── api/                                      # ★ API 客户端层（唯一允许发请求的地方）
│   ├── client.ts                             # axios 实例：信封解析、默认错误 toast
│   ├── types.ts                              # 后端 DTO 类型定义
│   ├── requirements.ts                       # ★ 需求 API 方法集
│   ├── documents.ts                          # 文档 API 方法集
│   ├── search.ts                             # 检索 API 方法集
│   ├── images.ts                             # 图片 API 方法集
│   └── system.ts                             # 系统健康 API
├── layouts/
│   ├── AppLayout.tsx                         # 布局骨架（顶部导航 + 侧边栏 + 内容区）
│   └── AppLayout.css                         # Grid 布局体系
├── pages/
│   ├── dashboard/                            # 仪表盘页
│   ├── documents/                            # 文档管理页
│   ├── images/                               # 图片管理页
│   ├── search/                               # 检索页
│   └── requirements/                         # ★ 需求模块页面
│       ├── RequirementListPage.tsx            # 需求列表页
│       ├── RequirementFormPage.tsx            # 三步表单页
│       ├── RequirementWorkshopPage.tsx        # 需求工坊页
│       ├── RequirementExportPage.tsx          # 导出页
│       └── formFragments.tsx                  # 表单子组件
├── components/                               # 共享组件
│   ├── StatusBadge.tsx                       # 状态徽标
│   ├── HighlightText.tsx                     # 高亮文本
│   └── DegradedBanner.tsx                    # 降级横幅
└── hooks/
    └── usePolling.ts                         # 轮询 hook
```

### 2.4 reqforge_app Flutter 源码结构

```
reqforge_app/lib/
├── main.dart                                 # App 入口
├── config/
│   └── api_config.dart                       # ★ API 地址配置（修改此处切换环境）
├── models/
│   ├── requirement.dart                      # 需求数据模型
│   └── api_response.dart                     # 通用 API 响应模型
├── services/
│   └── requirement_service.dart              # ★ 需求 API 调用服务
├── pages/
│   ├── requirement_list_page.dart            # 需求列表页
│   ├── requirement_form_page.dart            # 三步表单页
│   ├── requirement_workshop_page.dart        # 工坊页
│   └── requirement_export_page.dart          # 导出页
└── widgets/
    ├── status_badge.dart                     # 状态徽标
    └── requirement_card.dart                 # 需求卡片
```

---

## 3. 后端开发指南 (engine-server)

### 3.1 核心类职责速查

| 类 | 所在层 | 职责 | 修改频率 |
|----|--------|------|----------|
| [RequirementController.java](file:///F:/workspace/engine/engine-server/src/main/java/io/github/dekkerding/engine/interfaces/rest/RequirementController.java) | interfaces | REST 端点：CRUD + 提交 + 渲染 + 导出 | 新增接口时改 |
| [RequirementApplicationService.java](file:///F:/workspace/engine/engine-server/src/main/java/io/github/dekkerding/engine/application/RequirementApplicationService.java) | application | 用例编排：创建→提交→渲染→导出全流程 | 改业务流程时改 |
| [RequirementSubmission.java](file:///F:/workspace/engine/engine-server/src/main/java/io/github/dekkerding/engine/domain/model/requirement/RequirementSubmission.java) | domain | 需求聚合根（所有领域操作入口） | 改需求模型时改 |
| [RequirementForm.java](file:///F:/workspace/engine/engine-server/src/main/java/io/github/dekkerding/engine/domain/model/requirement/RequirementForm.java) | domain | 表单数据结构（3步） | 增表单字段时改 |
| [RequirementValidator.java](file:///F:/workspace/engine/engine-server/src/main/java/io/github/dekkerding/engine/domain/service/RequirementValidator.java) | domain | 提交闸门校验 | 改校验规则时改 |
| [VibecodingRenderer.java](file:///F:/workspace/engine/engine-server/src/main/java/io/github/dekkerding/engine/domain/service/renderer/VibecodingRenderer.java) | domain | 生成 TASK.md | 改模版输出时改 |
| [OpenSpecRenderer.java](file:///F:/workspace/engine/engine-server/src/main/java/io/github/dekkerding/engine/domain/service/renderer/OpenSpecRenderer.java) | domain | 生成 .yaml + spec 文件 | 改 OpenSpec 格式时改 |
| [SqliteRequirementRepository.java](file:///F:/workspace/engine/engine-server/src/main/java/io/github/dekkerding/engine/infrastructure/persistence/SqliteRequirementRepository.java) | infrastructure | SQLite 持久化 | 改存储结构时改 |
| [DatabaseMigrator.java](file:///F:/workspace/engine/engine-server/src/main/java/io/github/dekkerding/engine/infrastructure/persistence/DatabaseMigrator.java) | infrastructure | Schema 版本化迁移 | 加表/加列时改 |

### 3.2 需求生命周期（状态机）

```
DRAFT ──[提交闸门]──▶ SUBMITTED ──[导出]──▶ EXPORTED
  ▲                        │                    │
  └────[编辑保存]──────────┘                    │
  └─────────────────────────────────────────────┘
```

状态变更入口在 `RequirementSubmission` 聚合根：
- `submit()` → DRAFT → SUBMITTED（需过 Validator）
- `edit()` → 任意 → DRAFT（清空工件 stale 标记）
- `export()` → SUBMITTED/EXPORTED → EXPORTED

**禁止**在 Controller 或 ApplicationService 中直接修改 `status` 字段——必须通过聚合根方法。

### 3.3 新增 API 端点标准流程

以"新增一个需求统计接口"为例：

#### Step 1：domain 层（如有领域逻辑）

无需改 model（查询类接口不改变领域状态）

#### Step 2：infrastructure 层 → 仓储接口

```java
// domain/repository/RequirementRepository.java —— 新增方法
List<RequirementSubmission> findByStatusAndDateRange(RequirementStatus status, LocalDate from, LocalDate to);
```

```java
// infrastructure/persistence/SqliteRequirementRepository.java —— 实现
@Override
public List<RequirementSubmission> findByStatusAndDateRange(...) {
    // SQL 查询 + JSON 反序列化
}
```

#### Step 3：application 层 → 应用服务

```java
// application/RequirementApplicationService.java
public Map<String, Long> getStatistics(LocalDate from, LocalDate to) {
    List<RequirementSubmission> subs = requirementRepository.findByStatusAndDateRange(null, from, to);
    return subs.stream().collect(Collectors.groupingBy(
        s -> s.getStatus().name(), Collectors.counting()
    ));
}
```

#### Step 4：interfaces 层 → Controller

```java
// interfaces/rest/RequirementController.java
@GetMapping("/statistics")
public ApiResponse<Map<String, Long>> statistics(
    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
) {
    return ApiResponse.ok(requirementService.getStatistics(from, to));
}
```

### 3.4 新增渲染目标

当前支持：`vibecoding` / `openspec`。新增目标（如 `word`）：

#### Step 1：domain 层 → 新建渲染器

```java
// domain/service/renderer/WordRenderer.java
public class WordRenderer implements SpecRenderer {
    @Override
    public RenderTarget target() { return RenderTarget.WORD; }

    @Override
    public Map<String, String> render(RequirementSubmission submission) {
        // 根据 submission.getForm() 生成 .docx 内容
        Map<String, String> files = new LinkedHashMap<>();
        files.put("requirement.docx", buildDocxContent(submission));
        return files;
    }
}
```

#### Step 2：domain 层 → 枚举新增值

```java
// domain/model/requirement/RenderTarget.java
public enum RenderTarget {
    VIBECODING,
    OPENSPEC,
    WORD  // ← 新增
}
```

#### Step 3：application 层 → 注册渲染器

```java
// application/RequirementApplicationService.java
@PostConstruct
void registerRenderers() {
    renderers.put(RenderTarget.VIBECODING, new VibecodingRenderer());
    renderers.put(RenderTarget.OPENSPEC, new OpenSpecRenderer());
    renderers.put(RenderTarget.WORD, new WordRenderer());  // ← 新增
}
```

前端/APP 侧同步新增目标选项即可，无需改 API 签名。

### 3.5 新增表单字段

以"新增一个 `department` 字段"为例：

1. [RequirementForm.java](file:///F:/workspace/engine/engine-server/src/main/java/io/github/dekkerding/engine/domain/model/requirement/RequirementForm.java) — 在 `BasicInfo` 内部类加字段
2. [RequirementDto.java](file:///F:/workspace/engine/engine-server/src/main/java/io/github/dekkerding/engine/interfaces/rest/dto/RequirementDto.java) — 同步加字段
3. [RequirementJson.java](file:///F:/workspace/engine/engine-server/src/main/java/io/github/dekkerding/engine/infrastructure/persistence/RequirementJson.java) — 确保 JSON 序列化/反序列化覆盖新字段（使用 Jackson，字段自动映射）
4. [DatabaseMigrator.java](file:///F:/workspace/engine/engine-server/src/main/java/io/github/dekkerding/engine/infrastructure/persistence/DatabaseMigrator.java) — 如果存的是 JSON 列则无需改表（Schema-less）
5. 前端 [formFragments.tsx](file:///F:/workspace/engine/engine-server/frontend/src/pages/requirements/formFragments.tsx) — 加表单输入项
6. 前端 [types.ts](file:///F:/workspace/engine/engine-server/frontend/src/api/types.ts) — 同步 TS 类型
7. APP 端 models 和 pages 同步

### 3.6 后端开发规范速查

详见 [backend-standards.md](file:///F:/workspace/engine/docs/backend-standards.md)，关键点：

- **类命名**：Controller → `XxxController`，ApplicationService → `XxxApplicationService`，Repository 端口 → `XxxRepository`，实现 → `技术名Repository`
- **依赖倒置**：application/domain 只依赖端口接口，不 import 具体实现
- **异常体系**：业务代码只抛 `EngineException`（工厂：`badRequest()` / `notFound()` / `internal()` / `downstream()`）
- **API 信封**：`ApiResponse{code, message, data}`，code=0 成功，1xxx 客户端错误，2xxx 领域错误，3xxx 下游错误，5xxx 系统错误
- **Controller 不写业务**：不写 if 业务分支、不 new 业务对象、不写状态机
- **测试**：与被测类同包，方法名中文描述场景

---

## 4. 前端开发指南 (frontend)

### 4.1 核心文件职责

| 文件 | 职责 | 修改时机 |
|------|------|----------|
| [api/requirements.ts](file:///F:/workspace/engine/engine-server/frontend/src/api/requirements.ts) | 需求 API 调用方法 | 新增后端接口时 |
| [api/types.ts](file:///F:/workspace/engine/engine-server/frontend/src/api/types.ts) | TypeScript 类型定义 | 后端 DTO 变更时 |
| [api/client.ts](file:///F:/workspace/engine/engine-server/frontend/src/api/client.ts) | axios 实例、拦截器 | 改全局请求行为时 |
| [App.tsx](file:///F:/workspace/engine/engine-server/frontend/src/App.tsx) | 路由定义 | 新增页面时 |
| [layouts/AppLayout.tsx](file:///F:/workspace/engine/engine-server/frontend/src/layouts/AppLayout.tsx) | 全局布局 | 改导航菜单时 |
| [pages/requirements/RequirementListPage.tsx](file:///F:/workspace/engine/engine-server/frontend/src/pages/requirements/RequirementListPage.tsx) | 需求列表 | 改列表功能时 |
| [pages/requirements/RequirementFormPage.tsx](file:///F:/workspace/engine/engine-server/frontend/src/pages/requirements/RequirementFormPage.tsx) | 三步表单 | 改表单步骤时 |
| [pages/requirements/RequirementWorkshopPage.tsx](file:///F:/workspace/engine/engine-server/frontend/src/pages/requirements/RequirementWorkshopPage.tsx) | 需求工坊 | 改工坊功能时 |
| [pages/requirements/RequirementExportPage.tsx](file:///F:/workspace/engine/engine-server/frontend/src/pages/requirements/RequirementExportPage.tsx) | 导出页 | 改导出功能时 |
| [pages/requirements/formFragments.tsx](file:///F:/workspace/engine/engine-server/frontend/src/pages/requirements/formFragments.tsx) | 表单子组件 | 改表单字段时 |

### 4.2 新增前端页面标准流程

#### Step 1：定义路由

```typescript
// App.tsx
<Route path="/requirements" element={<RequirementListPage />} />
<Route path="/requirements/new" element={<RequirementFormPage />} />
<Route path="/requirements/:id" element={<RequirementWorkshopPage />} />
<Route path="/requirements/:id/edit" element={<RequirementFormPage />} />
<Route path="/requirements/:id/export" element={<RequirementExportPage />} />
<Route path="/my-new-page" element={<MyNewPage />} />  // ← 新增
```

#### Step 2：创建页面组件

```tsx
// pages/my-new/MyNewPage.tsx
import React from 'react';

export default function MyNewPage() {
  return <div>My New Page</div>;
}
```

#### Step 3：如需 API 调用 → api 层

```typescript
// api/requirements.ts 或新建 api/my-feature.ts
import client from './client';

export async function getMyData(): Promise<MyData> {
  const res = await client.get('/api/my-endpoint');
  return res.data;
}
```

#### Step 4：如需新类型 → types.ts

```typescript
// api/types.ts
export interface MyDataType {
  id: string;
  name: string;
}
```

### 4.3 前端开发规范速查

详见 [frontend-standards.md](file:///F:/workspace/engine/docs/frontend-standards.md)，关键点：

- **布局**：全站使用 Grid 骨架（顶部 56px + 侧边 208px + 内容区 1fr），新页面只进 main 区
- **间距**：四档 token（4/8/16/24），不允许出现其他值
- **字号**：页面标题 18px / 卡片标题 15px / 正文 14px / 辅助 12px
- **API 请求**：只走 `api/` 目录的封装方法，组件禁止直接 axios/fetch
- **命名**：页面组件 `XxxPage`，hook `useXxx`，API 方法动词开头
- **TS strict**：any 需注释理由
- **lint**：提交前 `npm run build` 必须零报错

### 4.4 页面功能速查

| 页面 | 路由 | 核心功能 | 关键 Hook/API |
|------|------|----------|---------------|
| 需求列表 | `/requirements` | 分页表格、状态/优先级筛选、搜索、跳转 | `fetchRequirements({status,q,page,size})` |
| 提交需求 | `/requirements/new` | 三步表单（基本信息→背景功能→验收约束）、暂存、提交 | `createRequirement()` → `submitRequirement(id)` |
| 编辑需求 | `/requirements/:id/edit` | 表单回填、保存（状态回 DRAFT） | `fetchRequirement(id)` → `updateRequirement(id, dto)` |
| 需求工坊 | `/requirements/:id` | 左栏摘要+表单、右栏渲染/工件/修订 | `renderRequirement(id,target)` → `fetchArtifacts(id,version)` |
| 导出 | `/requirements/:id/export` | 工件预览、切换目标、下载 | `exportRequirement(id,target)` |

---

## 5. APP 开发指南 (reqforge_app)

### 5.1 核心文件职责

| 文件 | 职责 |
|------|------|
| `lib/main.dart` | App 入口、路由注册、MaterialApp 配置 |
| `lib/config/api_config.dart` | API 地址配置（修改环境指向此处） |
| `lib/models/requirement.dart` | 需求数据模型（与后端 DTO 对应） |
| `lib/models/api_response.dart` | 通用 API 响应模型 |
| `lib/services/requirement_service.dart` | HTTP 请求封装（dart:io / http 包） |
| `lib/pages/requirement_list_page.dart` | 需求列表（卡片式 + 下拉刷新 + 筛选） |
| `lib/pages/requirement_form_page.dart` | 三步表单（Step 导航 + 底部操作栏） |
| `lib/pages/requirement_workshop_page.dart` | 工坊（Tab 切换 + BottomSheet 预览） |
| `lib/pages/requirement_export_page.dart` | 导出页 |
| `lib/widgets/status_badge.dart` | 状态徽标组件 |
| `lib/widgets/requirement_card.dart` | 需求卡片组件 |

### 5.2 环境配置

编辑 `lib/config/api_config.dart`：

```dart
class ApiConfig {
  // 模拟器 → 宿主机
  static const String baseUrl = 'http://10.0.2.2:8090/api';

  // 真机（同 WiFi）→ 电脑 IP
  // static const String baseUrl = 'http://192.168.1.100:8090/api';

  // 生产环境
  // static const String baseUrl = 'https://your-server.com/api';
}
```

### 5.3 新增 Flutter 页面

1. 在 `lib/pages/` 下新建 Dart 文件
2. 在 `main.dart` 注册路由
3. 如需 API：在 `lib/services/` 新增或扩充方法
4. 如需新模型：在 `lib/models/` 新增

### 5.4 Flutter 开发规范

- 状态管理：推荐 Provider 或 Riverpod（按团队选型）
- 命名：文件 snake_case，类 PascalCase，方法 camelCase
- API 封装：统一走 services 层，页面不直接调 http
- 错误处理：统一 try-catch + 用户友好提示
- 提交前：`flutter analyze` 零报错

---

## 6. API 接口文档

### 6.1 通用约定

| 约定 | 说明 |
|------|------|
| 基础路径 | `/api`（控制器映射前缀，D1 终案——前端路由与 API 命名空间物理隔离） |
| 请求格式 | JSON（Content-Type: application/json） |
| 响应格式 | `{"code": 0, "message": "ok", "data": {...}}` |
| 错误码分段 | 0=成功，1xxx=参数错误，2xxx=领域错误，3xxx=下游错误，5xxx=系统错误 |
| 认证 | 当前无（后期按需添加 JWT/Session）

### 6.2 需求 API 完整列表

#### 6.2.1 创建需求

```
POST /api/requirements
Content-Type: application/json

Request Body (RequirementDto):
{
  "basic": {
    "title": "订单批量导出Excel",      // 必填
    "requester": "张三",              // 必填
    "priority": "HIGH",               // 可选：LOW/MEDIUM/HIGH/URGENT
    "targetDate": "2026-10-01"       // 可选
  },
  "background": {
    "painPoints": ["每月汇总耗时2天", "手工复制易出错"],
    "affectedScope": "运营部门",
    "metrics": "处理时间从2天 → 1分钟"
  },
  "features": [
    {
      "asWho": "运营专员",           // 必填（三要素）
      "wants": "按日期筛选后导出工单",
      "soThat": "减少手工汇总时间"
    }
  ],
  "acceptanceCriteria": [
    {
      "when": "选择日期范围, 点击导出",
      "then": "10秒内下载xlsx文件"   // 必填（WHEN+THEN）
    }
  ],
  "constraints": {
    "technical": ["文件≤10万行"],
    "compliance": []
  }
}

Response 200:
{
  "code": 0,
  "message": "ok",
  "data": {
    "id": "uuid-36位字符串"
  }
}
```

#### 6.2.2 获取需求详情

```
GET /api/requirements/{id}

Response 200:
{
  "code": 0,
  "data": {
    "id": "...",
    "status": "DRAFT",
    "form": { ... },
    "artifacts": { "vibecoding": { "version": 1 }, "openspec": null },
    "createdAt": "2026-09-13T10:00:00",
    "updatedAt": "2026-09-13T10:00:00"
  }
}
```

#### 6.2.3 更新需求

```
PUT /api/requirements/{id}
Content-Type: application/json

Body: 同创建接口 (RequirementDto)

Response 200:
{
  "code": 0,
  "data": { "id": "...", "status": "DRAFT" }   // ★ 编辑后状态回 DRAFT，工件过期
}
```

#### 6.2.4 提交需求（闸门校验）

```
POST /api/requirements/{id}/submit

Response 200 (通过):
{
  "code": 0,
  "data": { "id": "...", "status": "SUBMITTED" }
}

Response 422 (不通过):
{
  "code": 1000,
  "message": "提交校验不通过：\n- 标题不能为空\n- 需要至少一条功能描述\n- 第1条用户故事缺少'以便'",
  "data": null
}
```

#### 6.2.5 渲染规约

```
POST /api/requirements/{id}/render
Content-Type: application/json

Request Body:
{
  "target": "vibecoding"    // 或 "openspec"
}

Response 200:
{
  "code": 0,
  "data": {
    "target": "vibecoding",
    "version": 1,
    "files": {
      "TASK.md": "# 任务目标\n..."
    }
  }
}
```

#### 6.2.6 获取工件内容

```
GET /api/requirements/{id}/artifacts/{version}?target=vibecoding

Response 200:
{
  "code": 0,
  "data": {
    "target": "vibecoding",
    "version": 1,
    "isLatest": true,
    "isStale": false,
    "files": { "TASK.md": "..." }
  }
}
```

#### 6.2.7 工件修订

```
PUT /api/requirements/{id}/artifacts/{version}?target=vibecoding
Content-Type: application/json

Request Body:
{
  "files": { "TASK.md": "# 修改后的内容\n..." }
}

Response 200:
{
  "code": 0,
  "data": { "version": 2, "files": { "TASK.md": "# 修改后的内容\n..." } }
}

Response 409 (工件已过期):
{
  "code": 2001,
  "message": "工件已过期，请重新渲染后再修订"
}
```

#### 6.2.8 导出

```
GET /api/requirements/{id}/export?target=vibecoding

Response 200 (vibecoding → .md):
Content-Type: text/markdown; charset=utf-8
Content-Disposition: attachment; filename="订单批量导出Excel.md"

Body: file content

Response 200 (openspec → .zip):
Content-Type: application/zip
Content-Disposition: attachment; filename="订单批量导出Excel.zip"

Body: binary zip data
```

#### 6.2.9 需求列表

```
GET /api/requirements?status=SUBMITTED&q=订单&page=1&size=20

Response 200:
{
  "code": 0,
  "data": {
    "items": [
      {
        "id": "...",
        "title": "订单批量导出Excel",
        "status": "SUBMITTED",
        "priority": "HIGH",
        "createdAt": "...",
        "updatedAt": "..."
      }
    ],
    "total": 1,
    "page": 1,
    "size": 20
  }
}
```

#### 6.2.10 附件上传

```
POST /api/requirements/{id}/attachments
Content-Type: multipart/form-data

file: 原型图.png  (白名单: PNG/JPG/PDF/DOCX, ≤20MB)

Response 200:
{
  "code": 0,
  "data": { "id": "attachment-uuid" }
}

Response 400:
{
  "code": 1001,
  "message": "不支持的文件类型: .exe"
}
```

### 6.3 错误码速查表

| code | HTTP | 含义 | 触发场景 |
|------|------|------|----------|
| 0 | 200 | 成功 | 正常响应 |
| 1000 | 400/422 | 参数校验失败 | @Valid 失败、闸门检查不通过 |
| 1001 | 400 | 文件格式不支持 | 附件格式不在白名单 |
| 2000 | 404 | 资源不存在 | 需求/版本/工件 ID 不存在 |
| 2001 | 409 | 业务状态冲突 | DRAFT 下渲染、过期工件修订 |
| 3000 | 503 | Python 引擎不可达 | 引擎挂了或启动中 |
| 5000 | 500 | 系统内部错误 | 未预期异常（兜底） |

---

## 7. 启动与运行

### 7.1 启动顺序

```
1. engine-server (:8090)   ← 唯一后端进程（含 Python 子进程加载模型 30-60s）
2. reqforge_app (Flutter)  ← server 就绪后启动
```

> v1.3 单 JAR 合体后只有一个后端进程——网关已退役，无启动顺序约束。

### 7.2 engine-server 启动

**开发模式（热重载，推荐日常开发）**：

```powershell
# 在 F:\workspace\engine 根目录执行
.\gradlew :engine-server:bootRun
```

启动日志关键行：
```
Started ServerApplication in 12.345 seconds
Py4J 通道就绪 (127.0.0.1:25335)
```

**IDE 启动**：右键 `ServerApplication.java` → Run（IDEA Ultimate 支持 Spring Boot 面板）

**Jar 包启动**：

```powershell
# 从 engine-server/ 目录启动（./python 探测命中脚本目录）
cd engine-server
java -jar build\libs\engine-server.jar

# 或从仓库根显式指定脚本目录
java -jar engine-server\build\libs\engine-server.jar --engine.python.home=engine-server\python
```

**Go 工具箱引擎（gotoolbox，默认关闭）**：

```powershell
# 开启态：降级向量化（Go 哈希向量接管 TEXT 模态）+ 检索并行加速，两级开关缺一不可
java -jar engine-server\build\libs\engine-server.jar --spring.profiles.active=go-toolbox --engine.go.enabled=true

# 只开第一级（engine.go.enabled=true）：仅检索加速（vector.* 副本），向量化仍走 Python
# 任意目录纯 jar 可跑：Go 二进制自动从 jar 内解压到 .\go-runtime\<platform>\ 后执行
# 开发态（未打 jar）：需本机 Go 工具链，launcher 自动走 golang\ 源码的 go run 开发轨
```

开关语义详表（两级门控四行矩阵）见 [gotoolbox-spec](file:///F:/workspace/engine/docs/reqforge-gotoolbox-spec.md) §4。
默认 `engine.go.enabled=false`：零 Bean 零进程零解压，行为与基线一致。

### 7.3 前端热更新开发模式（推荐前端开发使用）

```powershell
# 终端 1：启动合体应用（:8090，提供 API + 静态资源）
.\gradlew :engine-server:bootRun

# 终端 2：启动 Vite dev server（热更新）
cd engine-server\frontend
npm install
npm run dev     # 浏览器打开 http://localhost:5173
                # /api 请求自动代理到 http://127.0.0.1:8090
```

### 7.4 reqforge_app 启动

```powershell
cd F:\workspace\reqforge_app

# 安装依赖
flutter pub get

# 连接设备/模拟器后运行
flutter run

# 或指定设备
flutter run -d emulator-5554   # Android 模拟器
flutter run -d chrome           # Web 调试
```

### 7.5 验证健康

```powershell
# 容器/编排器探测口
curl http://127.0.0.1:8090/actuator/health
# → {"status":"UP"}

# 合体信封（整体 + engine/goToolbox/documents 明细）
curl http://127.0.0.1:8090/api/system/health
# → {status, gateway, server, engine, goToolbox, documents}
#   goToolbox: {enabled:false,status:"N/A"}（默认关闭）或
#             {enabled:true,status:"UP|DOWN",version,vectorCount,tools,lastError}
#   注意：goToolbox DOWN 不拖垮整体 status（加速器语义，检索自动降级本地扫）

# 浏览器打开
# 生产：http://127.0.0.1:8090
# 开发：http://localhost:5173
```

---

## 8. 打包与部署

### 8.1 后端打包（单 JAR 合体）

```powershell
# 在 F:\workspace\engine 根目录执行——一条命令出全部
.\gradlew :engine-server:bootJar
# 前端构建（npmInstall → buildFrontend → copyFrontendDist）、python 脚本打包
# （packagePython）与 Go 双平台交叉编译（buildGoToolbox → packageGolang）
# 被自动拉起，产物全部进 jar：
# 产物：engine-server/build/libs/engine-server.jar
#   ├─ BOOT-INF/classes/static/    ← 前端构建产物
#   ├─ BOOT-INF/classes/python/    ← Python 脚本资产
#   └─ BOOT-INF/classes/golang/    ← Go 双平台二进制（windows-amd64/toolbox.exe
#                                    + linux-amd64/toolbox，CGO_ENABLED=0 静态链接）
```

**本地构建需要 Go 1.21+**（`golang/go.mod` 钉的下限）。本机无 Go 工具链时构建
warn 跳过不炸——但 jar 缺 `golang/` 二进制，开启态须本机 Go 走 `go run` 开发轨；
`-PskipGoBuild` 显式关闭。Go 源码指纹未变时跳过重编（镜像前端 fingerprint 模式）。
零三方依赖：`GOPROXY=off` 也能全量构建（`go list -m all` 只有自身）。

验证 jar 内双平台二进制：
```powershell
# Git Bash
unzip -l engine-server/build/libs/engine-server.jar | grep golang/
```

镜像构建与部署详见 [deployment.md](file:///F:/workspace/engine/docs/deployment.md)。

### 8.2 前端单独构建

```powershell
cd engine-server\frontend
npm run build
# 产物：dist/ 目录

# 类型检查（不构建）
npx tsc --noEmit
```

### 8.3 Flutter APP 打包

```powershell
cd F:\workspace\reqforge_app

# Android debug 包
flutter build apk --debug
# 产物：build/app/outputs/flutter-apk/app-debug.apk

# Android release 包（需签名配置）
flutter build apk --release

# iOS（需 macOS + Xcode）
flutter build ios --no-codesign
```

### 8.4 一键构建脚本

```powershell
# ===== 全量构建脚本 (build-all.ps1) =====
$ErrorActionPreference = "Stop"

Write-Host "=== 1/3 构建 engine-server（单 JAR，前端自动入 jar） ==="
.\gradlew :engine-server:bootJar
if ($LASTEXITCODE -ne 0) { throw "server 构建失败" }

Write-Host "=== 2/3 构建 Flutter APP ==="
cd F:\workspace\reqforge_app
flutter build apk --debug
if ($LASTEXITCODE -ne 0) { throw "APP 构建失败" }
cd F:\workspace\engine

Write-Host "=== 3/3 完成 ==="
Write-Host "后端: engine-server/build/libs/engine-server.jar（含前端产物与 python 脚本）"
Write-Host "APP:  build/app/outputs/flutter-apk/app-debug.apk"
```

---

## 9. 扩展开发指南

### 9.1 常见扩展场景速查

| 我想实现... | 改哪里 | 关键文件 |
|-------------|--------|----------|
| 新增 API 端点 | interfaces → application → domain repo | 见 §3.3 |
| 新增渲染目标 | domain/renderer（新建 + 注册） | SpecRenderer, RenderTarget, ApplicationService |
| 新增表单字段 | domain model + DTO + 前端 form | RequirementForm, RequirementDto, formFragments.tsx |
| 改闸门校验规则 | domain/service | RequirementValidator.java |
| 新增前端页面 | pages/ + App.tsx 路由 | 见 §4.2 |
| 新增前端 API 调用 | api/ 目录 | requirements.ts 或新文件 |
| 新增 APP 页面 | pages/ + main.dart 路由 | 见 §5.3 |
| 切换 APP 环境地址 | config/ | api_config.dart |
| 新增数据库表 | infrastructure/persistence | DatabaseMigrator.java |
| 改渲染模板内容 | domain/renderer | VibecodingRenderer / OpenSpecRenderer |

### 9.2 完整开发流程（从想法到上线）

```
1. 阅读本文档明确改动点
      ↓
2. 按 DDD 分层顺序编码：
   domain（模型 + 端口）→ infrastructure（实现）→ application（编排）→ interfaces（暴露）
      ↓
3. 写测试（先写负面用例再写正面）：
   domain 单测 → application 假端口测试 → REST 集成测试
      ↓
4. 本地验证：
   .\gradlew :engine-server:bootRun → 浏览器操作一遍
      ↓
5. 前端适配：
   api/types.ts 同步类型 → api/ 加方法 → pages/ 加 UI
      ↓
6. APP 适配（如需要）：
   models/ 同步 → services/ 加方法 → pages/ 加 UI
      ↓
7. 全量构建验证：
   .\gradlew :engine-server:bootJar   ← 单 JAR（前端构建自动拉起）
   flutter build apk --debug
      ↓
8. 提交代码 + 运行全量测试
```

### 9.3 对新模块的架构约束

如果你要新增一个完全独立的业务模块（如"缺陷管理"、"任务跟踪"），请遵循：

1. **domain/model/ 下新建包**：如 `domain/model/defect/`
2. **domain/repository/ 新建端口**：如 `DefectRepository`
3. **infrastructure/persistence/ 新建实现**：如 `SqliteDefectRepository`
4. **application/ 新建 Service**：如 `DefectApplicationService`
5. **interfaces/rest/ 新建 Controller**：如 `DefectController`
6. **DatabaseMigrator 新增迁移**：建表语句
7. **前端 pages/ 下新建目录**：如 `pages/defects/`
8. **前端 api/ 下新建文件**：如 `api/defects.ts`

**禁止**：把新模块代码塞进已有的 RequirementXxx 类中——保持模块内聚，边界清晰。

---

## 10. 常见开发问题

### 10.1 后端

**Q: 启动报 `Lucene 索引初始化失败`**

A: 删除 `engine-server/lucene-index/write.lock` 后重启（上次异常退出残留）。

**Q: 启动报 `Py4J 通道未就绪`**

A: 检查 Python 环境：`python -c "import torch; print(torch.__version__)"`。确保依赖已安装：
```powershell
pip install -r engine-server/python/requirements.txt -i https://mirrors.aliyun.com/pypi/simple/
```

**Q: 改了 Java 代码没生效**

A: bootRun 模式下需要重启。IDE 用 DevTools 可部分热重载，但新增类/方法签名变更必须重启。

**Q: 怎么调试后端代码**

A: IDE 打断点，以 Debug 模式启动 `ServerApplication`；或远程调试：
```powershell
java -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005 -jar ...
```

**Q: 构建时提示 `No Go toolchain on this machine`**

A: 本机无 Go 工具链——构建 warn 跳过不炸，但 jar 缺 `golang/` 二进制。装 Go 1.21+
（`go version` 验证）后重跑；或确认确实不需要（保持 `engine.go.enabled=false`
默认关闭态，jar 缺二进制无影响）。显式关闭：`.\gradlew :engine-server:bootJar -PskipGoBuild`。

**Q: 开启态健康段 `goToolbox.status=DOWN`**

A: 按序排查：①看 `lastError` 字段（探活失败原因直接可读）；②启动日志搜
`[go-err]`（Go 进程 stderr 桥接）与 `Go toolbox engine ready`；③ Go 引擎挂了
**服务依然可用**（检索自动降级本地扫、摄取降级哈希），DOWN 是性能退化提示不是故障；
④重启应用即恢复（v1 不自动重启子进程）。

**Q: Windows 防病毒拖慢/拦截 Go 子进程**

A: 首次启动时 Defender 实时扫描刚解压的 `go-runtime\windows-amd64\toolbox.exe`
可能拖慢启动数秒——重复启动无此现象（大小比对免重解压）；若被误报隔离，把工程
目录加入白名单后重启应用（解压轨会自动补回二进制）。

**Q: 离线/弱网环境能构建吗**

A: 能。Go 工程零三方依赖（`go list -m all` 只有自身），`GOPROXY=off` 下
`buildGoToolbox` 照常编译；前端/python 环节不受影响（npm 源与 pip 源见上文）。

### 10.2 前端

**Q: `npm run dev` 报 proxy error**

A: 确保 engine-server 已启动（`.\gradlew :engine-server:bootRun`，:8090）。Vite dev server 的 `/api` 代理依赖它。

**Q: 改完代码页面没更新**

A: Vite HMR 应自动更新。如果不生效，`Ctrl+Shift+R` 硬刷新，或重启 `npm run dev`。

**Q: 安装了新 npm 包后类型报错**

A: 运行 `npm install` 确保依赖完整。某些包需额外安装 `@types/xxx`。

**Q: 构建报 TypeScript 错误**

A: `npx tsc --noEmit` 检查所有类型错误。务必在提交前修复——构建不允许类型错误。

### 10.3 APP

**Q: 连接不上后端 API**

A: 检查 `lib/config/api_config.dart` 的 `baseUrl`：
- 模拟器用 `http://10.0.2.2:8090/api`
- 真机同 WiFi 用电脑 IP，如 `http://192.168.1.100:8090/api`
- 确保 engine-server 已启动且 :8090 端口可访问

**Q: `flutter run` 找不到设备**

A: `flutter devices` 查看已连接设备。Android 确保 USB 调试已开或模拟器已启动。

**Q: 构建 APK 报错**

A: `flutter clean` → `flutter pub get` → 重新构建。确保 Android SDK 和 Gradle 版本兼容。

---

## 附录 A：文档索引

| 文档 | 面向角色 | 路径 |
|------|----------|------|
| 使用手册 | 运维/部署/运营 | [reqforge-user-guide.md](file:///F:/workspace/reqforge_app/docs/reqforge-user-guide.md) |
| 开发手册（本文） | 后端/前端/APP 开发 | [reqforge-dev-guide.md](file:///F:/workspace/reqforge_app/docs/reqforge-dev-guide.md) |
| 测试手册 | 测试/开发 | [reqforge-test-guide.md](file:///F:/workspace/reqforge_app/docs/reqforge-test-guide.md) |
| 产品手册 | 产品经理/业务 | [reqforge-product-guide.md](file:///F:/workspace/reqforge_app/docs/reqforge-product-guide.md) |
| 架构总览 | 全体开发 | [architecture.md](file:///F:/workspace/engine/docs/architecture.md) |
| 后端代码规范 | 后端开发 | [backend-standards.md](file:///F:/workspace/engine/docs/backend-standards.md) |
| 前端代码规范 | 前端开发 | [frontend-standards.md](file:///F:/workspace/engine/docs/frontend-standards.md) |
| 部署手册 | 运维/架构 | [deployment.md](file:///F:/workspace/engine/docs/deployment.md) |

## 附录 B：快速命令速查

```powershell
# ===== 后端（单 JAR 合体） =====
.\gradlew :engine-server:bootRun          # 启动合体应用（开发，:8090）
.\gradlew :engine-server:bootJar          # 打包单 JAR（前端构建自动拉起）
.\gradlew :engine-server:test             # 运行全部测试
.\gradlew :engine-server:test --tests "*RequirementControllerTest"  # 单个测试

# ===== 前端 =====
cd engine-server\frontend
npm install                               # 安装依赖
npm run dev                               # 启动 Vite 热更新（/api → :8090）
npm run build                             # 生产构建
npx tsc --noEmit                          # 类型检查

# ===== APP =====
cd F:\workspace\reqforge_app
flutter pub get                           # 安装依赖
flutter run                               # 启动 APP
flutter build apk --debug                 # 打包 Android
flutter analyze                           # 静态分析

# ===== 验证 =====
curl http://127.0.0.1:8090/actuator/health     # 容器探测口
curl http://127.0.0.1:8090/api/system/health   # 合体信封（整体 + engine/documents）
```