import axios, { AxiosError, type AxiosResponse } from 'axios'
import { message } from 'antd'
import type { ApiEnvelope } from './types'

/**
 * 统一 API 客户端 —— 全应用唯一的 HTTP 出口。
 *
 * 【教学注释 · 为什么所有请求都要走这一个实例】
 * 信封解析/超时/错误提示只写一次，所有页面共享。如果各页面各写各的
 * fetch，错误处理行为必然漂移（有的 toast、有的 console、有的静默）。
 *
 * 【两层错误模型】
 * 1. HTTP 层失败（网络断/超时/5xx）：axios 抛错 → 拦截器统一 toast；
 * 2. 业务层失败（HTTP 200 但 code≠0，如参数错误）：响应拦截器主动抛
 *    ApiError——对调用方来说两种失败"长得一样"，都走 try-catch。
 */

/** 业务错误：信封 code≠0 时抛出（message 来自后端，直接可展示） */
export class ApiError extends Error {
  readonly code: number
  readonly httpStatus?: number

  constructor(code: number, msg: string, httpStatus?: number) {
    super(msg)
    this.name = 'ApiError'
    this.code = code
    this.httpStatus = httpStatus
  }
}

/**
 * axios 实例：
 * - baseURL '/api'：开发态由 vite 代理转发到网关，生产态与网关同源——
 *   两种形态代码零改动（这也是"产物不含后端绝对地址"的实现方式）；
 * - 超时 15 秒：普通接口（列表/健康/检索）的安全上限。上传走独立实例，
 *   大文件 + 摄取受理不该被这 15 秒误杀。
 */
export const httpClient = axios.create({
  baseURL: '/api',
  timeout: 15000,
})

/** 上传专用实例：60 秒（multipart 传输 + 网关转发 + 受理落盘） */
export const uploadClient = axios.create({
  baseURL: '/api',
  timeout: 60000,
})

/**
 * 响应信封解析：HTTP 200 但 code≠0 视为业务失败，抛 ApiError。
 * 网关自身错误（如 502 上游不可达）也返回同构信封，同一逻辑覆盖。
 */
export function unwrap<T>(response: AxiosResponse<ApiEnvelope<T>>): T {
  const envelope = response.data
  if (envelope.code !== 0 || envelope.data === null) {
    throw new ApiError(envelope.code, envelope.message || '未知错误', response.status)
  }
  return envelope.data
}

/** 把任意失败转成用户友好的提示文案（toast 用） */
function humanizeError(error: unknown): string {
  if (error instanceof ApiError) {
    // 按错误码分段给出行动指引（design.md D9：前端按段位决定交互）
    if (error.code >= 1000 && error.code < 2000) return `参数错误：${error.message}`
    if (error.code >= 3000 && error.code < 4000) return `检索引擎暂不可用：${error.message}`
    if (error.code >= 5000) return `服务异常：${error.message}`
    return error.message
  }
  if (error instanceof AxiosError) {
    if (error.code === 'ECONNABORTED') return '请求超时，请稍后重试'
    if (!error.response) return '网络不可达，请检查服务是否启动'
    const serverMsg = (error.response.data as ApiEnvelope<unknown> | undefined)?.message
    return serverMsg ?? `服务异常（HTTP ${error.response.status}）`
  }
  return '未知错误，请重试'
}

/** 统一错误处理：toast + 向调用方抛出（页面可继续 catch 做局部处理） */
export function notifyError(error: unknown, context = '') {
  const prefix = context ? `${context}：` : ''
  message.error(`${prefix}${humanizeError(error)}`)
}

// ---------- 拦截器装配（两个实例同一套行为） ----------
for (const client of [httpClient, uploadClient]) {
  client.interceptors.response.use(
    (response) => response,
    (error: unknown) => {
      // HTTP 层错误（含网关 5xx 信封）在这里统一 toast；
      // 业务层 code≠0 的 toast 交给各 API 函数（有页面上下文前缀）
      if (error instanceof AxiosError) {
        message.error(humanizeError(error))
      }
      return Promise.reject(error)
    },
  )
}
