# 需求工厂（requirement-forge）技术设计

## Context

engine 仓库现有：engine-server（Spring Boot :8081，DDD 四层，SQLite + DatabaseMigrator 逐版迁移）、engine-gateway（:8090 哑反代 + React18/AntD5/Router6 前端，AppLayout 全站 Grid 坐标系）、openspec spec-driven 工作流。本设计新增「需求工厂」领域，不动现有文档/图片检索域任何代码。

已确认的四项方向决策（见 proposal）：engine 仓库内新增领域；双格式渲染（openspec + vibecoding）；Web 与 Flutter 双端并行；渲染纯模板确定性、零 LLM。

## Goals / Non-Goals

**Goals**：表单 → IR → 双目标 md 的确定性流水线；API 契约先行冻结以支撑双端并行；模板即代码（版本化、可单测、字节级可复现）；Flutter 端演示数据模式先行。

**Non-Goals**（设计层面）：不做表单引擎的动态 schema 执行（首版表单结构编译期固定，`form_template` 表仅为后续演进预留）；不做 md diff 的算法展示库（以逐行对比的简单呈现起步）；不做模板热加载。

## Decisions

### D1 领域落位与模块结构

`engine-server` 新增 `requirement` 包，严格四层：

```
domain/
  model/        RequirementSubmission(聚合根) MdArtifact Attachment
                RequirementIR(值对象) RequirementStatus(DRAFT/SUBMITTED/EXPORTED)
  service/      RequirementValidator(完整性闸门,纯函数)
                renderer/ SpecRenderer(端口接口)
                renderer/OpenSpecRenderer VibecodingRenderer(纯 JVM 实现)
  repository/   RequirementRepository MdArtifactRepository AttachmentRepository(端口)
application/    RequirementApplicationService(用例编排+状态机推进)
infrastructure/ SqliteRequirementRepository SqliteMdArtifactRepository
                SqliteAttachmentRepository LocalFileAttachmentStore(data/requirements/)
interfaces/     RequirementController(@Valid + ApiResponse 信封 + 错误码)
```

渲染器放 `domain.service`：纯文本变换、无框架依赖、同输入同输出——满足 domain 纯度纪律。备选「放 infrastructure」被否：渲染是业务规则不是技术细节。

### D2 数据模型（SQLite 迁移新增 4 表，零存量改动）

```sql
form_template(id, name, version, schema_json, is_active)          -- 首版内置一条固定记录
requirement_submission(id, title, capability_slug, form_data_json,
                       status, created_at, updated_at,
                       submitted_at, exported_at)
md_artifact(id, requirement_id, target, version, files_json,
            template_output_json, revised_json, is_stale,
            template_version, created_at)
attachment(id, requirement_id, file_name, stored_path,
           size_bytes, content_type, created_at)
```

关键点：
- `form_data_json` 存原始表单数据，IR 由其即时派生（不落库，派生逻辑纯函数可测）
- `md_artifact` 一个版本 = 一个 target 的多文件产物：`files_json` 存 `{相对路径: 内容}` 映射（openspec 包多文件、vibecoding 单文件 `TASK.md`，结构统一，zip 导出直接遍历）
- **模板原始输出与人工修订分离**：`template_output_json` 不可变（确定性断言的基准），`revised_json` 可空（人工编辑）；diff = 两者比对；导出取 `revised ?? template_output`
- `capability_slug`：OpenSpec 包 `specs/<capability>/spec.md` 的目录名。表单第一步可选填写，缺省 `req-{id}`（中文标题无法 slug 化，不做拼音转换）

### D3 RequirementIR 结构（编译流水线的字节码）

```json
{
  "meta":     { "id": 1, "title": "…", "submitter": "…", "department": "…",
                "priority": "HIGH|MEDIUM|LOW", "expectDate": "2026-10-01",
                "capabilitySlug": "req-1" },
  "background": { "painPoints": ["…"], "impactScope": "…", "metrics": ["…(可选)"] },
  "features": [ { "userStory": { "as": "…", "want": "…", "so": "…" },
                  "details": "…" } ],
  "acceptance": [ { "when": "…", "then": "…" } ],
  "constraints": { "technical": ["…"], "compliance": ["…"] },
  "attachments": [ { "name": "原型.png", "path": "attachments/…", "type": "image/png" } ]
}
```

闸门规则（`RequirementValidator`，纯函数返回缺失清单）：`acceptance ≥ 1`、`features ≥ 1`、每条 userStory 三要素齐备。

### D4 渲染器：模板即代码

模板以 Java text block 常量内嵌于渲染器类，`TEMPLATE_VERSION` 常量随实现演进递增并写入 `md_artifact.template_version`。

- 备选 A「Handlebars/Mustache 依赖」：模板结构固定、无循环外逻辑需求，引依赖收益为负 → 否
- 备选 B「classpath .tpl 资源文件」：模板变更绕过代码评审、加载是 IO 破坏纯函数性 → 否
- 结论：模板变更 = 代码变更 = 走评审与测试，确定性由 `assertEquals(常量字符串, render(ir))` 直接断言

IR → 两种目标的字段映射规约（渲染器实现的唯一依据）：

| IR 字段 | openspec 产物 | vibecoding 产物 |
|---------|--------------|----------------|
| `background.painPoints + impactScope` | `proposal.md` Why | 业务背景 |
| `features`（含 userStory） | What Changes + `specs/<slug>/spec.md` 的 Requirements | 需求详述 |
| `acceptance`（when/then） | spec.md 的 `#### Scenario` WHEN/THEN | 验收标准（保留 WHEN/THEN） |
| `constraints` | proposal Impact（technical）+ 实施注意 | 约束与边界 + 实施注意事项 |
| `features × {实现,测试,验收}` 派生 | `tasks.md` 勾选清单 | 并入需求详述尾部的任务清单 |
| `attachments` | proposal Impact 附件引用清单 | 附件引用清单 |
| `meta.title` | 变更标题 | 任务目标 |

### D5 状态机与过期联动

```
DRAFT ──submit(闸门通过)──▶ SUBMITTED ──export──▶ EXPORTED
  ▲                            │
  └────── 保存编辑(任何状态) ────┘   编辑保存 ⇒ 状态回 DRAFT
                                     同时 md_artifact.is_stale = true(全部工件)
```

过期联动在 `RequirementApplicationService` 编排（聚合内一致性），避免应用层遗漏。

### D6 API 契约（设计阶段冻结，双端并行的前提）

```
POST   /api/requirements                                创建草稿(可部分字段)
GET    /api/requirements?page&size&status&q&priority    分页列表
GET    /api/requirements/{id}                           详情(含 IR 摘要+工件索引)
PUT    /api/requirements/{id}                           保存表单(编辑回退+过期联动)
POST   /api/requirements/{id}/submit                    提交(闸门,失败返缺失清单 422)
POST   /api/requirements/{id}/render                    {target} 渲染,产新版本
GET    /api/requirements/{id}/artifacts                 版本列表(含 stale 标记)
GET    /api/requirements/{id}/artifacts/{version}?target 内容(template/revised/diff)
PUT    /api/requirements/{id}/artifacts/{version}       保存人工修订
GET    /api/requirements/{id}/export?target             zip(openspec)/md(vibecoding)
POST   /api/requirements/{id}/attachments               multipart 上传(白名单+20MB)
GET    /api/requirements/{id}/attachments/{file}        附件下载
```

错误码沿用 `EngineException` + `GlobalExceptionHandler` 模式：`REQ_GATEWAY_INCOMPLETE`(422 闸门)、`REQ_NOT_FOUND`(404)、`REQ_INVALID_STATE`(409 非法状态迁移)。ApiResponse 信封不变，gateway 零改动（哑反代透传）。

### D7 Web 端页面架构（复用 AppLayout，商业范式映射）

| 路由 | 页面 | 范式参照 | 要点 |
|------|------|---------|------|
| `/requirements` | RequirementListPage | 美团商家后台 | 状态筛选 chips + AntD Table + StatusBadge 徽标 + 标题搜索 + 分页 |
| `/requirements/new` | RequirementFormPage | 电商结账流 | AntD Steps 三步、每步暂存、校验错误字段定位、上一步/下一步 |
| `/requirements/:id` | RequirementWorkshopPage | 淘宝详情编辑双栏 | 左：表单回填可编辑；右：md 工件实时预览 + 目标/版本切换 + 修订编辑（Monaco 首版用 textarea+等宽排版替代，避免新依赖） |
| `/requirements/:id/export` | RequirementExportPage | — | 产物文件树预览 + 过期警示 + zip/md 下载 |

侧边栏新增「需求工厂」分组（AppLayout 两项不动铁律不破：新页面只进 main）。API 客户端按现有 `api/` 目录惯例新增 `api/requirements.ts`。

### D8 Flutter 工程结构（`F:\workspace\reqforge_app`，仓库外）

沿 `flutter-photo-client` 边界例外先例：变更文档归 engine 仓库，实施产物落仓库外，engine Gradle 构建不感知。

```
lib/
  core/     api/(按冻结契约的 dio 客户端) repo/(RequirementRepository 接口
            + MockRequirementRepository + ApiRequirementRepository)
            config/ models/(IR/工件/列表 DTO) theme/
  features/ list/ submit/ workshop/ memory/(占位) profile/
```

技术选型同 photo 客户端：`dio` + `provider` + `shared_preferences`。演示模式 Mock 数据确定性生成（固定清单），显式横幅纪律对齐 Web 端 DegradedBanner 惯例。

### D9 附件存储

`data/requirements/{id}/attachments/{uuid}.{ext}`，删除需求联动清理（沿 `data/documents/` 现行纪律）。格式白名单 png/jpg/jpeg/pdf/docx + 魔数校验 + 20MB 上限（沿 ImageFormatGuard 模式实现 `AttachmentFormatGuard`）。

## Risks / Trade-offs

- [中文内容进入 openspec 结构的合法性] → spec.md 的 Requirement/Scenario 名含中文，openspec CLI 对正文 UTF-8 无约束；capability_slug 强制英文 kebab-case 规避路径问题。实施首日用真实产物跑一次 `openspec validate` 验证，不合则渲染器加标题英化规则
- [双端并行契约漂移] → 契约在本设计冻结（D6）；Flutter Mock 以本文档为唯一依据；后端实现合入前跑契约一致性检查（Web 与 Flutter 共用同一份 OpenAPI 风格描述或以本节为准）
- [模板即代码导致改版需发版] → 接受：模板变更本就该走评审；TEMPLATE_VERSION 保证历史工件可追溯
- [人工修订与模板输出合并冲突] → 首版策略：修订整体覆盖，不提供三方合并；工件过期后重新渲染生成新版本，修订历史不丢失（按版本留存）
- [Flutter 工程名/落位未最终确认] → 实施前用户可改名，不影响契约与 engine 侧任何设计

## Migration Plan

1. SQLite 迁移新增（DatabaseMigrator 新版本号，仅 CREATE TABLE 四张 + 内置 form_template 种子行），启动时自动执行，不动存量表——回滚 = 删新表，无数据损失风险
2. 后端域合入 → Web 页面合入（同仓两 PR 或一体）→ Flutter Phase C 接入时只需后端已部署
3. 导出的 openspec 变更包由工程师放入目标仓库消费——本仓库自身不自动写入 `openspec/changes/`（避免自动变更绕过人工评审纪律）

## Open Questions

- vibecoding 产物是否需要英文章节标题版本（当前设计：中文章节 + 英文结构词 WHEN/THEN）
- `form_template.schema_json` 首版内置后是否有第二张表单的真实场景（若无，D2 的 form_template 表可降级为代码常量——留待 Phase 2 复评）
