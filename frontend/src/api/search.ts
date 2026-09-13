import { httpClient, unwrap } from './client'
import type { ApiEnvelope, SearchRequest, SearchResultVo } from './types'

/**
 * 混合检索 API。
 * 【教学注释】检索失败不给默认 toast——检索页要展示"错误态"卡片（spec：
 * 错误错误态），由页面自己 catch 渲染，客户端只负责解析信封。
 */
export async function search(request: SearchRequest): Promise<SearchResultVo> {
  const response = await httpClient.post<ApiEnvelope<SearchResultVo>>('/search', request)
  return unwrap<SearchResultVo>(response)
}
