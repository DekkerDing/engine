# reqforge — 使用手册

> 面向：运维人员、部署工程师、业务运营人员
> 版本：1.0 | 日期：2026-09-13

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

reqforge 是一个**三端协同**系统：
- **engine-server**（:8081）—— 后端业务核心（Java / Spring Boot）
- **engine-gateway**（:8090）—— 聚合网关：Web 静态资源 + API 反向代理
- **reqforge_app**（Flutter）—— 移动端 APP

部署模式：所有服务同机部署，网关是唯一对外端口（8090），server 端口（8081）不对外暴露。

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
| 端口 | 8090、8081 未被占用 | `netstat -an \| findstr "8090 8081"` |

---

## 3. 一键部署脚本

### 3.1 全量构建（首次部署）

在项目根目录 `F:\workspace\engine\` 执行：

```powershell
# Windows PowerShell 一键构建
cd F:\workspace\engine

# Step 1: 构建后端（Java）
.\gradlew :engine-server:bootJar
.\gradlew :engine-gateway:bootJar

# Step 2: 构建前端（React → 静态文件 → 打入网关 jar）
.\gradlew :engine-gateway:buildFrontend
.\gradlew :engine-gateway:copyFrontendDist
.\gradlew :engine-gateway:bootJar

# Step 3: 检查产物
Get-ChildItem engine-server\build\libs\engine-server.jar
Get-ChildItem engine-gateway\build\libs\engine-gateway.jar
```

### 3.2 仅构建后端（改动 Java 代码后）

```powershell
.\gradlew :engine-server:bootJar
```

### 3.3 仅构建前端（改动 React 代码后）

```powershell
cd engine-gateway\frontend
npm run build
# 构建产物在 dist/ 目录，需重新执行 gateway 打包
cd ..\..
.\gradlew :engine-gateway:copyFrontendDist
.\gradlew :engine-gateway:bootJar
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

### 4.1 后端

1. 确保 `engine-server\data\` 目录存在（SQLite 数据库存放位置）
2. 确保 Python 环境可用（用于文本向量化等）
3. 启动 server：
   ```powershell
   java -jar engine-server\build\libs\engine-server.jar
   ```
4. 验证：访问 `http://127.0.0.1:8081/actuator/health` → 返回 `{"status":"UP"}`

### 4.2 网关

1. 确保 server 已启动并健康
2. 启动 gateway：
   ```powershell
   java -jar engine-gateway\build\libs\engine-gateway.jar
   ```
3. 验证：浏览器打开 `http://127.0.0.1:8090` → 看到仪表盘页面

### 4.3 前端开发模式（热更新）

```powershell
cd engine-gateway\frontend
npm install
npm run dev
# 浏览器打开 http://localhost:5173
# /api 请求自动代理到 http://127.0.0.1:8090
```

---

## 5. 启动与停止

### 启动顺序（必须遵守！）

```
1. engine-server (:8081)   ← 先启动
2. engine-gateway (:8090)   ← server 就绪后再启动
3. reqforge_app (Flutter)  ← 最后启动（连接 :8090）
```

### 启动脚本

**Windows（start.bat）**：

```batch
@echo off
echo === Starting engine-server ===
start "engine-server" java -jar engine-server\build\libs\engine-server.jar
timeout /t 10 /nobreak >nul
echo === Starting engine-gateway ===
start "engine-gateway" java -jar engine-gateway\build\libs\engine-gateway.jar
echo === Done. Gateway at http://127.0.0.1:8090 ===
pause
```

### 停止

```powershell
# 查找并终止 Java 进程
Get-Process java -ErrorAction SilentlyContinue | Stop-Process -Force
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
netstat -ano | findstr "8081"

# 终止占用进程（替换 PID）
taskkill /PID <PID> /F
```

### 8.2 前端页面空白

1. 检查 gateway 是否启动了前端资源构建：查看 `engine-gateway/build/frontend-static/static/` 目录是否有文件
2. 重新构建：`.\gradlew :engine-gateway:buildFrontend :engine-gateway:copyFrontendDist :engine-gateway:bootJar`

### 8.3 API 请求 502/504

1. 确认 server 是否启动：`curl http://127.0.0.1:8081/actuator/health`
2. 确认 gateway 配置文件 `application.yml` 中 `upstream.base-url` 指向 `http://127.0.0.1:8081`

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