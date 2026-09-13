# 前端代码规范（frontend-standards）

> 适用范围：`engine-server/frontend/`（Vite5 + React18 + TypeScript + ReactRouter6 + AntD5）。
> 本文回答：**页面怎么定位**（栅格与坐标体系）、**尺寸用什么值**（间距/字号 token）、**代码怎么命名**（组件/文件/API 客户端）、**状态怎么展示**（徽标/横幅约定）。
> 布局骨架是手写 CSS Grid——组件风格靠 AntD，布局原理靠手写讲透，这是本工程的教学定位。

---

## 1. 布局栅格与坐标定位体系（全站统一）

### 1.1 骨架：一个 Grid 定义全站坐标

`layouts/AppLayout.css` 的 grid-template 二维声明是**全站唯一的布局坐标系**：

```
grid-template:
  "top  top"   56px     ← 顶栏占两列、固定 56px 高
  "side main"  1fr      ← 侧边 208px 固定宽，内容区吃剩余
  / 208px 1fr           ← 列宽定义（side 208px / main 1fr）
```

三条铁律：
1. **顶栏/侧边永不动**：页面切换只有 main 区变化（spec: frontend-app/标准化布局体系——"页面之间切换不发生布局跳动"）
2. **新页面只进 main**：任何页面组件都是 main 区的填充物，不自带顶栏/侧边
3. **不用 position 搭布局**：定位体系 = Grid 区域名（top/side/main），绝对定位只用于浮层（下拉/提示，且优先交给 AntD）

### 1.2 路由与布局的对应

```
/           → AppLayout > DashboardPage
/documents  → AppLayout > DocumentsPage
/search     → AppLayout > SearchPage
```

路由在 `App.tsx` 声明，页面组件放 `pages/<域>/`；深层路由刷新依赖服务端 SPA 回退
（非 /api 未命中 → index.html，`SpaFallbackResolver` 提供）。

---

## 2. 间距与字号 token

### 2.1 间距档位（4 的倍数，全站只允许这四档）

| token | 值 | 用途 |
|-------|-----|------|
| xs | 4px | 图标与文字、徽标内距 |
| sm | 8px | 同组元素内间距 |
| md | 16px | 卡片内边距、骨架 gap（默认档） |
| lg | 24px | 区块间距、页面容器内边距 |

写法：直接写数值 + 注释标注档位（如 `gap: 16px; /* 间距档位 16 */`）；
出现 12px/20px 等档位外数值即为违规（微调需求归档到最近档位）。

### 2.2 字号

| 层级 | 值 | 用途 |
|------|-----|------|
| 页面标题 | 18px | main 区页头 |
| 卡片标题 | 15px | 组件标题 |
| 正文 | 14px | 默认（AntD 默认对齐） |
| 辅助说明 | 12px | 时间、分数、提示文案 |

中文字体栈跟 AntD 默认，不自定义；**禁止**用 px 之外单位设定字号。

### 2.3 颜色

主色与语义色跟 AntD token（`@primary-color` 等），不自造色值；
状态色约定见 §5（成功/处理中/失败/降级）。

---

## 3. 组件与命名规范

### 3.1 目录职责

```
engine-server/frontend/src/
├── api/          # 统一 API 客户端（唯一允许发请求的地方）
│   ├── client.ts       # axios 实例：信封解析（code≠0 抛错）、超时、错误 toast
│   ├── types.ts        # 后端 DTO 类型定义（与 server 响应字段一一对应）
│   └── {documents,search,system}.ts   # 按域分文件的方法集
├── layouts/      # 布局骨架（AppLayout.tsx + .css，全站唯一）
├── pages/        # 页面：pages/<域>/<域>Page.tsx（dashboard/documents/search）
├── components/   # 跨页面共享组件（HighlightText / StatusBadge / DegradedBanner）
└── hooks/        # 自定义 hook（usePolling：列表轮询自动流转）
```

### 3.2 命名

| 对象 | 约定 | 样例 |
|------|------|------|
| 组件文件/导出 | PascalCase，组件名 = 文件名 | `StatusBadge.tsx` → `StatusBadge` |
| 页面组件 | `XxxPage` | `DocumentsPage` |
| 共享组件 | 名词性、领域词 | `HighlightText` `DegradedBanner` |
| hook | `use` 前缀 + 动作 | `usePolling` |
| API 方法 | 动词开头，与后端端点对应 | `uploadDocument()` `search()` `fetchHealth()` |
| 样式文件 | 与组件同名 `.css`，只配 BEM 式类名 | `AppLayout.css` |
| TS 类型 | 与后端 DTO 字段 camelCase 对齐 | `DocumentSummary` `SearchHit` |

### 3.3 组件规则

- **请求只走 api/**：组件禁止直接 `axios`/`fetch`；统一 client 保证信封解析与错误提示一致
- **状态徽标/高亮只用共享件**：状态展示统一 `StatusBadge`，命中高亮统一 `HighlightText`——不允许页面内自绘状态色
- **轮询用 usePolling**：列表自动流转（上传后 UPLOADED→…→INDEXED）一律走共享 hook（间隔与停止条件参数化），页面不自己写 setInterval
- 页面组件保持"取数 + 布局"，复杂逻辑下沉 hook 或 api 层

---

## 4. 统一 API 客户端约定

```typescript
// 信封：{code, message, data}
// client.ts 职责：code === 0 → 返回 data；code ≠ 0 / HTTP 4xx/5xx → 抛错 + 统一 toast
// 页面侧只处理"成功的 data"与"空态/错误态渲染"，不重复判错
```

- 超时：全局统一配置（跟随后端读超时 60s 量级），个别快接口可单独收紧
- 开发态经 vite devServer 代理 `/api → :8090`；**产物为相对路径请求**，不含后端绝对地址
- 后端不可达：统一错误提示（不白屏不崩溃），用户可重试（spec: 统一 API 客户端与错误处理）

---

## 5. 状态徽标与降级展示约定

### 5.1 文档状态徽标（StatusBadge）

| 后端状态 | 展示 | 色 |
|----------|------|-----|
| UPLOADED / PARSING / CHUNKING / VECTORIZING | 处理中 | 蓝（processing） |
| INDEXED（COMPLETED） | 已索引 | 绿（success） |
| FAILED | 失败 | 红（error）——点击可看失败原因 |

规则：处理中态必须配合轮询自动流转，**不允许**要求用户手动刷新。

### 5.2 降级全局横幅（DegradedBanner）

- 引擎 `degraded=true` 时挂载于布局顶栏下方，**所有页面可见**
- 文案统一含"检索精度受限"语义 + 原因；引擎恢复后自动消失
- 数据源：健康接口（`/api/system/health` 的 engine 段），不做前端推断

### 5.3 健康卡片（仪表盘）

三组件卡片（网关/业务服务/Python 引擎——合体后前两段由服务端恒 UP 自证，段名保留兼容）+ 通道/模型/维度/降级原因字段；
异常组件卡片转红并在刷新周期内可见（默认轮询周期内自动恢复）。

---

## 6. 编码与工程约定

- TypeScript strict；任何 `any` 需注释豁免理由
- 组件用函数组件 + hooks，不用 class 组件
- 开发用非严格模式起步（AntD5 与 React18 严格模式的兼容问题，升级路径记录于仓库）
- 样式：普通 CSS 文件 + BEM 命名；**不引入** CSS-in-JS/预处理器（教学保持零抽象）
- 提交前 `npm run build` 必须零报错（类型错误不允许忽略）
