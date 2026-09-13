# container-deployment — 容器化部署规格（delta）

## Purpose

以单个 Docker 容器交付全栈运行时（JRE + Python + 双 jar + 模型权重），运行时不含 Node/npm，一条 docker run 完成部署；数据经卷持久化，进程启停全托管。

## ADDED Requirements

### Requirement: 单容器全栈交付
镜像 SHALL 包含 JRE 8、Python 运行时及全部 pip 依赖、两个应用 jar（业务服务内嵌 Python 脚本、网关内嵌前端产物）、预置模型权重；单容器对外仅暴露网关端口（默认 8090），业务服务端口 SHALL NOT 对外发布。

#### Scenario: 一条命令启动全栈
- **WHEN** 执行 docker run 映射 8090 端口后用浏览器访问
- **THEN** 页面可用、上传/检索全流程正常，无需任何容器外组件

### Requirement: 运行时镜像不含 Node
运行时镜像 MUST NOT 包含 node、npm 及前端源码/构建工具链；镜像内仅存在编译后的静态产物（经网关 jar 内嵌）。

#### Scenario: 镜像内无 Node
- **WHEN** 在运行容器内尝试执行 node --version 或 npm --version
- **THEN** 命令不存在

### Requirement: 容器内进程编排
容器入口 SHALL 按依赖顺序启动业务服务与网关（网关在业务服务健康后对外可用可等待）、容器停止时优雅终止全部进程（含 Python 子进程），任何子进程异常退出 SHALL 导致容器以非零码退出（不静默带病运行）。

#### Scenario: 优雅停机无孤儿
- **WHEN** 执行 docker stop
- **THEN** Java 进程与其 Python 子进程在宽限期内全部退出，容器正常终止

#### Scenario: 关键进程崩溃即容器退出
- **WHEN** 业务服务进程在容器内异常退出
- **THEN** 容器随之以非零码退出（便于编排器重启），而非只剩网关空转

### Requirement: 双构建轨镜像
SHALL 提供两个镜像构建入口且产出行为一致：本地构建轨（Dockerfile，COPY 本地已构建的两个 jar）与全构建轨（Dockerfile.full，多阶段在容器内从源码完成前端构建与 jar 构建）；两轨镜像的对外行为（页面、API、检索）SHALL 完全一致。

#### Scenario: 两轨产出等价
- **WHEN** 分别用本地构建轨与全构建轨构建镜像并各自运行
- **THEN** 两个容器通过同样的页面操作与检索请求得到一致结果

### Requirement: 模型权重内置与离线运行
镜像 SHALL 在构建期预置全部已启用模型权重（经国内镜像下载）；容器启动与运行期 SHALL NOT 依赖外部模型下载网络，离线环境首启即可用。

#### Scenario: 断网首启可用
- **WHEN** 在无外网环境启动容器并立即发起摄取与检索
- **THEN** 模型直接加载成功，功能正常且不发生模型下载

### Requirement: 数据卷持久化
全部有状态数据（数据库文件、上传文档目录、全文索引目录）SHALL 存放于容器内统一数据目录并可经卷挂载持久化；容器删除重建后，已摄取文档 SHALL 仍可检索。

#### Scenario: 重建容器数据存活
- **WHEN** 摄取文档后销毁容器，再以同一卷重新运行新容器
- **THEN** 该文档仍在库中且可正常检索命中
