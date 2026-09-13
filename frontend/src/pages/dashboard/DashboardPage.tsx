import { Card, Col, Row, Statistic } from 'antd'
import {
  ChromeOutlined,
  CloudServerOutlined,
  CodeOutlined,
} from '@ant-design/icons'
import { getAggregatedHealth } from '../../api/system'
import type { AggregatedHealth, EngineSection } from '../../api/types'
import { usePolling } from '../../hooks/usePolling'
import { HealthBadge } from '../../components/StatusBadge'
import './DashboardPage.css'

/**
 * 系统仪表盘 —— 全链路健康一屏可见。
 *
 * 【教学注释 · 这一页是"架构图的实时版"】
 * 三张健康卡片对应三个进程（gateway / server / Python），卡片内容
 * 直接来自网关聚合健康端点（一次请求拿全，见 gateway HealthController）。
 * 杀掉 Python 子进程后，server 周期探测会把 engine 段刷成不 ok，
 * 下一个刷新周期内卡片转红——这就是 spec 场景「故障组件可视化」。
 *
 * 【教学注释 · 轮询间隔为什么是 10 秒】
 * 与网关上游探测周期（10s）对齐：更快的页面轮询只会反复看到同一份
 * 探测缓存，不会更快发现故障。
 */
const REFRESH_INTERVAL_MS = 10_000

function DashboardPage() {
  const { data: health, error } = usePolling(getAggregatedHealth, REFRESH_INTERVAL_MS)

  return (
    <section>
      <div className="dashboard-page__header">
        <h2 className="dashboard-page__title">系统仪表盘</h2>
        <span className="dashboard-page__refresh-info">每 10 秒自动刷新</span>
      </div>

      {!!error && !health && (
        <Card size="small" style={{ marginBottom: 24 }}>
          健康数据暂不可达（网关或业务服务未启动），恢复后自动展示。
        </Card>
      )}

      {health && (
        <>
          <div className="dashboard-page__cards">
            <GatewayCard health={health} />
            <ServerCard health={health} />
            <EngineCard health={health} />
          </div>

          <DocumentStats health={health} />
          <ChainDiagram health={health} />
        </>
      )}
    </section>
  )
}

/** 网关卡片：能拿到本页数据说明网关活着（与后端的自证逻辑一致） */
function GatewayCard({ health }: { health: AggregatedHealth }) {
  const status = health.gateway.status
  return (
    <Card
      className={`dashboard-health-card dashboard-health-card--${statusClass(status)}`}
      size="small"
    >
      <div className="dashboard-health-card__name">
        <ChromeOutlined />
        网关（engine-gateway）
        <HealthBadge status={status} />
      </div>
      <div style={{ marginTop: 8, color: '#6b7280', fontSize: 12 }}>
        唯一流量入口：静态页面 + /api 反向代理（:8090）
      </div>
    </Card>
  )
}

/** 业务服务卡片：DOWN 时展示最后错误与最后成功时间（排障线索） */
function ServerCard({ health }: { health: AggregatedHealth }) {
  const { status, lastError, lastSuccessAt } = health.server
  return (
    <Card
      className={`dashboard-health-card dashboard-health-card--${statusClass(status)}`}
      size="small"
    >
      <div className="dashboard-health-card__name">
        <CloudServerOutlined />
        业务服务（engine-server）
        <HealthBadge status={status} />
      </div>
      <div style={{ marginTop: 8, color: '#6b7280', fontSize: 12 }}>
        文档摄取与混合检索（:8081，仅网关可达）
      </div>
      {status === 'DOWN' && lastError && (
        <div className="dashboard-health-card__error">探测失败：{lastError}</div>
      )}
      {lastSuccessAt && (
        <div style={{ marginTop: 4, color: '#9ca3af', fontSize: 12 }}>
          最近一次探测成功：{new Date(lastSuccessAt).toLocaleTimeString()}
        </div>
      )}
    </Card>
  )
}

/**
 * Python 引擎卡片：信息密度最高的一张——通道/模型/维度/降级原因。
 * engine 为 null 表示 server 没起过或通道未初始化（状态未知而非宕机）。
 */
function EngineCard({ health }: { health: AggregatedHealth }) {
  const engine = health.engine
  return (
    <Card
      className={`dashboard-health-card dashboard-health-card--${engine ? statusClass(engine.status) : 'unknown'}`}
      size="small"
    >
      <div className="dashboard-health-card__name">
        <CodeOutlined />
        Python 引擎
        {engine ? <HealthBadge status={engine.status} /> : <HealthBadge status={null} />}
      </div>
      {engine ? (
        <EngineDetail detail={engine.detail} status={engine.status} />
      ) : (
        <div style={{ marginTop: 8, color: '#6b7280', fontSize: 12 }}>
          尚无引擎数据（业务服务未启动或向量化通道未初始化），发起一次上传或检索后可见。
        </div>
      )}
    </Card>
  )
}

/** 引擎卡片正文：通道/模型/维度三行关键信息 + 降级/失败原因 */
function EngineDetail({ detail, status }: { detail: EngineSection; status: string }) {
  return (
    <>
      <div style={{ marginTop: 8, color: '#6b7280', fontSize: 12 }}>
        通道：{detail.channel ?? '未初始化'} · 模型：{detail.modelKey ?? '未加载'} · 维度：
        {detail.dimension ?? '-'}
      </div>
      {detail.loadedModels.length > 0 && (
        <div style={{ marginTop: 4, color: '#9ca3af', fontSize: 12 }}>
          已加载：{detail.loadedModels.join('、')}
        </div>
      )}
      {status === 'DEGRADED' && (
        <div className="dashboard-health-card__error">
          降级中：{detail.lastError ?? '真实模型不可用，检索精度受限'}
        </div>
      )}
      {status === 'DOWN' && detail.lastError && (
        <div className="dashboard-health-card__error">引擎异常：{detail.lastError}</div>
      )}
    </>
  )
}

/** 文档统计：仪表盘的"业务侧脉搏"（数据随聚合健康透传，无额外请求） */
function DocumentStats({ health }: { health: AggregatedHealth }) {
  const stats = health.documents
  return (
    <Card title="文档统计" size="small" className="dashboard-page__stats">
      {stats ? (
        <Row gutter={24}>
          <Col span={6}><Statistic title="文档总数" value={stats.total} /></Col>
          <Col span={6}><Statistic title="分块总数" value={stats.chunkTotal} /></Col>
          <Col span={6}><Statistic title="向量条目" value={stats.vectorTotal} /></Col>
          <Col span={6}>
            <Statistic
              title="降级文档"
              value={stats.degraded}
              valueStyle={stats.degraded > 0 ? { color: '#faad14' } : undefined}
            />
          </Col>
        </Row>
      ) : (
        <span style={{ color: '#6b7280', fontSize: 12 }}>业务服务不可达，统计暂缺。</span>
      )}
    </Card>
  )
}

/**
 * 调用链路示意：浏览器→网关→业务服务→Python。
 * 节点底色跟随对应组件健康状态，链路"哪一环断了"一眼可见。
 */
function ChainDiagram({ health }: { health: AggregatedHealth }) {
  const engineStatus = health.engine?.status ?? null
  const nodeColor = (status: string | null): string =>
    status === 'UP' ? '#52c41a' : status === 'DEGRADED' ? '#faad14' : status === 'DOWN' ? '#ff4d4f' : '#d1d5db'

  return (
    <Card title="请求调用链路" size="small">
      <div className="dashboard-chain">
        <div className="dashboard-chain__node">
          <span className="dashboard-chain__node-title" style={{ color: '#1677ff' }}>浏览器</span>
          <span className="dashboard-chain__node-sub">React 页面</span>
        </div>
        <span className="dashboard-chain__arrow">→</span>
        <div className="dashboard-chain__node" style={{ borderColor: nodeColor(health.gateway.status) }}>
          <span className="dashboard-chain__node-title" style={{ color: nodeColor(health.gateway.status) }}>
            网关 :8090
          </span>
          <span className="dashboard-chain__node-sub">{health.gateway.application}</span>
        </div>
        <span className="dashboard-chain__arrow">→</span>
        <div className="dashboard-chain__node" style={{ borderColor: nodeColor(health.server.status) }}>
          <span className="dashboard-chain__node-title" style={{ color: nodeColor(health.server.status) }}>
            业务服务 :8081
          </span>
          <span className="dashboard-chain__node-sub">{health.server.application}</span>
        </div>
        <span className="dashboard-chain__arrow">→</span>
        <div className="dashboard-chain__node" style={{ borderColor: nodeColor(engineStatus) }}>
          <span className="dashboard-chain__node-title" style={{ color: nodeColor(engineStatus) }}>
            Python 引擎
          </span>
          <span className="dashboard-chain__node-sub">{health.engine?.detail.channel ?? '子进程'}</span>
        </div>
      </div>
      <div style={{ marginTop: 8, color: '#9ca3af', fontSize: 12 }}>
        检索请求：页面 POST /api/search → 网关剥前缀转发 /search → server 向量化查询并混合检索 → Python 引擎负责 embedding。
      </div>
    </Card>
  )
}

/** UP/DEGRADED/DOWN → 色条 class 后缀（未知状态用 unknown 灰条） */
function statusClass(status: string | null | undefined): string {
  if (status === 'UP') return 'up'
  if (status === 'DEGRADED') return 'degraded'
  if (status === 'DOWN') return 'down'
  return 'unknown'
}

export default DashboardPage
