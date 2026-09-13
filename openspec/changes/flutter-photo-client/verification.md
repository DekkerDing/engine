# 验证记录 — flutter-photo-client · Phase A（演示数据端到端）

> 验收环境：Windows 11 Pro（10.0.22000）· Flutter stable · `F:\workspace\lifeCloud_app`
> 验收日期：2026-09-13

## 1. 总体结论

Phase A（演示数据、零后端运行）全部完成：**8/8 任务（1.x–8.x）**，
`flutter analyze` 零问题，`flutter test` **78/78 全绿**，
`flutter build windows --debug` 产物可运行。

| 验收项 | 结果 | 证据 |
| --- | --- | --- |
| 静态检查 | ✅ | `flutter analyze` → `No issues found! (ran in 8.0s)` |
| 单测/组件测试 | ✅ | `flutter test` → `00:39 +78: All tests passed!` |
| Windows 桌面构建 | ✅ | `build/windows/x64/runner/Debug/lifecloud_app.exe` |

## 2. Windows 全流程走查（浏览→搜索→预览→演示上传→开关切换）

每一步均有组件测试锚定（页面对应测试文件、断言要点）：

| 流程步骤 | 页面/文件 | 锚定测试（test/） | 关键断言 |
| --- | --- | --- | --- |
| ① 启动即浏览（演示数据 24 张） | `lib/shell/app_shell.dart` + `lib/pages/home_page.dart` | `shell/app_shell_test.dart`、`widgets/photo_card_test.dart` | 五槽导航就位、瀑布流卡片三形态（mocked/未拆分/占位） |
| ② 相册：网格/瀑布切换、筛选、分页、下拉刷新 | `lib/pages/album_page.dart` | `pages/album_page_test.dart`（7 用例） | 布局偏好持久化（UniqueKey 重启）、pageSize=12 分页无重复直至 24 张 hasMore=false、筛选 chips 与卡片徽标一致 |
| ③ 搜索：历史/推荐/三态/加深 | `lib/pages/search_page.dart` | `pages/search_page_test.dart` | 历史去重前移上限 10 并持久化、命中卡分数胶囊「重排 xx%」、空态「加深搜索」阶梯 8→24、错误态可重试 |
| ④ 大图预览：Hero + 左右切换 + 标注面板 | `lib/pages/photo_preview_page.dart` | `pages/photo_preview_page_test.dart` | Hero tag 命名空间 `photo-{id}-{source}` 无冲突、滑页后面板收起、标签/描述/分数展开可见 |
| ⑤ 演示上传：多选→进度→失败明细 | `lib/pages/upload_page.dart` | `pages/upload_page_test.dart` | 3 选 1 失败注入（成功 2/失败 1）、「演示：任务仅在本机演示库执行」明示、回到首页 popUntil |
| ⑥ 我的页统计 + 演示开关 + 设置子页 | `lib/pages/profile_page.dart`、`lib/pages/settings_page.dart` | `pages/profile_page_test.dart`、`pages/settings_page_test.dart` | 统计 24/20/18/4 与数据源一致、开关即时隐藏/恢复顶部演示横幅并触发画廊刷新（持久化）、地址校验（http(s) 前缀）+ 自检不可达给出排查建议不崩溃 |

## 3. 演示语义的如实呈现（spec：不假装成功）

- 壳顶横幅：`演示数据：内置样例，未连接真实后端（「我的」页可关闭）`（`app_shell.dart`，amber 显式标识）。
- 上传完成态：`演示：任务仅在本机演示库执行，未真实入库（关闭演示模式后走真实后端）`。
- 我的页开关副标题直接标注 `使用真实后端（Phase B 接通）`。

## 4. 测试基建经验（复用到 Phase B）

- 桌面/移动选图按平台分发，`UploadPage(picker:...)` 注入假选择器测试。
- 自检为真实 `HttpClient` 探测（3s 超时），测试经 `SettingsPage(probe:...)` 注入假探测函数。
- `LifeCloudApp(key: UniqueKey())` 实现真「重启」语义（Provider 树重建 + SharedPreferences mock 保留写入）。
- 惰性视口（GridView/MasonryGridView）用 `findsWidgets` + 数据层断言，必要时设手机视口 412×916。

## 5. 遗留与下一步（Phase B，任务 9.x）

- 9.1 `ApiPhotoRepository`（dio）→ 9.2 真实上传分批编排 → 9.3 关闭演示模式全链路 → 9.4 Android 真机验收。
- 后端就绪度：`photo-semantic-search` 23/23 已完成（列表/搜索/上传/统计 API 全部可用），客户端仅需切仓库实现。
