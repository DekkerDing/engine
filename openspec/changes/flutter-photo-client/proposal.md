# Flutter 照片客户端（lifeCloud_app）

## Why

照片语义检索后端能力（拆分标注 / 双路召回 / 重排 / 批量导入）已由 `photo-semantic-search` 变更交付，但唯一前端是桌面 React 网页——照片天然产生于手机、浏览于手机，需要移动端原生体验（相册多选上传、随手搜索、大图预览）承载这条链路。第一版按成熟商业应用（美团 / 淘宝 / 京东）的信息架构范式设计完整页面体系与跳转交互，并以**演示数据模式先行**（不依赖后端实施进度即可跑通全部页面），后端就绪后切换真实 API。

## What Changes

- **新建 Flutter 工程 `lifeCloud_app`**（目录 `F:\workspace\lifeCloud_app`，在 engine 仓库之外——变更文档与验收归 engine 仓库管理，实施产物按用户指定落位仓库外，此为经确认的边界例外）
- **商业级页面架构**（借鉴美团/淘宝/京东的共性范式）：
  - **底部五槽导航 + 中央凸起上传按钮**（类美团/京东发布键）：首页 / 相册 / ＋上传 / 搜索 / 我的，Tab 切换保状态
  - **首页**（类美团首页）：顶部胶囊搜索栏（占位"搜索照片：红色的小花…"）→ 功能宫格（全部照片 / 最近上传 / 批量导入 / 模拟识别 / 未拆分 / 演示模式…）→ 照片瀑布流卡片（类淘宝"猜你喜欢"双列流，卡片含缩略图/主题/描述/徽标）
  - **相册页**（类淘宝格子铺）：网格/瀑布流视图切换、标注状态筛选 chips、下拉刷新、滚动分页
  - **搜索页**（类京东搜索）：搜索历史与推荐词、命中瀑布流（分数可视化）、空态/错误态/重试
  - **上传页**（中央＋进入，类电商发布流）：相册多选、已选管理、共享描述（caption）、进度与失败明细
  - **我的页**（类淘宝"我的"）：照片统计卡 + 设置项（后端地址 / 连接自检 / 演示模式开关 / 关于）
  - **大图预览**（Hero 共享元素过渡全屏页）：原图 + 底部可展开标注面板（主题/描述/标签/分数）、左右滑动切换
- **页面跳转交互体系**：首页搜索栏推入搜索页、宫格入口带参跳相册筛选态、卡片 Hero 动画进预览、上传 modal 全屏流、我的页子页右侧推入——完整路由表见 design
- **演示数据模式（第一版先行）**：`PhotoRepository` 接口 + Mock 实现（内置确定性样例：本地生成占位图 + 与后端演示语义一致的标注样例如"红色的小花/红塔"）；演示模式显式横幅标注（对齐工程 mocked 显式标志纪律），经"我的"页开关切换真实 API 实现
- 不做：拍照直传、离线缓存、账号体系、推送（Non-goals）

## Capabilities

### New Capabilities

- `flutter-client`: Flutter 移动客户端——商业级页面架构（底部导航/首页/相册/搜索/上传/我的/预览）与跳转交互、演示数据模式、照片浏览 / 批量上传与进度 / 语义搜索与预览 / 后端连接配置；对接 engine-gateway 既有 `/api` 契约与 `photo-semantic-search` 扩展端点

### Modified Capabilities

（无——后端 API 由前置变更交付，本变更零后端改动；engine-gateway 作为哑反代不需要改动）

## Impact

- **代码**：新工程 `F:\workspace\lifeCloud_app`（lib/ 按 features 分层：home / gallery / upload / search / profile / preview + core：api / repo / config / models / theme）；engine 仓库零代码改动
- **依赖**：Flutter SDK（用户本地已装）、`dio`（HTTP/multipart）、`image_picker`（相册多选；Windows 按需 file_picker）、`cached_network_image`（缩略图缓存）、`shared_preferences`（配置与演示模式开关）、`provider`（状态管理）
- **API 消费**（演示模式关闭后）：`POST /api/images`（含 caption）、`POST /api/images/batch`、`GET /api/images/import-tasks/{id}`、`GET /api/images/{id}/file`、`POST /api/search`（modality=image + rerank 参数 + 标注字段）——全部为前置变更交付的契约
- **平台**：首期 Android + Windows 桌面（本地联调最快路径）；iOS/web 不在验证范围
- **分期**：Phase A（UI 壳 + 演示数据，全部页面与交互可跑，零后端依赖）→ Phase B（接入真实 API，设置/上传/搜索/画廊切换数据源）——Phase A 可与后端变更 `photo-semantic-search` 并行实施

## Non-goals（明确不做）

- 后端任何改动（含 gateway）
- 拍照/相机集成、照片编辑、离线队列与断点续传
- 账号、多用户、鉴权、推送通知
- iOS 与 web 平台适配（工具链在用户环境未就绪）
- 深度品牌视觉定制（第一版用 Material 3 体系 + 单一品牌主色，不做多主题/换肤）

## Assumptions

- 演示数据模式让 Phase A 不阻塞于 `photo-semantic-search` 的实施进度；Phase B 要求该变更已实施（API 契约前置），未实施时演示模式为唯一可用数据源
- 演示模式占位图由本地 Canvas 确定性生成（渐变+图形，零网络依赖），标注样例与后端演示语义一致（"红色的小花"等），保证 Phase B 切换后搜索行为连贯
- 真机联调时手机与开发机同局域网，直连 `http://<开发机IP>:8090/api`（gateway 现有 CORS/反代行为对原生 HTTP 客户端无阻碍）
- Flutter 工程不纳入 Gradle 多模块（沿 settings.gradle 注释确立的"每种语言用原生构建"纪律，engine 构建不感知它）
