import type { AxiosProgressEvent } from 'axios'
import { httpClient, uploadClient, unwrap, notifyError } from './client'
import type { DocumentDetailVo, DocumentPage, DocumentSummary } from './types'

/**
 * 文档管理 API —— 摄取全流程的接口封装。
 * 【教学注释 · API 层的粒度】一个后端端点一个函数、函数名 = 用户意图，
 * 页面代码读起来是"做什么"而不是"怎么发请求"。
 */

/** 上传文档（multipart）。onProgress 驱动上传进度条 */
export async function uploadDocument(
  file: File,
  onProgress?: (percent: number) => void,
): Promise<DocumentSummary> {
  const form = new FormData()
  form.append('file', file)
  try {
    const response = await uploadClient.post('/documents', form, {
      headers: { 'Content-Type': 'multipart/form-data' },
      onUploadProgress: (event: AxiosProgressEvent) => {
        if (onProgress && event.total) {
          onProgress(Math.round((event.loaded / event.total) * 100))
        }
      },
    })
    return unwrap<DocumentSummary>(response)
  } catch (error) {
    notifyError(error, '上传失败')
    throw error
  }
}

/** 分页文档列表（page 参数 1-based：UI 页码直接传入，内部转为后端 0-based） */
export async function listDocuments(page = 1, size = 20): Promise<DocumentPage> {
  // 【契约对齐】后端 page 从 0 起（PageResult 注释：与 Spring Data 对齐），
  // antd Pagination 与用户心智都是 1-based——API 层是唯一该做换算的地方
  const response = await httpClient.get('/documents', { params: { page: Math.max(0, page - 1), size } })
  return unwrap<DocumentPage>(response)
}

/** 文档详情（含分块文本、模型、维度、降级原因） */
export async function getDocument(id: string): Promise<DocumentDetailVo> {
  const response = await httpClient.get(`/documents/${id}`)
  return unwrap<DocumentDetailVo>(response)
}

/** 删除文档（向量/索引/文件联动清理） */
export async function deleteDocument(id: string): Promise<void> {
  try {
    await httpClient.delete(`/documents/${id}`)
  } catch (error) {
    notifyError(error, '删除失败')
    throw error
  }
}
