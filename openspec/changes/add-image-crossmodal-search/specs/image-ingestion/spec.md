# image-ingestion 规格（图片跨模态检索增量）

## Purpose

让用户上传图片（照片），经 CLIP 图文共空间模型向量化后成为可被文本查询检索的多模态资源：一图一向量、状态可跟踪、原件可追溯、降级有标志。

## ADDED Requirements

### Requirement: 图片上传与校验

系统 SHALL 提供图片上传端点，接受 jpg/jpeg/png/webp/bmp/gif 格式。系统 MUST 按文件魔数（而非仅扩展名）校验格式，MUST 拒绝超过配置大小上限的文件，MUST 拒绝白名单之外的格式，并返回统一错误码与明确原因。

#### Scenario: 上传合法图片被接受

- **WHEN** 用户上传一张真实 jpg 照片且大小在上限内
- **THEN** 系统接受上传，立即返回该图片资源的唯一 ID，摄取异步开始

#### Scenario: 假扩展名被魔数校验拒绝

- **WHEN** 用户上传一个把 `.exe` 改名为 `.jpg` 的文件
- **THEN** 系统拒绝上传并返回格式非法错误，不落盘、不进入摄取管线

#### Scenario: 超过大小上限被拒绝

- **WHEN** 用户上传超过配置上限（默认 20MB）的图片
- **THEN** 系统拒绝上传并返回大小超限错误与上限值

### Requirement: 一图一向量

图片摄取 SHALL 将每张图片作为单个资源处理：不切块，整图经 CLIP 图片编码器生成恰好一条向量记录，模态标记为 IMAGE。

#### Scenario: 摄取完成后恰有一条向量

- **WHEN** 一张图片完成摄取
- **THEN** 向量库中该图片对应且仅对应一条 IMAGE 模态的向量记录，可按资源 ID 反查

### Requirement: 图片摄取状态机

图片摄取 SHALL 复用与文档一致的异步状态机（UPLOADED → … → INDEXED / FAILED），状态与错误信息 SHALL 可经进度查询端点轮询。

#### Scenario: 上传后异步完成向量化

- **WHEN** 图片上传成功且向量化顺利
- **THEN** 轮询可见状态从 UPLOADED 推进到 INDEXED

#### Scenario: 向量化失败可追溯

- **WHEN** 图片向量化过程出错（如图片数据损坏无法解码）
- **THEN** 状态推进到 FAILED，错误原因可经详情查询获得，且不阻塞其他图片的摄取

### Requirement: 图片存储与联动清理

系统 SHALL 将图片原件持久化于独立的数据目录（与文档目录同构）。删除图片资源时 MUST 联动清理向量记录、原件文件与缓存。

#### Scenario: 删除图片联动清理

- **WHEN** 用户删除一张已摄取的图片
- **THEN** 该图片的向量记录与原件文件均被移除，后续检索不再命中该图片

### Requirement: 图片向量化降级标记

当 CLIP 模型不可用时，系统 SHALL 以确定性哈希向量完成摄取并标记 `degraded=true`；MUST NOT 在无标志的情况下静默使用假向量。

#### Scenario: 模型不可用时降级摄取

- **WHEN** CLIP 模型加载失败（如离线且无缓存）时用户上传图片
- **THEN** 摄取仍完成（INDEXED），但资源与后续检索响应均携带 degraded 标志
