import { Alert } from 'antd'
import { getAggregatedHealth } from '../api/system'
import { usePolling } from '../hooks/usePolling'

/**
 * 降级全局横幅 —— 引擎处于降级模式时在所有页面顶部持续提示。
 *
 * 【教学注释 · 为什么挂在布局而不是检索页】
 * spec 要求"用户浏览任意页面"都能看到横幅——组件挂载点决定了它的
 * 生命周期：挂在 AppLayout（布局不随路由重挂载）就是全局的，挂在
 * 某个页面就只有那一页可见。这是 React Router 嵌套路由的常见考点。
 *
 * 【数据源】独立轮询聚合健康（10 秒，与网关探测周期对齐）。降级状态
 * 来自引擎段的 degraded 标志——不依赖用户发检索才发现降级。
 */
export function DegradedBanner() {
  const { data: health } = usePolling(getAggregatedHealth, 10_000)

  const engine = health?.engine
  if (!engine || engine.status !== 'DEGRADED') return null

  const reason = engine.detail.lastError ?? '真实模型不可用'
  return (
    <Alert
      type="warning"
      banner
      message="检索精度受限：向量化引擎处于降级模式"
      description={`新上传文档与检索查询将使用哈希兜底向量（原因：${reason}）。引擎恢复后本提示自动消失。`}
    />
  )
}
