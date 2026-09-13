import { Tag } from 'antd'
import type { DocumentStatus, ImageStatus, RequirementStatus } from '../api/types'

/**
 * 状态徽标 —— 全站统一的状态颜色约定（docs/frontend-standards.md 状态徽标约定）。
 *
 * 【教学注释 · 为什么颜色映射要收敛到一个组件】
 * "绿色=健康/完成、橙色=降级/部分异常、红色=失败/宕机"是全站语义。
 * 散落在各页面各写一版，很快就会出现"同样是 FAILED 一页红一页灰"。
 */

/** 组件健康状态（UP/DEGRADED/DOWN + 中文标签） */
export function HealthBadge({ status }: { status: string | null | undefined }) {
  if (status === 'UP') return <Tag color="success">正常</Tag>
  if (status === 'DEGRADED') return <Tag color="warning">降级</Tag>
  if (status === 'DOWN') return <Tag color="error">宕机</Tag>
  return <Tag>未知</Tag>
}

/** 文档状态徽标（后端 statusLabel 提供中文文案，这里只管颜色） */
export function DocumentStatusBadge({ status, label }: { status: DocumentStatus; label: string }) {
  const colorMap: Record<DocumentStatus, string> = {
    PENDING: 'default',
    PARSING: 'processing',
    CHUNKING: 'processing',
    VECTORIZING: 'processing',
    COMPLETED: 'success',
    FAILED: 'error',
  }
  return <Tag color={colorMap[status]}>{label}</Tag>
}

/** 图片状态徽标 —— 颜色语义与文档一致（绿=完成/橙=进行中/红=失败） */
export function ImageStatusBadge({ status, label }: { status: ImageStatus; label: string }) {
  const colorMap: Record<ImageStatus, string> = {
    PENDING: 'default',
    VECTORIZING: 'processing',
    COMPLETED: 'success',
    FAILED: 'error',
  }
  return <Tag color={colorMap[status]}>{label}</Tag>
}

/** 需求状态徽标（需求工厂）：蓝=草稿、紫=已提交、绿=已导出 */
export function RequirementStatusBadge({ status }: { status: RequirementStatus }) {
  const colorMap: Record<RequirementStatus, string> = {
    DRAFT: 'blue',
    SUBMITTED: 'purple',
    EXPORTED: 'success',
  }
  const labelMap: Record<RequirementStatus, string> = {
    DRAFT: '草稿',
    SUBMITTED: '已提交',
    EXPORTED: '已导出',
  }
  return <Tag color={colorMap[status]}>{labelMap[status]}</Tag>
}

/** 优先级徽标：红=HIGH、橙=MEDIUM、灰=LOW（渐弱色阶呼应轻重） */
export function PriorityBadge({ priority }: { priority: string }) {
  if (priority === 'HIGH') return <Tag color="error">高</Tag>
  if (priority === 'MEDIUM') return <Tag color="warning">中</Tag>
  return <Tag>低</Tag>
}
