# container-deployment 规格（图片跨模态检索增量）

## ADDED Requirements

### Requirement: CLIP 模型权重内置

镜像构建 SHALL 预下载 CLIP（中文图文共空间）模型权重并烘焙进模型层，下载源与既有模型一致（hf-mirror）。运行期 MUST 完全离线可用：容器启动与图片向量化均不触发任何外网下载。镜像体积约束 SHALL 随模型清单同步更新（预计增加约 400MB）。

#### Scenario: 离线环境图片向量化不降级

- **WHEN** 容器在无外网环境启动，用户上传图片触发 CLIP 向量化
- **THEN** 向量化使用镜像内置权重完成，不发生网络下载，也不进入哈希降级

#### Scenario: 模型层缓存有效复用

- **WHEN** 仅修改 Java/前端代码重新构建镜像
- **THEN** 模型权重层命中缓存不重复下载，仅重建变更层
