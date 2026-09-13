## Purpose

规范渲染能力：将 RequirementIR 通过确定性模板引擎渲染为多目标 md 工件（OpenSpec 兼容变更包与 VibeCoding 范式文档），并提供预览、人工编辑、版本化与导出。

## ADDED Requirements

### Requirement: 双目标渲染

系统 SHALL 支持对同一 RequirementIR 渲染两种目标产物：`openspec` 目标产出 OpenSpec 兼容变更包，`vibecoding` 目标产出 AGENTS.md 风格的单一规范文档。两种产物 MUST 语义一致（同一需求的事实、约束、验收标准不矛盾）。

#### Scenario: 双目标渲染同一需求
- **WHEN** 对一个已通过完整性闸门的需求分别以 openspec 与 vibecoding 目标发起渲染
- **THEN** 产出各自的 md 工件，两者的验收标准条目一一对应且表述一致

### Requirement: 渲染确定性

渲染 MUST 是确定性的：同一 RequirementIR 与同一模板版本组合，任意时刻重复渲染 MUST 产出字节级相同的内容。渲染产物 MUST 记录所用模板版本。

#### Scenario: 重复渲染一致性
- **WHEN** 同一需求在模板版本不变的情况下连续渲染两次并比较产物字节
- **THEN** 两次产物完全相同

### Requirement: OpenSpec 变更包结构

`openspec` 目标的产物 SHALL 为目录结构的变更包：`proposal.md`（Why/What Changes/Impact）、`tasks.md`（可勾选实施清单）、`specs/<capability>/spec.md`（`### Requirement` + `#### Scenario` WHEN/THEN 结构）。产物 MUST 可被 openspec CLI 识别为合法变更。

#### Scenario: 产物合法性
- **WHEN** 将导出的 openspec 变更包放入目标仓库的 changes 目录并执行校验
- **THEN** 校验通过，变更出现在变更列表中且状态为可实施

### Requirement: VibeCoding 范式结构

`vibecoding` 目标的产物 SHALL 为单一 md 文档，包含固定章节：任务目标、业务背景、需求详述（含用户故事）、约束与边界、验收标准、实施注意事项、附件引用清单。验收标准章节 MUST 保留 WHEN/THEN 结构化表述。

#### Scenario: 章节完整性
- **WHEN** 渲染 vibecoding 目标产物
- **THEN** 文档包含全部固定章节，且验收标准以 WHEN/THEN 列表呈现

### Requirement: 工件版本化与人工编辑

每次渲染 SHALL 产生新的工件版本（版本号单调递增）。用户 SHALL 能对最新版本进行人工编辑；人工编辑产生该版本的修订，修订 MUST 可与模板原始输出比对差异。导出时以最新的人工修订为准，无修订则以模板输出为准。

#### Scenario: 修订后以修订导出
- **WHEN** 用户在模板输出的第 3 版工件上修改了任务目标章节并导出
- **THEN** 导出内容包含该修改，且能查看与第 3 版模板原始输出的差异

### Requirement: 过期标记

当需求的 RequirementIR 发生任何变更（表单编辑保存）时，该需求全部既有工件 MUST 立即标记为「已过期」。过期工件可查看但导出时 MUST 给出过期警示。

#### Scenario: 过期警示
- **WHEN** 用户尝试导出一个已被标记过期的工件
- **THEN** 导出确认提示中明确警示该工件基于旧版需求，建议先重新渲染

### Requirement: 导出

系统 SHALL 支持导出：`openspec` 目标导出为保持目录结构的 zip 包（含附件），`vibecoding` 目标导出为单 md 文件（附件可选随 zip）。导出动作 MUST 记录导出时间并将需求状态推进为「已导出」。

#### Scenario: zip 导出
- **WHEN** 用户对 openspec 目标最新工件执行导出
- **THEN** 下载得到 zip 包，解压后目录结构与渲染产物一致且包含附件，需求状态变为「已导出」
