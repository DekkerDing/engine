# 需求工厂（requirement-forge）实施任务

> 依赖顺序：1 → 2 → 3 → 4/5（4 与 5 可并行）→ 6。Flutter 5.1-5.6 仅依赖冻结契约（design D6），不依赖后端实施完成。

## 1. 后端领域模型与迁移

- [x] 1.1 DatabaseMigrator 新增迁移版本：CREATE TABLE `form_template`/`requirement_submission`/`md_artifact`/`attachment` + 内置 form_template 种子行；验证：启动 server 后 SQLite 中四表存在且种子行可查
- [x] 1.2 实现 domain model：`RequirementSubmission`（聚合根）、`MdArtifact`、`Attachment`、`RequirementStatus`（DRAFT/SUBMITTED/EXPORTED）、`RequirementIR` 值对象（meta/background/features/acceptance/constraints/attachments 结构见 design D3）；验证：值对象构造与 JSON 派生单测通过
- [x] 1.3 实现 `RequirementValidator` 完整性闸门纯函数（acceptance≥1、features≥1、userStory 三要素）；验证：单测覆盖通过/各缺失项清单场景

## 2. 渲染引擎（纯函数，零 IO）

- [x] 2.1 实现 `SpecRenderer` 端口 + `OpenSpecRenderer`（模板 text block 常量 + TEMPLATE_VERSION，按 design D4 映射表产出 proposal.md/tasks.md/specs/<slug>/spec.md 的 files 映射）；验证：单测断言固定 IR 渲染输出与预期字符串字节级相同
- [x] 2.2 实现 `VibecodingRenderer`（单一 TASK.md，固定七章节，验收保留 WHEN/THEN）；验证：同上字节级断言 + 章节完整性单测
- [x] 2.3 用 2.1 真实产物在临时目录组装变更包并执行 `openspec validate`/`openspec list`；验证：被识别为合法变更（消解 design 风险第一条）

## 3. 后端仓储、用例与 REST

- [x] 3.1 实现 `SqliteRequirementRepository`/`SqliteMdArtifactRepository`/`SqliteAttachmentRepository` + `LocalFileAttachmentStore`（data/requirements/{id}/attachments/）+ `AttachmentFormatGuard`（png/jpg/jpeg/pdf/docx 白名单 + 魔数 + 20MB）；验证：仓储集成测试（内存/临时 SQLite）通过，非法格式被拒
- [x] 3.2 实现 `RequirementApplicationService`：状态机推进（submit 闸门/export）、编辑保存回退 DRAFT + 全工件 is_stale=true 联动、渲染版本号单调递增、人工修订保存（template_output 不可变）；验证：状态迁移与过期联动单测通过
- [x] 3.3 实现 `RequirementController` 全部端点（design D6 表）+ 错误码 REQ_GATEWAY_INCOMPLETE(422)/REQ_NOT_FOUND(404)/REQ_INVALID_STATE(409)；验证：MockMvc 测关键路径——创建→提交缺验收标准返 422 清单→补齐提交→双目标渲染→保存修订→导出

## 4. Web 前端（engine-gateway/frontend）

- [ ] 4.1 新增 `api/requirements.ts` + types（按冻结契约）；验证：`npm run build`（tsc --noEmit + vite build）通过
- [ ] 4.2 AppLayout 侧边栏新增「需求工厂」分组 + App.tsx 注册四路由；验证：页面切换顶栏/侧边不动、无布局跳动（frontend-standards 铁律）
- [ ] 4.3 `RequirementListPage`（类美团商家后台：状态筛选 chips + Table + StatusBadge + 标题搜索 + 分页，筛选翻页保持）；验证：对 MockMvc 后端手工过列表/筛选/搜索行为
- [ ] 4.4 `RequirementFormPage`（类电商结账流：Steps 三步、每步暂存、字段级校验定位、上一步/下一步、附件上传白名单提示）；验证：断网刷新后草稿恢复、空标题被拦且定位到字段
- [ ] 4.5 `RequirementWorkshopPage`（类淘宝双栏：左表单回填可编辑、右 md 实时预览、openspec/vibecoding 目标切换、版本切换、修订编辑保存、与模板输出逐行 diff、过期徽标）；验证：目标/版本切换内容正确、保存修订后 diff 与导出内容一致
- [ ] 4.6 `RequirementExportPage`（产物文件树预览、过期警示确认、zip/md 下载）；验证：zip 解压目录结构与 files 映射一致且含附件
- [ ] 4.7 全流程冒烟：表单提交→闸门拦截→补齐→渲染双目标→修订→导出；验证：`npm run build` 通过 + 全流程手工执行无 console 错误

## 5. Flutter APP（F:\workspace\reqforge_app，可与 4 并行）

- [ ] 5.1 脚手架 Flutter 工程（provider/dio/shared_preferences）+ Material 3 主题 + 目录结构（core/features 见 design D8）；验证：`flutter run`（Windows/debug）出壳页面
- [ ] 5.2 实现 models + `RequirementRepository` 接口 + `MockRequirementRepository`（确定性演示数据：固定需求清单含各状态/优先级/双目标工件样本）；验证：Mock 单测数据确定（两次构造相等）
- [ ] 5.3 底部五槽导航框架（列表/工坊/中央凸起＋提交/记忆占位/我的，Tab 保状态）；验证：滚动列表切 Tab 往返位置保持、记忆 Tab 显示预告空态
- [ ] 5.4 列表页：卡片流（标题/状态徽标/优先级/提出人/更新时间）+ 下拉刷新 + 滚动分页 + 状态筛选 chips；验证：演示模式下刷新与分页行为正确
- [ ] 5.5 提交流：中央＋进入全屏三步表单（与 Web 同构）+ 暂存 + 字段校验 + 提交成功回列表定位新项；验证：演示模式提交闭环场景（spec requirement-app-client）通过
- [ ] 5.6 工坊预览（目标/版本切换、过期提示、只读）+ 我的页（统计卡/后端地址/连接自检/演示模式开关）+ 演示横幅全局组件；验证：关闭演示模式且后端不可达时出现失败态与重试指引（不假装真实数据）
- [ ] 5.7 Phase C 接入真实 API：`ApiRequirementRepository` 按 design D6 契约实现 dio 客户端；验证：对已部署后端联调列表/提交/渲染/导出读路径全通

## 6. 集成验收与文档同步

- [ ] 6.1 端到端验收：Web 全流程导出的 openspec zip 放入临时示例仓库 `openspec/changes/` 执行 validate 与 show；验证：合法且 Requirement/Scenario 结构完整（对应 spec-rendering「产物合法性」场景）
- [ ] 6.2 Docker 部署验证：单容器三进程照常启动、迁移自动执行、/api/requirements 经 gateway 反代可达；验证：容器内 curl 列表接口返回 ApiResponse 信封
- [ ] 6.3 文档同步：architecture.md 增补需求域调用链与模块拓扑、frontend-standards 增补需求工厂页面范式、README 功能清单；验证：三处文档合入且与实现一致
