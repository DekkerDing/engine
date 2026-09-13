# reqforge — 测试手册

> 面向：测试工程师、开发工程师 | 版本：1.0 | 日期：2026-09-13

---

## 1. 测试架构与运行

```
集成测试 (MockMvc + 真实 SQLite) → REST 全链路
应用层测试 (Mock 仓储)           → 用例编排
领域层测试 (纯函数，零替身)       → 闸门/渲染器
```

### 运行命令

```powershell
# 全部测试
.\gradlew :engine-server:test

# 单个测试类
.\gradlew :engine-server:test --tests "*RequirementControllerTest"

# 单个方法
.\gradlew :engine-server:test --tests "*关键路径*"

# 跳过 Python 集成测试
.\gradlew :engine-server:test -x channelIT

# 前端类型检查
cd engine-gateway\frontend && npx tsc --noEmit

# APP 静态分析
cd F:\workspace\reqforge_app && flutter analyze
```

### 已有测试文件

| 测试类 | 位置 |
|--------|------|
| `RequirementControllerTest` | [interfaces/rest/RequirementControllerTest.java](file:///F:/workspace/engine/engine-server/src/test/java/io/github/dekkerding/engine/interfaces/rest/RequirementControllerTest.java) |
| `RequirementApplicationServiceTest` | [application/RequirementApplicationServiceTest.java](file:///F:/workspace/engine/engine-server/src/test/java/io/github/dekkerding/engine/application/RequirementApplicationServiceTest.java) |
| `RequirementValidatorTest` | [domain/service/RequirementValidatorTest.java](file:///F:/workspace/engine/engine-server/src/test/java/io/github/dekkerding/engine/domain/service/RequirementValidatorTest.java) |
| `RendererDeterminismTest` | [domain/service/renderer/RendererDeterminismTest.java](file:///F:/workspace/engine/engine-server/src/test/java/io/github/dekkerding/engine/domain/service/renderer/RendererDeterminismTest.java) |
| `RequirementTestFixtures` | [domain/service/RequirementTestFixtures.java](file:///F:/workspace/engine/engine-server/src/test/java/io/github/dekkerding/engine/domain/service/RequirementTestFixtures.java) |

---

## 2. 后端 API 测试点

### 2.1 创建 (POST /api/requirements)

| 测试点 | 输入 | HTTP | 关键断言 |
|--------|------|------|----------|
| 创建含标题 | `{"basic":{"title":"测试"}}` | 200 | `data.id` UUID 36 位 |
| 空 body | 无 | 400 | code != 0 |
| 创建后列表可见 | GET list | 200 | items 包含新创建 |

### 2.2 提交闸门 (POST /{id}/submit)

| 测试点 | 输入 | HTTP | 关键断言 |
|--------|------|------|----------|
| 缺验收 | features=[] | 422 | code=1000, msg 含"验收标准至少一条" |
| 缺标题 | title="" | 422 | msg 含"标题" |
| 三要素不齐 | so="" | 422 | msg 含"第 X 条用户故事" |
| WHEN/THEN 不完整 | then="" | 422 | msg 含"第 X 条验收标准" |
| 全部通过 | 完整表单 | 200 | status=SUBMITTED |
| DRAFT 渲染 | DRAFT 调 render | 409 | "尚未提交" |

### 2.3 编辑 (PUT /{id})

| 测试点 | 预期 | 断言 |
|--------|------|------|
| 编辑保存 | 200 | status 回到 DRAFT |
| 编辑后工件过期 | stale=true | 所有工件 stale |
| 空 body | 400 | bad request |

### 2.4 渲染 (POST /{id}/render)

| 测试点 | 输入 | HTTP | 断言 |
|--------|------|------|------|
| openspec | target=openspec | 200 | version=1, files 含 .openspec.yaml |
| vibecoding | target=vibecoding | 200 | version=1, files 含 TASK.md |
| 再次渲染 | 同 target | 200 | version=2 |
| 无效 target | target=word | 400 | bad request |

### 2.5 工件内容 (GET /{id}/artifacts/{version})

| 测试点 | 断言 |
|--------|------|
| TASK.md 含标题 | `files["TASK.md"]` contains 表单标题 |
| 七章节齐全 | 含"任务目标"/"业务背景"/"验收标准" |
| 不存在的版本 | 404 |

### 2.6 修订 (PUT /{id}/artifacts/{version})

| 测试点 | 断言 |
|--------|------|
| 修订内容 | files={"TASK.md":"# 修订版"} → 200，内容变成修订版 |
| 修订后导出 | 导出内容 = 修订版 |
| 过期工件修订 | 409，"已过期" |
| 空内容 | 400，"不能为空" |

### 2.7 导出 (GET /{id}/export)

| 测试点 | 断言 |
|--------|------|
| vibecoding→.md | Content-Type: text/markdown |
| openspec→.zip | Content-Type: application/zip, body > 100B |
| stale 头 | X-Artifact-Stale 正确 |
| 未渲染导出 | 409 |

### 2.8 附件 (POST /{id}/attachments)

| 测试点 | 断言 |
|--------|------|
| PNG 上传 | 200 |
| 白名单外 (.exe) | 400 |
| 空文件 | 400 |

### 2.9 列表查询

| 参数 | 断言 |
|------|------|
| ?status=EXPORTED | 全部 status=EXPORTED |
| ?q=订单 | 标题含"订单" |
| 无数据 | items=[], total=0（不抛异常） |

---

## 3. 前端测试点

### 3.1 需求列表页

| 测试点 | 预期 |
|--------|------|
| 正常加载 | Table 渲染 |
| 空状态 | "暂无需求" |
| 筛选 | 状态/优先级过滤正确 |
| 搜索 | 关键字过滤 |
| 分页 | >20 条显示分页 |
| 跳转 | 标题→工坊，按钮→新建 |

### 3.2 三步表单页

| 测试点 | 预期 |
|--------|------|
| 步骤前进/后退 | Steps 切换 + 校验 |
| 必填校验 | 空标题→提示 |
| 暂存 | 保存为 DRAFT |
| 编辑回填 | URL 带 id 时数据回填 |
| 提交成功 | Result 页 |
| 提交失败 | 闸门缺失清单提示 |

### 3.3 工坊页

| 测试点 | 预期 |
|--------|------|
| 详情加载 | 左栏摘要+表单 |
| 渲染 | 工件列表更新 |
| 预览 | Modal + Tabs |
| 修订 | 编辑→保存→version 递增 |

### 3.4 导出页

| 测试点 | 预期 |
|--------|------|
| 预览 | 最新工件内容 |
| 切换目标 | 内容切换 |
| 下载 | 浏览器触发下载 |

---

## 4. 状态机断言速查

```
DRAFT ──[提交闸门]──▶ SUBMITTED ──[导出]──▶ EXPORTED
  ▲                        │                    │
  └────[编辑保存]──────────┘                    │
  └─────────────────────────────────────────────┘
```

| 操作 | 前状态 | 后状态 | HTTP |
|------|--------|--------|------|
| 创建 | — | DRAFT | 200 |
| 编辑 | 任意 | DRAFT | 200 |
| 提交 | DRAFT | SUBMITTED | 200 |
| 渲染 | SUBMITTED+ | 不变 | 200 |
| 导出 | SUBMITTED+ | EXPORTED | 200 |
| 提交(不完整) | DRAFT | — | 422 |
| 渲染 | DRAFT | — | 409 |
| 修订(过期) | — | — | 409 |

---

## 5. API 测试模板

### 全链路用例

```
1. POST /requirements (仅标题) → id
2. PUT /requirements/{id} (补齐) → 200
3. POST /requirements/{id}/submit → 200
4. POST /requirements/{id}/render {target:vibecoding} → v1
5. POST /requirements/{id}/render {target:openspec} → v1
6. GET /requirements/{id}/artifacts/1?target=vibecoding → TASK.md 正确
7. PUT /requirements/{id}/artifacts/1 (修订) → 200
8. GET /requirements/{id}/export?target=vibecoding → 导出=修订版
```

### 测试数据工厂

```java
RequirementForm form = RequirementTestFixtures.fullForm();
// 包含 2 条 features + 2 条 acceptance + 完整 background
```

### 常用断言代码

```java
// HTTP 状态
.andExpect(status().isOk())
.andExpect(status().is(422))

// JSON 字段
JsonNode body = json.readTree(utf8Body(result));
assertEquals(0, body.get("code").intValue());
assertTrue(body.get("data").get("files").get("TASK.md").asText().contains("订单导出"));

// 响应头
assertEquals("application/zip", result.getResponse().getContentType());
```