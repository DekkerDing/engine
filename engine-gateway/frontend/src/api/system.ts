import { httpClient, unwrap } from './client'
import type { AggregatedHealth } from './types'

/**
 * 系统健康 API —— 仪表盘三卡片的数据源（网关聚合版）。
 */
export async function getAggregatedHealth(): Promise<AggregatedHealth> {
  const response = await httpClient.get('/system/health')
  return unwrap<AggregatedHealth>(response)
}
