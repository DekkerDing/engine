import type { AxiosProgressEvent } from 'axios'
import { httpClient, uploadClient, unwrap, notifyError } from './client'
import type { ImageDetailVo, ImageImportTaskVo, ImagePage, ImageSummary } from './types'

/**
 * 图片管理 API —— 与 documents.ts 一一对应的接口封装
 * （一个端点一个函数、函数名 = 用户意图）。
 *
 * 【教学注释 · 原图 URL 为什么是个纯函数而不是请求】GET /images/{id}/file
 * 返回的是二进制流（不走 ApiResponse 信封），axios 包装没有意义——
 * <img src> 天生就是"发 GET 收二进制"的客户端，直接把 URL 给它。
 * 开发态走 vite 代理、生产态同源，与 httpClient 的 baseURL 逻辑一致。
 */

/** 原图访问地址（<img src> / 预览组件直接用；inline 展示非下载） */
export function imageUrl(id: string): string {
  return `/api/images/${id}/file`
}

/**
 * 上传图片（multipart）。onProgress 驱动上传进度条。
 * caption 可选（photo-semantic-search）：给这张图一句描述，标注阶段
 * caption 优先于文件名派生——"我对这张图的理解"比"IMG_20240501.jpg"信息量大。
 */
export async function uploadImage(
  file: File,
  caption?: string,
  onProgress?: (percent: number) => void,
): Promise<ImageSummary> {
  const form = new FormData()
  form.append('file', file)
  if (caption && caption.trim()) {
    form.append('caption', caption.trim())
  }
  try {
    const response = await uploadClient.post('/images', form, {
      headers: { 'Content-Type': 'multipart/form-data' },
      onUploadProgress: (event: AxiosProgressEvent) => {
        if (onProgress && event.total) {
          onProgress(Math.round((event.loaded / event.total) * 100))
        }
      },
    })
    return unwrap<ImageSummary>(response)
  } catch (error) {
    notifyError(error, '上传失败')
    throw error
  }
}

/**
 * 批量导入（photo-semantic-search 任务 5.1）：multipart 多文件一次提交，
 * 受理即回（毫秒级回执，不等标注+双向量化执行完）——千张导入不会撞 HTTP 超时。
 *
 * 【教学注释 · 受理与执行分离】上传耗时（网络）与导入耗时（模型计算分钟级）
 * 是两段时间：这里只等"受理回执"（taskId），执行进度交给 getImportTask 轮询。
 * 后端有整单防线（文件数/体量超限 400 整单拒绝），拒错会在 catch 里弹通知。
 */
export async function uploadImagesBatch(
  files: File[],
  onProgress?: (percent: number) => void,
): Promise<ImageImportTaskVo> {
  const form = new FormData()
  files.forEach((file) => form.append('files', file))
  try {
    const response = await uploadClient.post('/images/batch', form, {
      headers: { 'Content-Type': 'multipart/form-data' },
      onUploadProgress: (event: AxiosProgressEvent) => {
        if (onProgress && event.total) {
          onProgress(Math.round((event.loaded / event.total) * 100))
        }
      },
    })
    return unwrap<ImageImportTaskVo>(response)
  } catch (error) {
    notifyError(error, '批量导入受理失败')
    throw error
  }
}

/** 批量导入任务进度/明细轮询（完成态后端保留 24h，过期 404） */
export async function getImportTask(taskId: string): Promise<ImageImportTaskVo> {
  const response = await httpClient.get(`/images/import-tasks/${taskId}`)
  return unwrap<ImageImportTaskVo>(response)
}

/** 图片分页列表（page 参数 1-based：UI 页码直接传入，内部转为后端 0-based；status 缺省查全部） */
export async function listImages(
  page = 1,
  size = 60,
  status?: string,
): Promise<ImagePage> {
  // 【契约对齐】后端 page 从 0 起（与 documents 同一契约），API 层统一换算
  const zeroBased = Math.max(0, page - 1)
  const response = await httpClient.get('/images', {
    params: status ? { page: zeroBased, size, status } : { page: zeroBased, size },
  })
  return unwrap<ImagePage>(response)
}

/** 图片详情（模型/维度/降级原因） */
export async function getImage(id: string): Promise<ImageDetailVo> {
  const response = await httpClient.get(`/images/${id}`)
  return unwrap<ImageDetailVo>(response)
}

/** 删除图片（向量/原件/记录联动清理） */
export async function deleteImage(id: string): Promise<void> {
  try {
    await httpClient.delete(`/images/${id}`)
  } catch (error) {
    notifyError(error, '删除失败')
    throw error
  }
}
