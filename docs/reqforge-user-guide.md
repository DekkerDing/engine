# reqforge — 使用手册

> 面向：运维人员、部署工程师、业务运营人员
> 版本：1.2（单 JAR 合体） | 日期：2026-09-13

---

## 目录

1. [系统概述](#1-系统概述)
2. [环境要求](#2-环境要求)
3. [一键部署脚本](#3-一键部署脚本)
4. [手动部署步骤](#4-手动部署步骤)
5. [启动与停止](#5-启动与停止)
6. [Web 前端使用](#6-web-前端使用)
7. [APP 端使用](#7-app-端使用)
8. [常见故障排查](#8-常见故障排查)

---

## 1. 系统概述

reqforge 是一个**双端协同**系统：
- **engine-server**（:8090，单 JAR 合体）—— 后端业务核心 + Web 静态资源 + API（Java / Spring Boot，一个进程一个端口承载全部）
- **reqforge_app**（Flutter）—— 移动端 APP

部署模式：所有服务同机部署，:8090 是唯一对外端口（页面与 API 同源提供）。

> v1.2 变更：Go 网关（engine-gateway）已退役，职能并入 engine-server；内部端口 8081 随之消失。对外地址与使用方式完全不变。

---

## 2. 环境要求

| 组件 | 版本要求 | 验证命令 |
|------|----------|----------|
| JDK | 1.8+ | `java -version` |
| Gradle | 7.x（wrapper 自带，无需安装） | `gradlew --version` |
| Node.js | 18+ | `node -v` |
| npm | 9+ | `npm -v` |
| Python | 3.8+ | `python --version` |
| Flutter | 3.x | `flutter --version` |
| 操作系统 | Windows 10+ / Linux | — |
| 端口 | 8090 未被占用 | `netstat -an \| findstr "8090"` |

---

## 3. 一键部署脚本

### 3.1 全量构建（首次部署）

在项目根目录 `F:\workspace\engine\` 执行：

```powershell
# Windows PowerShell 一键构建
cd F:\workspace\engine

# Step 1: 构建单 JAR 合体（前端构建与 python 脚本打包自动拉起，全部进 jar）
.\gradlew :engine-server:bootJar

# Step 2: 检查产物
Get-ChildItem engine-server\build\libs\engine-server.jar
```

### 3.2 仅构建后端（改动 Java 代码后）

```powershell
.\gradlew :engine-server:bootJar
```

### 3.3 仅构建前端（改动 React 代码后）

```powershell
cd engine-server\frontend
npm run build
# 构建产物在 dist/ 目录；发布态需重打 jar 使产物进 BOOT-INF/classes/static/：
cd ..\..
.\gradlew :engine-server:bootJar
```

### 3.4 Flutter APP 构建

```powershell
cd F:\workspace\reqforge_app
flutter pub get
flutter build apk --debug        # Android debug 包
flutter build ios --no-codesign  # iOS（需 macOS）
```

---

## 4. 手动部署步骤

### 4.1 启动合体应用

1. 确保 `engine-server\data\` 目录存在（SQLite 数据库存放位置）
2. 确保 Python 环境可用（用于文本向量化等）
3. 启动（从 engine-server 目录，`./python` 探测命中脚本目录）：
   ```powershell
   cd engine-server
   java -jar build\libs\engine-server.jar
   ```
4. 验证：访问 `http://127.0.0.1:8090/actuator/health` → 返回 `{"status":"UP"}`；
   浏览器打开 `http://127.0.0.1:8090` → 看到仪表盘页面

### 4.2 前端开发模式（热更新）

```powershell
cd engine-server\frontend
npm install
npm run dev
# 浏览器打开 http://localhost:5173
# /api 请求自动代理到 http://127.0.0.1:8090
```

---

## 5. 启动与停止

### 启动顺序

```
1. engine-server (:8090)   ← 唯一后端进程（含 Python 子进程加载模型 30-60s）
2. reqforge_app (Flutter)  ← server 就绪后启动（连接 :8090）
```

### 启动脚本

**Windows（start.bat）**：

```batch
@echo off
echo === Starting engine-server (single-jar) ===
cd engine-server
start "engine-server" java -jar build\libs\engine-server.jar
echo === Done. App at http://127.0.0.1:8090 ===
pause
```

### 停止

```powershell
# 查找并终止 Java 进程（Python 子进程随 Java 的 shutdown hook 连带终止）
Get-Process java -ErrorAction SilentlyContinue | Stop-Process
# 强杀（-Force）不走 shutdown hook，可能遗留 Python 孤儿进程：
# Get-Process python -ErrorAction SilentlyContinue | Stop-Process -Force
```

---

## 6. Web 前端使用

### 6.1 访问地址

- 生产环境：`http://127.0.0.1:8090`
- 开发环境：`http://localhost:5173`

### 6.2 页面导航

| 路径 | 页面 | 用途 |
|------|------|------|
| `/` | 仪表盘 | 系统总览 |
| `/requirements` | 需求列表 | 查看/搜索/筛选所有需求 |
| `/requirements/new` | 提交需求 | 三步表单创建新需求 |
| `/requirements/:id` | 需求工坊 | 渲染规约、预览工件、人工修订 |
| `/requirements/:id/edit` | 编辑需求 | 修改已创建的需求 |
| `/requirements/:id/export` | 导出 | 预览并下载 .md 文件 |

### 6.3 操作流程

**提交需求流程**：
1. 点击「提交需求」按钮 → 进入三步表单
2. **Step 1 基本信息**：填写标题、提出人、优先级、期望上线日期
3. 点击「下一步」
4. **Step 2 业务背景与功能**：描述痛点、影响范围、量化指标；添加至少一条用户故事
5. 点击「下一步」
6. **Step 3 验收标准与约束**：添加至少一条 WHEN/THEN 验收标准；可选添加技术约束
7. 点击「提交需求」→ 系统校验完整性
8. 通过后跳转到成功页 → 可进入工坊

**工坊操作流程**：
1. 在工坊页左栏查看需求摘要和表单内容
2. 右栏选择渲染目标：VibeCoding（TASK.md）或 OpenSpec（完整包）
3. 点击「渲染规约」→ 系统生成规约文件
4. 点击「预览」→ 在弹窗中查看文件内容
5. 可在线编辑文件内容，点击「保存修订」→ 版本号递增

**导出流程**：
1. 点击「导出」→ 进入导出页
2. 选择渲染目标查看文件预览
3. 点击「下载文件」→ 浏览器下载 .md 或 .zip

### 6.4 需求状态说明

| 状态 | 含义 | 可执行操作 |
|------|------|-----------|
| `DRAFT` | 草稿 | 编辑、提交 |
| `SUBMITTED` | 已提交 | 渲染、编辑（回 DRAFT） |
| `EXPORTED` | 已导出 | 渲染、编辑（回 DRAFT）、重新导出 |

---

## 7. APP 端使用

### 7.1 构建和安装

```bash
cd F:\workspace\reqforge_app
flutter pub get
flutter run                    # 连接设备直接运行
flutter build apk --debug      # 生成 APK 安装包
```

### 7.2 API 连接配置

编辑 `lib/config/api_config.dart`：

```dart
class ApiConfig {
  // 开发环境：指向电脑 IP（手机和电脑同 WiFi）
  static const String baseUrl = 'http://192.168.1.100:8090/api';
  // 模拟器：指向宿主机
  // static const String baseUrl = 'http://10.0.2.2:8090/api';
}
```

### 7.3 页面功能

| 页面 | 功能 | 操作 |
|------|------|------|
| 首页/需求列表 | 卡片式列表 + 下拉刷新 + 筛选 | 点击卡片进入工坊 |
| 三步表单页 | 新建需求 | 分步填写，底部固定操作栏 |
| 工坊页 | 查看详情、渲染、预览工件 | Tab 切换，BottomSheet 预览 |
| 导出页 | 预览文件、触发下载 | 切换渲染目标 |

---

## 8. 常见故障排查

### 8.1 端口被占用

```powershell
# 检查端口占用
netstat -ano | findstr "8090"

# 终止占用进程（替换 PID）
taskkill /PID <PID> /F
```

### 8.2 前端页面空白

1. 确认访问的是 `http://127.0.0.1:8090`（页面与 API 同端口）
2. jar 内可能缺前端产物（构建时前端任务链被跳过）：重新构建 `.\gradlew :engine-server:bootJar`
3. 解包验证：`jar tf engine-server\build\libs\engine-server.jar | findstr static` 应见 `BOOT-INF/classes/static/index.html`

### 8.3 API 请求失败（404/503）

1. 确认应用是否启动：`curl http://127.0.0.1:8090/actuator/health`
2. `/api/xxx` 返回 404 属正常语义（未匹配路径不吞成 HTML）；503 多为 Python 引擎启动中或不可达，等模型加载完成（30-60s）后重试

### 8.4 附件上传失败

1. 检查文件格式：仅支持 PNG/JPG/PDF/DOCX
2. 检查文件大小：不超过 20MB
3. 确认 `engine-server/data/requirements/` 目录存在且可写

### 8.5 Python 引擎不可用

```powershell
# 检查 Python 是否在 PATH 中
python --version

# 检查 Python 依赖
pip list | findstr "py4j sentence-transformers"
```