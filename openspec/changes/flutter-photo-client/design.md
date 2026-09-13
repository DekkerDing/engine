# design — Flutter 照片客户端（lifeCloud_app）

## Context

后端契约已定：`/api/images`（含 caption）、`/api/images/batch` + `/api/images/import-tasks/{id}`、`/api/images/{id}/file` 原图、`/api/search`（modality=image、rerank 开关、命中含标注摘要与分数）——全部经 gateway :8090 反代，由前置变更 `photo-semantic-search` 交付（可并行于本变更实施）。工程落位 `F:\workspace\lifeCloud_app`（仓库外，proposal 已声明的边界例外）；Flutter SDK 本地就绪；engine 的 Gradle 不感知此工程。第一版要求商业级页面体系与跳转交互（用户指定参考美团/淘宝/京东范式），且后端未就绪时即可跑通——演示数据模式为一等公民。

## Goals / Non-Goals

**Goals:**

- 商业范式信息架构：底部五槽导航 + 首页（搜索栏/宫格/瀑布流）/ 相册 / 搜索 / 我的 + Hero 预览，页面跳转交互完整
- Phase A（演示数据）零后端依赖全流程可跑；Phase B 换数据源实现零页面改动（Repository 接缝）
- Windows 桌面联调 + Android 真机可用；错误态/空态/加载态三态覆盖
- 状态管理轻量可预期（provider，四功能正交）

**Non-Goals:**

- 不引代码生成全家桶（freezed/build_runner）与重路由框架（go_router）——named routes 够用
- 不做多主题/换肤/深度品牌定制（Material 3 + 单一品牌主色）
- 不做离线缓存与队列、后台续传（沿 proposal Non-goals）

## Decisions

### D1 · 技术底座：provider + dio + 原生 Navigator

- **状态管理 `provider`**：ChangeNotifier 按功能域各一（GalleryProvider / SearchProvider / UploadProvider / ProfileProvider），不搞全局 store。否决 riverpod（概念面大、四页收益不匹配）与 bloc（样板重）
- **HTTP `dio`**：multipart / 拦截器（动态 baseUrl）/ 超时一体。否决 http 包（multipart 手工度高）
- **图片 `cached_network_image`**（缩略图双层缓存，画廊滚动性能底线）；原图预览 `Image.network` + fade
- **选图 `image_picker`**（`pickMultiImage`）；Windows 桌面按平台条件走 file_picker（实施期小决策，行为不变）
- **路由：原生 `Navigator` + named routes + 路由参数**。否决 go_router——无嵌套深链/守卫需求，五 Tab + 六推入页的规模引框架是负资产
- **持久化 `shared_preferences`**：后端地址 / 演示模式开关 / 视图偏好 / 搜索历史

### D2 · 信息架构与路由表

```
                    ┌──────────────────────────────┐
                    │        主导航壳 (Shell)        │
                    │  IndexedStack 保状态的四个 Tab  │
                    └──────────────────────────────┘
 ┌──────────┬──────────┬──────────┬──────────┐
 │ 首页 home │ 相册 album │ ＋上传(凸起) │ 搜索 search │ 我的 profile │
 └────┬─────┴────┬─────┴─────┬────┴─────┬─────┴─────┬─────┘
      │          │           │          │           │
      ├─▶搜索页(聚焦) ├─▶筛选态(参数) ├─▶上传流程   ├─▶预览(Hero) ├─▶预览(Hero)
      └─▶宫格▶相册/上传           └─▶进度页      └─▶大图       └─▶设置子页(推入)
```

| 路由 | 进入方式 | 过渡 | 返回 |
|---|---|---|---|
| `/` | 启动 | — | — |
| `/search` | Tab / 首页搜索栏 | Tab 切换或推入（搜索栏入口自动聚焦输入框） | 壳 |
| `/preview?ids&index&source` | 任意照片卡片 | **Hero 共享元素** + 页面渐隐 | 来源页 |
| `/upload` | 中央＋ / 首页宫格 | 全屏 modal（自底向上滑入，类电商发布流） | 壳 |
| `/upload/progress` | 上传提交 | 推入 | 上传页（完成态提供"去画廊"直达） |
| `/settings` | 我的页设置项 | 右侧推入 | 我的 |
| `/album`（Tab） | Tab / 宫格带参 | Tab；带参时设置筛选态 | 壳 |

- 预览页参数传列表 id 集 + 起始索引 + 来源（相册/搜索），左右滑动用 `PageView`——同一批内切换，避免列表态与预览态数据模型分裂
- Tab 用 IndexedStack 保状态（spec 场景约束）；推入页返回不重建 Tab 态

### D3 · 商业布局借鉴映射

| 借鉴对象 | 借鉴点 | 落位 |
|---|---|---|
| 美团首页 | 顶部胶囊搜索栏（占位文案引导查询语义）+ 2×4 功能宫格 | 首页上两段 |
| 淘宝首页 | 双列瀑布流"猜你喜欢"卡片（图为主、文为辅、异高卡片） | 首页照片流 / 相册瀑布流视图 / 搜索结果 |
| 京东搜索 | 搜索历史 chips + 推荐词、结果分数/标签角标可视化 | 搜索页 |
| 美团/京东发布键 | 底部中央凸起发布按钮 | ＋上传入口 |
| 淘宝"我的" | 头部统计卡 + 分组设置列表 | 我的页 |
| 通用相册 | 三列均匀网格 / 双列瀑布流切换、chips 筛选 | 相册页 |

瀑布流选型：`flutter_staggered_grid_view`（成熟、异高子项）。否决自写 CustomScrollView 算法——成熟包即成熟范式。

### D4 · 演示数据层：Repository 接缝 + 确定性样例

```
core/repo/PhotoRepository（抽象：列表分页/详情/搜索/上传任务/统计）
  ├─ MockPhotoRepository   演示实现（Phase A）
  └─ ApiPhotoRepository    dio 实现（Phase B，消费前置变更契约）
```

- **占位图**：本地 `CustomPainter` 确定性生成（种子→渐变底色+几何图形组合），零网络依赖、同 id 同图——否决 picsum 网络图（联调断网即碎、不确定性违背演示纪律）
- **标注样例**：内置 24 张样例（主题/描述/标签/分数），语义与后端演示链路一致——"红色的小花""红塔与白云""海边的日出"等；构成覆盖三类卡片：mocked=true / 未拆分 / 高低分各异（搜索排序可视化需要）
- **搜索语义**：演示搜索按查询词与样例描述的字符/分词重合度打分排序（简单确定性规则即可，不求检索真实性）——目标是交互与布局可验，不是检索质量
- **演示上传**：本地模拟任务（计数递增 200ms/张、注入 1 张失败样例），完成态明示"演示：未真实入库"
- **显式纪律**：演示模式下页面顶部常驻"演示数据"横幅（琥珀色）——对齐工程 mocked=true 的显式标志纪律，不伪装真实数据

### D5 · 上传任务编排：客户端分批 + 单进度流

多选 N 张 → 按 `batch.max-files`（后端配置或本地常量 100 取小）切批 → 串行提交（每批 multipart files + captions 等长填充共享描述）→ 首批返回 taskId 后逐批轮询 `import-tasks/{id}`（2s 起、退避上限 10s）→ 各批 done/total 本地累计合成总进度 → 失败批不阻断后续 → 汇总失败明细。演示模式下同一编排走 Mock 任务的相同进度协议（Provider 不感知数据源差异）。否决并行多批（后端导入并发已受控为 2，并行只加剧排队且进度语义混乱）。

### D6 · 搜索交互：一次查询 + 加深搜索

提交 → `POST /api/search`（modality=image, rerank 开）→ 首屏 topN 瀑布流。结果不做滚动分页（重排分数序与翻页语义冲突）——提供「加深搜索」按钮（提高 topK 重查，loading 态明示）。空结果与错误态视觉区分（spec 场景）。历史与推荐词本地持久化；历史去重、最近优先、可一键清空。

### D7 · 视觉规范（Material 3 + 单一品牌色）

- **色**：`ColorScheme.fromSeed(品牌主色 深青 #00695C)`；mocked 徽标琥珀 `#F59E0B` 底 10% 透明；"演示数据"横幅同琥珀系（与徽标同族，统一"非真实"色语）
- **形**：卡片圆角 12 / 搜索栏胶囊全圆 / 徽标圆角 4；卡片阴影 8% 不透明度（M3 tonal elevation 优先）
- **距**：页面水平 padding 12、卡片间距 8、瀑布流双列 gutter 8、宫格纵横距 12
- **字**：标题 16/600、正文 14/400、辅助 12/400 灰（onSurfaceVariant）；卡片主题截断单行、描述两行 ellipsis
- **分数可视化**：命中卡片右上角浮层小胶囊（`93%`），重排分与相似度同形制不同图标（闪电/水滴或文字标注），进度条仅用于上传
- **空态**：图标 + 一句话 + 动作按钮（如"去上传照片"）

### D8 · 预览交互：Hero + PageView + 面板

Hero tag = `photo-${id}-${source}`（source 防相册与搜索同图 tag 冲突）；PageView 相邻切换（传 id 列表与索引，翻页同步更新面板）；底部标注面板 DragBottomSheet 可展开（收起态露主题一行，展开态全量字段+分数）；顶部渐变遮罩返回键。原图 loading 态：缩略图先铺底 + fade 过渡（感知性能）。

### D9 · 配置与联调

- `BackendConfig`：缺省 `http://localhost:8090/api`；Android 真机改局域网 IP；保存 shared_preferences，dio 拦截器动态读
- **演示模式默认开启**（首启无后端也能完整体验）；我的页开关切换 + 自检通过时提示"可关闭演示模式"（不自动切，切换是显式用户行为）
- Android 明文 HTTP：`usesCleartextTraffic="true"`（联调期，代码注释标记发布态收紧）
- 联调顺序：Windows `flutter run -d windows`（Phase A 全流程）→ 后端就绪后关演示模式联 Phase B → Android 真机验收

## Risks / Trade-offs

- [image_picker Windows 支持不稳] → 平台条件 file_picker（D1，行为不变）
- [IndexedStack 四 Tab 常驻内存] → 缩略图缓存上限配置（`maxNrOfCacheObjects`/`stalePeriod`）；照片 App 可接受
- [演示搜索的简单打分误导质量预期] → 横幅+文档明示演示模式仅验交互；真实检索质量以后端为准
- [上传中 App 退后台进度中断] → 批内重试 + 失败明细兜底；后台续传属 Non-goal
- [Hero 在 Windows 桌面大图卡顿] → Hero 关闭动画时长可平台条件微调；不改结构
- [重排延迟 1-2s 首屏偏慢] → loading 态 + 「加深搜索」交由用户选择；后端降候选数是另一侧手段

## Migration Plan

1. 纯新增工程，无迁移；engine 仓库零改动
2. **Phase A（可与后端变更并行）**：脚手架 → 导航壳 → 首页 → 相册 → 搜索 → 预览 → 上传（演示）→ 我的（演示开关）——交付物：演示模式全流程可跑的 APP
3. **Phase B（依赖 `photo-semantic-search` 实施）**：ApiPhotoRepository → 自检 → 真实上传/搜索/画廊——交付物：真数据闭环
4. 回滚：删除工程目录即可

## Open Questions

- 共享描述 vs 逐张描述的 UI 形态——首版共享描述（最快闭环），逐张编辑属标注编辑 Non-goal 延伸
- 相册页"最近上传"排序参数是否需要后端支持（现契约按上传时间倒序即可满足，无需改）——实施时确认列表端点排序字段
- Android `photo_manager` 整相册浏览是否优于临时多选——首版多选够用，后议
