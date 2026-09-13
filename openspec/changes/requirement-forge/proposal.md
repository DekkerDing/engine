# 需求工厂（requirement-forge）：业务表单 → Agent 可消费的规范 .md

## Why

VibeCoding 工作流（Claude Code / Codex）的代码生成质量高度依赖输入 .md 工件的规范性，但掌握 md 规则范式的只有工程师——业务人员与产品经理的诉求要么口口相传、要么写成自由文本，转译损耗与格式噪声直接降低模型生成准确性。需要一个「需求编译器」：业务侧只填表单，工程侧拿到的是可直接被 AI 编码工作流消费的高度规范化 .md 工件包，把「格式合规」这件事从人身上彻底拿走。

## What Changes

- **新增「需求工厂」领域**（engine-server 内新 bounded context，端口/分层纪律照搬现有 DDD 四层）：
  - **表单模板 + 分步提交**：结构化需求表单（基本信息 → 业务背景 → 期望功能 → 验收标准 → 约束与附件），@Valid 校验 + 分步暂存
  - **RequirementIR（中间表示）**：表单数据不直接渲染 md，先升维为结构化 IR（为什么/是什么/怎么验收/约束/影响），IR 层做完整性闸门（如：无验收标准不允许导出）
  - **双目标确定性渲染引擎**：同一 IR 渲染两种产物——① OpenSpec 兼容变更包（proposal.md + specs/*/spec.md + tasks.md，可直接被 `openspec` CLI 与 /opsx 工作流消费）② 自定义 VibeCoding md 范式（AGENTS.md 风格，面向 Codex 等 Agent）。纯模板引擎，同输入必同输出，零 LLM 依赖（LLM 润色为后期独立变更）
  - **工件管理**：md 预览、编辑、版本化、zip 导出
- **Web 端页面体系**（engine-gateway/frontend，沿用 AppLayout 骨架，商业范式映射）：需求列表（类美团商家后台）、分步表单（类电商结账流）、MD 工坊双栏实时预览（类淘宝详情编辑）、导出页
- **Flutter APP 端**（新工程 `F:\workspace\reqforge_app`，仓库外——沿 `flutter-photo-client` 确立的边界例外先例）：底部五槽导航（列表 / ＋提交 / 工坊 / 记忆 / 我的），演示数据模式先行、后切真实 API
- **不做（本变更 Non-goals）**：Agent 任务拆分规划（Phase 2 独立变更）、数据库记忆与向量召回（Phase 3 独立变更，届时复用现有 EmbeddingProvider 端口新增 requirement 向量空间）、LLM 增强、账号体系

## Capabilities

### New Capabilities

- `requirement-intake`: 需求收集——表单模板定义、分步填写与暂存、字段校验、RequirementIR 构建与完整性闸门、需求列表与状态管理
- `spec-rendering`: 规范渲染——多目标 md 渲染引擎（OpenSpec 变更包 / VibeCoding 范式）、确定性模板渲染、md 预览与编辑、工件版本化与 zip 导出
- `requirement-app-client`: Flutter 移动客户端——底部导航信息架构、需求浏览 / 表单提交 / md 工坊预览、演示数据模式与真实 API 切换

### Modified Capabilities

（无——gateway 作为哑反代零改动；现有文档/图片检索域零改动；SQLite 新增表不动存量表结构）

## Impact

- **代码**：
  - `engine-server`：新增 `requirement` 域（interfaces/application/domain/infrastructure 四层）+ SQLite 迁移新增 `form_template` / `requirement_submission` / `md_artifact` 表
  - `engine-gateway/frontend`：侧边栏新增「需求工厂」分组 + `pages/requirements/` 四个页面，路由 `/requirements/*`
  - 新工程 `F:\workspace\reqforge_app`（Flutter，仓库外；变更文档与验收归 engine 仓库）
- **API**（经 gateway 既有 `/api` 反代，无网关代码改动）：`POST/GET /api/requirements`、`GET /api/requirements/{id}`、`PUT /api/requirements/{id}`、`POST /api/requirements/{id}/render`、`GET /api/requirements/{id}/artifacts`、`GET /api/requirements/{id}/export`（zip）
- **依赖**：后端渲染引擎自研（Java 文本模板，零新外部依赖）；前端零新依赖（AntD5 现有组件足够）；Flutter 侧 `dio` / `provider` / `shared_preferences`（与 photo 客户端同选型）
- **分期**：Phase A（后端域 + Web 端，双渲染器全量）→ Phase B（Flutter 演示模式，可与 A 并行启动）→ Phase C（Flutter 接真实 API）——B 依赖 API 契约冻结，不依赖后端实施完成

## Non-goals（明确不做）

- Agent 角色注册表与任务拆分（Phase 2 独立变更 `agent-planning`）
- 需求记忆持久化、术语表、相似需求向量召回（Phase 3 独立变更 `requirement-memory`）
- LLM 参与生成或润色（后期可选增强层，须带显式开关与标志）
- 账号、多用户、鉴权、审批流
- 表单模板可视化设计器（首版表单结构固定，模板化管理后期演进）
- 深度品牌视觉定制（Web 沿用 AntD 体系；APP 用 Material 3 + 单一品牌主色）

## Assumptions

- 双端并行采用「契约先行」：API 契约在本变更 design 阶段冻结，Flutter 演示模式按契约 Mock，Phase C 才接真实后端
- 生成的 OpenSpec 变更包按仓库现行 `spec-driven` schema 组织，导出后由工程师放入目标仓库 `openspec/changes/` 消费
- Flutter 工程名 `reqforge_app` 与落位 `F:\workspace\` 为提案默认值，实施前可经用户确认调整
- md 渲染确定性纪律：同一 IR + 同一模板版本 ⇒ 字节级相同的输出（可单测断言）
