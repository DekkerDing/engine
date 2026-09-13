import { httpClient, unwrap, notifyError } from './client'
import type {
  ArtifactContentVo,
  ArtifactSummaryVo,
  AttachmentVo,
  RenderTarget,
  RequirementDetailVo,
  RequirementFormVo,
  RequirementPage,
  RequirementStatus,
  RequirementSummary,
} from './types'

/**
 * 需求工厂 API —— design D6 冻结契约的封装。
 * 【契约对齐】page 1-based → 0-based 换算只在这里做（沿 documents.ts 惯例）。
 */

/** 创建草稿（部分字段即可——分步表单第一步暂存就会调用） */
export async function createRequirement(form: RequirementFormVo): Promise<RequirementSummary> {
  try {
    const response = await httpClient.post('/requirements', form)
    return unwrap<RequirementSummary>(response)
  } catch (error) {
    notifyError(error, '创建需求失败')
    throw error
  }
}

/** 保存编辑（后端联动：状态回 DRAFT + 全部工件过期） */
export async function saveRequirement(id: string, form: RequirementFormVo): Promise<RequirementDetailVo> {
  try {
    const response = await httpClient.put(`/requirements/${id}`, form)
    return unwrap<RequirementDetailVo>(response)
  } catch (error) {
    notifyError(error, '保存失败')
    throw error
  }
}

/** 分页列表（status/priority/q 均可选；q 为标题关键字） */
export async function listRequirements(options: {
  page?: number
  size?: number
  status?: RequirementStatus | null
  priority?: string | null
  q?: string | null
}): Promise<RequirementPage> {
  const params: Record<string, string | number> = {
    page: Math.max(0, (options.page ?? 1) - 1),
    size: options.size ?? 20,
  }
  if (options.status) params.status = options.status
  if (options.priority) params.priority = options.priority
  if (options.q) params.q = options.q
  const response = await httpClient.get('/requirements', { params })
  return unwrap<RequirementPage>(response)
}

/** 详情（表单回填 + 附件 + 工件索引） */
export async function getRequirement(id: string): Promise<RequirementDetailVo> {
  const response = await httpClient.get(`/requirements/${id}`)
  return unwrap<RequirementDetailVo>(response)
}

/** 提交（过完整性闸门；422 时 message 携带逐项缺失清单） */
export async function submitRequirement(id: string): Promise<RequirementSummary> {
  try {
    const response = await httpClient.post(`/requirements/${id}/submit`)
    return unwrap<RequirementSummary>(response)
  } catch (error) {
    notifyError(error, '提交未通过完整性闸门')
    throw error
  }
}

/** 渲染（目标 openspec/vibecoding；产新版本） */
export async function renderRequirement(id: string, target: RenderTarget): Promise<ArtifactSummaryVo> {
  try {
    const response = await httpClient.post(`/requirements/${id}/render`, { target })
    return unwrap<ArtifactSummaryVo>(response)
  } catch (error) {
    notifyError(error, '渲染失败')
    throw error
  }
}

/** 工件版本列表（最新在后） */
export async function listArtifacts(id: string): Promise<ArtifactSummaryVo[]> {
  const response = await httpClient.get(`/requirements/${id}/artifacts`)
  return unwrap<ArtifactSummaryVo[]>(response)
}

/** 工件内容（files=当前有效；templateOutput=diff 基准） */
export async function getArtifact(id: string, version: number, target: RenderTarget): Promise<ArtifactContentVo> {
  const response = await httpClient.get(`/requirements/${id}/artifacts/${version}`, { params: { target } })
  return unwrap<ArtifactContentVo>(response)
}

/** 保存人工修订（整体覆盖；过期工件后端 409 拒绝） */
export async function saveRevision(
  id: string,
  version: number,
  target: RenderTarget,
  files: Record<string, string>,
): Promise<ArtifactSummaryVo> {
  try {
    const response = await httpClient.put(`/requirements/${id}/artifacts/${version}`, { target, files })
    return unwrap<ArtifactSummaryVo>(response)
  } catch (error) {
    notifyError(error, '保存修订失败')
    throw error
  }
}

/** 上传附件（png/jpg/jpeg/pdf/docx 白名单 + 20MB，后端魔数校验） */
export async function uploadAttachment(id: string, file: File): Promise<AttachmentVo> {
  const form = new FormData()
  form.append('file', file)
  try {
    const response = await httpClient.post(`/requirements/${id}/attachments`, form, {
      headers: { 'Content-Type': 'multipart/form-data' },
    })
    return unwrap<AttachmentVo>(response)
  } catch (error) {
    notifyError(error, '附件上传失败')
    throw error
  }
}

/** 导出下载地址（浏览器直接打开触发下载；不走 httpClient——响应是二进制流） */
export function exportUrl(id: string, target: RenderTarget): string {
  return `/api/requirements/${id}/export?target=${target}`
}
