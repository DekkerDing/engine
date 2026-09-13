/**
 * 后端 API 类型定义 —— 与 server DTO 一一对应（字段契约见 server 侧注释）。
 *
 * 【教学注释 · 类型即契约】
 * 这些 interface 是前后端之间的"纸质合同"：后端改字段名/类型，这里不同步
 * 改，编译期不报错（HTTP 是运行时边界），但页面会显示 undefined——
 * 所以改后端 DTO 必须同步改这里，两边文件注释里都写了对应关系。
 */

/** 统一响应信封（server/gateway 的 ApiResponse 同构） */
export interface ApiEnvelope<T> {
  code: number        // 0 成功；1xxx 参数 / 2xxx 领域 / 3xxx Python 下游 / 5xxx 系统
  message: string     // 人类可读信息（失败时给用户看）
  data: T | null
  timestamp: string
  traceId: string | null
}

/** 文档状态机（后端 DocumentStatus 枚举的字符串形式） */
export type DocumentStatus =
  | 'PENDING'    // 已上传等待处理
  | 'PARSING'    // 解析中
  | 'CHUNKING'   // 分块中
  | 'VECTORIZING'// 向量化中
  | 'COMPLETED'  // 全部完成可检索
  | 'FAILED'     // 失败（errorMessage 可查原因）

/** 文档摘要（列表项/上传响应） */
export interface DocumentSummary {
  id: string
  filename: string
  status: DocumentStatus
  statusLabel: string       // 后端给的中文状态标签（前端徽标直接用）
  sizeBytes: number         // 文件大小（字节，列表展示用）
  chunkCount: number
  vectorizedCount: number
  degraded: boolean
  errorMessage: string | null
  createdAt: string
  updatedAt: string
}

/** 分页响应（GET /documents） */
export interface DocumentPage {
  items: DocumentSummary[]
  total: number
  page: number
  size: number
}

/** 文本块（详情接口） */
export interface DocumentChunk {
  chunkIndex: number
  text: string
}

/** 文档详情（GET /documents/{id}） */
export interface DocumentDetailVo {
  document: DocumentSummary
  chunks: DocumentChunk[]
  modelKey: string | null    // 向量化模型键（如 text-embedding-zh）
  dimension: number | null   // 向量维度（512/384）
  degradedReason: string | null
}

/** 图片状态机（后端 ImageStatus 枚举的字符串形式）——比文档少两态：无 PARSING/CHUNKING */
export type ImageStatus =
  | 'PENDING'    // 已上传等待向量化
  | 'VECTORIZING'// CLIP 编码中
  | 'COMPLETED'  // 可检索
  | 'FAILED'     // 失败（errorMessage 可查原因）

/**
 * 图片摘要（列表项/上传响应）——与 DocumentSummary 同构但无分块/进度字段。
 *
 * 【教学注释 · 标注三态】photo-semantic-search 后每张图附带 VLM 拆分结果：
 * annotationMocked 是三态语义——null=尚未拆分（存量图/处理中，UI 显示"未拆分"
 * 占位）、true=mock 标注（UI 显示"模拟识别"徽标）、false=真实 VLM 产出。
 * subject/description 恒有值（未拆分时空串），UI 以 annotationMocked 为准切换展示。
 */
export interface ImageSummary {
  id: string
  filename: string
  status: ImageStatus
  statusLabel: string       // 后端给的中文状态标签
  sizeBytes: number
  degraded: boolean
  errorMessage: string | null
  subject: string                  // 标注主题（如"花"；未拆分为空串）
  description: string              // 标注描述（如"红色的小花"；未拆分为空串）
  tags: string[]                   // 标注标签（未拆分为空数组）
  annotationMocked: boolean | null // 三态：null=未拆分 / true=模拟 / false=真实
  annotationError: string | null   // 标注失败原因（不阻塞入库，可重试语义）
  createdAt: string
  updatedAt: string
}

/** 图片分页响应（GET /images） */
export interface ImagePage {
  items: ImageSummary[]
  total: number
  page: number
  size: number
}

/** 图片详情（GET /images/{id}）——图片无分块列表，元数据 + 模型信息 */
export interface ImageDetailVo {
  image: ImageSummary
  modelKey: string | null    // CLIP 模型键（clip）
  dimension: number | null   // 向量维度（512）
  degradedReason: string | null
}

/** 批量导入文件明细状态（后端 ImageImportTask.FileState 枚举） */
export type ImageImportFileState = 'REJECTED' | 'COMPLETED' | 'FAILED'

/** 批量导入单文件明细 */
export interface ImageImportFileItemVo {
  filename: string
  state: ImageImportFileState
  imageId: string | null     // 被拒文件不建档为 null；进管线失败的文件有 id
  reason: string | null      // 拒绝原因/失败原因
  duplicateSuspected: boolean // 同名同大小疑似重复（提示不阻断）
}

/** 批量导入任务（受理回执与进度轮询共用）——status RUNNING/COMPLETED */
export interface ImageImportTaskVo {
  taskId: string
  status: 'RUNNING' | 'COMPLETED' // 文件级失败是明细不是任务失败（部分失败不阻断）
  total: number               // 入队数（不含受理期拒绝）
  processed: number           // 已到终态数（进度条分子）
  succeeded: number
  failed: number
  rejected: number
  createdAt: string
  finishedAt: string | null   // 未完成为 null
  files: ImageImportFileItemVo[] // 终态明细（含拒绝条目；在途文件暂不出现）
}

/** 命中来源（后端 SearchHit.Source 枚举）：SEMANTIC 语义 / FULLTEXT 全文 / BOTH 双路命中 */
export type SearchSource = 'SEMANTIC' | 'FULLTEXT' | 'BOTH'

/** 命中条目的资源模态：text 文本片段 / image 图片 */
export type SearchModality = 'text' | 'image'

/** 单条检索命中 */
export interface SearchHitVo {
  documentId: string        // 文档 ID 或图片资产 ID（按 sourceType 解释）
  documentName: string      // 文档名或图片文件名
  chunkIndex: number
  snippet: string | null    // 命中片段原文（图片命中无片段，为 null）
  score: number             // RRF 融合分（双路命中）/ 单路直通的余弦或融合序分
  vectorScore: number       // 语义余弦分
  textScore: number | null  // BM25 全文分（图片模态单路，为 null）
  source: SearchSource      // 来源标注（图片双路命中为 BOTH）
  highlight: string | null  // 带 <em> 高亮标记的片段（图片命中为 null）
  degraded: boolean         // 向量化时引擎是否降级（哈希兜底）
  sourceType: SearchModality // 资源模态标注（text / image）
  subject: string                  // 图片命中：标注主题（未标注为空串）
  description: string              // 图片命中：标注描述（未标注为空串）
  tags: string[]                   // 图片命中：标注标签
  annotationMocked: boolean | null // 图片命中三态：null=未拆分 / true=模拟 / false=真实
  rerankScore: number | null       // 重排分（未参与重排/未标注候选为 null）
}

/** 检索请求 */
export interface SearchRequest {
  query: string
  topK?: number
  modality?: SearchModality // 缺省 text（既有客户端零改动）；image 走 CLIP 跨模态
  rerank?: boolean          // 重排开关（仅 image 模态生效；缺省用服务端配置=开）
  rerankCandidates?: number // 送重排的候选池大小（缺省 20，上限 50）
}

/** 检索响应（POST /search） */
export interface SearchResultVo {
  query: string
  items: SearchHitVo[]
  total: number
  tookMs: number
  degraded: boolean
  degradedReason: string | null
  cached: boolean
  modality: SearchModality  // 本次检索实际使用的模态（回显）
  reranked: boolean         // 本次结果是否经过重排（image 模态；降级直通为 false）
  rerankReason: string | null // 未重排原因（重排关闭/不可用/候选无标注等）
}

/** Python 引擎段（server /system/health 的 engine 段） */
export interface EngineSection {
  ok: boolean
  degraded: boolean
  channel: string | null       // py4j / stdio
  loadedModels: string[]
  dimension: number | null
  lastError: string | null
  modelKey: string | null
}

/** 文档统计段 */
export interface DocumentsStat {
  total: number
  byStatus: Record<string, number>
  chunkTotal: number
  vectorTotal: number
  degraded: number
}

/** 网关聚合健康（GET /api/system/health 的 data） */
export interface AggregatedHealth {
  status: 'UP' | 'DEGRADED' | 'DOWN'   // 整链路判定
  gateway: { application: string; status: string }
  server: {
    application: string
    status: string
    lastError: string | null
    lastSuccessAt: string | null
  }
  engine: { status: string; detail: EngineSection } | null
  documents: DocumentsStat | null
}

// ============================================================
// 需求工厂（requirement-forge）—— 表单 → 规范 md 工件
// ------------------------------------------------------------
// 【契约来源】openspec/changes/requirement-forge design D6（冻结契约）；
// 与后端 RequirementForm / RequirementDto 一一映射，改动需双向同步。
// ============================================================

/** 需求状态机：草稿 → 已提交（过闸门）→ 已导出；编辑保存回草稿并过期全部工件 */
export type RequirementStatus = 'DRAFT' | 'SUBMITTED' | 'EXPORTED'

/** 渲染目标：openspec 变更包（多文件 zip）/ vibecoding 单文档（TASK.md） */
export type RenderTarget = 'openspec' | 'vibecoding'

/** 第一步：基本信息 */
export interface RequirementBasicInfo {
  title: string
  submitter: string
  department: string
  priority: 'HIGH' | 'MEDIUM' | 'LOW'
  expectDate: string
  capabilitySlug: string
}

/** 第二步：业务背景 */
export interface RequirementBackground {
  painPoints: string[]
  impactScope: string
  metrics: string[]
}

/** 期望功能：一条 = 一个用户故事（作为 as / 想要 want / 以便 so） */
export interface RequirementFeature {
  userStory: { as: string; want: string; so: string }
  details: string
}

/** 验收标准（WHEN/THEN，渲染时原样保留为结构化场景） */
export interface RequirementAcceptance {
  when: string
  then: string
}

/** 第三步：约束条件 */
export interface RequirementConstraints {
  technical: string[]
  compliance: string[]
}

/** 三步表单全量数据（与后端 RequirementForm 值对象同构） */
export interface RequirementFormVo {
  basic: RequirementBasicInfo
  background: RequirementBackground
  features: RequirementFeature[]
  acceptance: RequirementAcceptance[]
  constraints: RequirementConstraints
}

/** 列表项（列表页卡片/表格所需最小字段集） */
export interface RequirementSummary {
  id: string
  title: string
  status: RequirementStatus
  priority: string
  submitter: string
  department: string
  capabilitySlug: string
  updatedAt: string
}

/** 分页列表信封（PageResult 同构） */
export interface RequirementPage {
  items: RequirementSummary[]
  total: number
  page: number
  size: number
}

/** 附件元数据（文件本体经下载端点获取） */
export interface AttachmentVo {
  id: number
  fileName: string
  contentType: string
  sizeBytes: number
  createdAt: string
}

/** 工件索引项（工坊/导出页的版本列表） */
export interface ArtifactSummaryVo {
  target: RenderTarget
  version: number
  stale: boolean
  templateVersion: string
  hasRevision: boolean
  fileNames: string[]
  createdAt: string
}

/** 需求详情（聚合 + 附件 + 工件索引） */
export interface RequirementDetailVo {
  summary: RequirementSummary
  form: RequirementFormVo
  attachments: AttachmentVo[]
  artifacts: ArtifactSummaryVo[]
}

/** 工件内容：files=当前有效（修订优先）；templateOutput=模板基准（diff 用） */
export interface ArtifactContentVo {
  target: RenderTarget
  version: number
  stale: boolean
  templateVersion: string
  files: Record<string, string>
  templateOutput: Record<string, string>
}
