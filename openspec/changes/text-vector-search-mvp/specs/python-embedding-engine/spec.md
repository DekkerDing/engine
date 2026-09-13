# python-embedding-engine — Python 向量化引擎规格（delta）

## Purpose

由 Java 进程内拉起的 Python 子进程提供文本向量化能力：真实模型优先、确定性哈希显式降级、双模型可切换、双通道容错，状态全程可查询。

## ADDED Requirements

### Requirement: 批量文本编码
引擎 SHALL 接受一批文本（1~N 条）并返回与输入顺序一一对应的等长向量列表，同时返回向量维度与实际使用的模型名；空批次 SHALL 返回参数错误。

#### Scenario: 批量编码保持顺序
- **WHEN** 提交 3 条文本的编码请求
- **THEN** 返回 3 个向量，第 i 个向量对应第 i 条输入文本

### Requirement: 确定性降级与显式标志
真实模型不可用时，引擎 SHALL 退回到确定性哈希编码：同一文本在任何时刻、任何次调用下得到相同向量；所有降级响应 SHALL 携带 degraded=true 与失败原因，禁止无标志的假向量。

#### Scenario: 降级向量确定性
- **WHEN** 模型不可用时对同一文本编码两次
- **THEN** 两次返回的向量完全一致，且响应均标记 degraded=true

#### Scenario: 真实模型可用时不降级
- **WHEN** 模型加载成功后发起编码
- **THEN** 响应标记 degraded=false 并返回真实模型名

### Requirement: 双模型注册与切换
引擎 SHALL 同时注册两个文本模型槽位：默认 bge-small-zh-v1.5（512 维）与备选多语 MiniLM（384 维），按配置键选择；切换模型键后新编码使用新维度；当查询向量与库内向量维度不一致时，系统 SHALL 拒绝该次检索并提示需要重新摄取，而非返回错误结果。

#### Scenario: 默认模型维度
- **WHEN** 使用默认配置发起编码
- **THEN** 返回 512 维向量与 bge 模型名

#### Scenario: 维度不一致的检索被拒绝
- **WHEN** 库内向量由 384 维模型生成，而当前配置切换为 512 维后发起检索
- **THEN** 检索返回明确的维度不匹配错误与"需重新摄取"提示

### Requirement: 双通道与自动回退
引擎 SHALL 提供两条进程内通道：环回 socket 通道（默认主通道）与 stdio 管道通道（备用）；主通道进程死亡或不可达时，系统 SHALL 自动回退到备用通道（惰性拉起，不常驻双 Python 进程）并保持调用成功；当前使用通道 SHALL 在健康状态中可见。

#### Scenario: 主通道故障自动回退
- **WHEN** 主通道 Python 进程被强制终止后立即发起编码请求
- **THEN** 请求经备用通道成功返回，健康状态显示当前通道为备用通道

#### Scenario: 不双开常驻进程
- **WHEN** 主通道工作正常时查询进程列表
- **THEN** 系统中只有一个 Python 引擎子进程在运行

### Requirement: 进程生命周期托管
Python 子进程 SHALL 随 Java 服务启动而拉起、随 Java 服务停止而被优雅终止（超时强杀）；任何情况下不遗留孤儿 Python 进程。

#### Scenario: Java 停止无孤儿进程
- **WHEN** engine-server 正常停止
- **THEN** 其拉起的全部 Python 子进程在限定时间内退出

### Requirement: 状态与注册表查询
引擎 SHALL 提供状态查询，返回：存活标志、当前模型与维度、真实模型是否已加载、降级标志与原因、当前通道名、模型注册表快照（未启用槽位显示为空，如图片/CLIP 槽位）。

#### Scenario: 注册表快照暴露预留槽位
- **WHEN** 查询引擎状态
- **THEN** 注册表快照包含 text-embedding 已启用规格，且 image-embedding 与 clip 槽位显示为未启用

### Requirement: stdio 通道协议纪律
stdio 通道 SHALL 以 UTF-8 JSON 行协议通信：标准输出仅承载协议行（日志一律走标准错误）、每个未知操作 SHALL 返回错误信封而非静默、每行响应写完立即刷新。

#### Scenario: 未知操作返回错误信封
- **WHEN** 通过命令行向 stdio 通道发送未知 op
- **THEN** 立即收到带错误类型与消息的 JSON 错误响应，连接保持可用
