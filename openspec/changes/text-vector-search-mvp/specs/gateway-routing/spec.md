# gateway-routing — 聚合网关规格（delta）

## Purpose

作为系统唯一流量入口：承载 React 前端静态资源与 SPA 路由回退、将 /api 请求反向代理到业务服务、聚合全链路健康状态；浏览器与外部只感知这一个端口。

## ADDED Requirements

### Requirement: 静态资源服务
网关 SHALL 服务前端构建产物：根路径返回 index.html，静态资产（JS/CSS/图片）按路径返回对应文件；带内容哈希的资产 SHALL 配置长缓存，index.html SHALL 不缓存。

#### Scenario: 根路径返回页面
- **WHEN** 浏览器访问网关根路径
- **THEN** 返回 index.html（HTTP 200），页面在浏览器正常渲染

#### Scenario: 哈希资产长缓存
- **WHEN** 浏览器再次请求此前已获取的带哈希文件名的 JS 资产
- **THEN** 响应携带长周期缓存头，浏览器可使用本地缓存

### Requirement: SPA 路由回退
对不属于 /api 前缀且不匹配任何静态文件的路径，网关 SHALL 返回 index.html（HTTP 200，非 404），使前端路由在页面刷新/直接输入 URL 时可用。

#### Scenario: 深层路由刷新不 404
- **WHEN** 用户在 /search 页面按 F5 刷新
- **THEN** 网关返回 index.html，前端路由恢复到检索页而非显示 404

### Requirement: API 反向代理
网关 SHALL 将 /api/** 请求剥去 /api 前缀后转发到业务服务（127.0.0.1:8081）：透传 HTTP 方法、请求头、请求体，并将业务服务的状态码与响应体原样返回；连接超时（默认 2 秒）与读超时（默认 60 秒）SHALL 可配置。

#### Scenario: 代理转发成功
- **WHEN** 前端 POST /api/documents 上传文档
- **THEN** 业务服务收到 POST /documents（无 /api 前缀），其响应经网关原样返回给前端

#### Scenario: 上游错误状态透传
- **WHEN** 业务服务对某请求返回 500
- **THEN** 网关向浏览器同样返回 500 与原始错误体，不吞错

### Requirement: 流式响应透传
网关 SHALL 以流式方式透传上游响应体（不整包缓冲），保证后续进度推送类接口（如 SSE）可经网关正常工作。

#### Scenario: 渐进式接收
- **WHEN** 上游返回分块流式响应
- **THEN** 浏览器在整体完成前即可逐块收到数据

### Requirement: 上游不可达的明确失败
业务服务不可达时，网关 SHALL 在短超时内返回网关错误（5xx）与结构化错误信息（含"上游服务不可用"语义），不得挂起浏览器请求。

#### Scenario: 业务服务停止时快速失败
- **WHEN** 业务服务进程不存在时前端发起 /api 请求
- **THEN** 网关在秒级返回 5xx 与明确错误信息，浏览器请求不超时挂起

### Requirement: 聚合健康端点
网关 SHALL 提供 /api/system/health，聚合：网关自身存活、业务服务存活（周期探测，默认 10 秒一次、连续 3 次失败才判 DOWN 以容忍重启）、Python 引擎状态（经业务服务透传），返回整链路分层状态。

#### Scenario: 全链路健康
- **WHEN** 网关、业务服务、Python 引擎均正常
- **THEN** 聚合健康返回整体 UP 且三个组件状态各自可见

#### Scenario: Python 降级联动展示
- **WHEN** Python 引擎处于哈希降级模式
- **THEN** 聚合健康显示引擎组件为"降级"而非 DOWN，整体状态标注部分异常

### Requirement: 全链路中文编码
网关 SHALL 保证中文在整个链路（请求参数、请求体、响应体、文件名）不出现乱码，统一 UTF-8。

#### Scenario: 中文查询往返
- **WHEN** 前端提交含中文的检索查询
- **THEN** 业务服务收到无乱码的原文，响应中的中文片段在浏览器正常显示
