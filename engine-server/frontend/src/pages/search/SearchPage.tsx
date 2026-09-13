import { useMemo, useState } from 'react'
import { Alert, Button, Card, Empty, Input, Space, Spin, Tag } from 'antd'
import { SearchOutlined } from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import { search } from '../../api/search'
import type { SearchResultVo, SearchSource } from '../../api/types'
import { HighlightText } from '../../components/HighlightText'
import './SearchPage.css'

/**
 * 语义检索页 —— 混合检索（向量语义 + Lucene 全文，RRF 融合）的交互界面。
 *
 * 【教学注释 · 这一页的四种状态】
 * 初始态（未查询，引导输入）→ 加载态（Spin）→ 结果态（命中列表）
 * → 空态/错误态（引导文案或错误卡片）。列表页可以偷懒用 loading 覆盖，
 * 检索页不行：查询是用户明确的动作，每个分支都要有交代（spec：
 * 空结果空态、错误错误态）。
 *
 * 【教学注释 · 会话内历史】用 useState 而不是 localStorage——"会话内"
 * 的语义就是刷新即清空；持久化历史是另一个需求（另一个变更的事）。
 */
const HISTORY_LIMIT = 8

/** 来源标注的中文与颜色（spec：命中来源标注可见；键与后端 Source 枚举一致） */
const SOURCE_LABEL: Record<SearchSource, { text: string; color: string }> = {
  SEMANTIC: { text: '语义命中', color: 'geekblue' },
  FULLTEXT: { text: '全文命中', color: 'green' },
  BOTH: { text: '双路命中', color: 'purple' },
}

function SearchPage() {
  const navigate = useNavigate()
  const [query, setQuery] = useState('')
  const [loading, setLoading] = useState(false)
  const [result, setResult] = useState<SearchResultVo | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [history, setHistory] = useState<string[]>([])

  const runSearch = async (rawQuery: string) => {
    const q = rawQuery.trim()
    if (!q || loading) return
    setLoading(true)
    setError(null)
    try {
      const data = await search({ query: q, topK: 10 })
      setResult(data)
      // 历史去重置顶、限长（最旧的挤掉）
      setHistory((prev) => [q, ...prev.filter((h) => h !== q)].slice(0, HISTORY_LIMIT))
    } catch (e) {
      // 检索失败：展示错误态卡片（错误 toast 已由拦截器统一弹出）
      setResult(null)
      setError(e instanceof Error ? e.message : '检索失败，请重试')
    } finally {
      setLoading(false)
    }
  }

  const sourceTag = useMemo(
    () => (source: SearchSource) => {
      const meta = SOURCE_LABEL[source] ?? { text: source, color: 'default' }
      return <Tag color={meta.color}>{meta.text}</Tag>
    },
    [],
  )

  return (
    <section>
      <div className="search-page__header">
        <h2 className="search-page__title">语义检索</h2>
      </div>

      <Card size="small" className="search-page__form">
        <Space.Compact style={{ width: '100%' }}>
          <Input
            size="large"
            placeholder="输入查询，如：红塔、向量化流程、网关转发……"
            value={query}
            maxLength={200}
            onChange={(e) => setQuery(e.target.value)}
            onPressEnter={() => void runSearch(query)}
            allowClear
          />
          <Button
            type="primary"
            size="large"
            icon={<SearchOutlined />}
            loading={loading}
            onClick={() => void runSearch(query)}
          >
            检索
          </Button>
        </Space.Compact>
        <div style={{ marginTop: 8, color: '#9ca3af', fontSize: 12 }}>
          混合检索：向量语义（余弦）+ 全文匹配（BM25），RRF 融合排序，取 top 10。
        </div>
      </Card>

      {loading && (
        <div style={{ textAlign: 'center', padding: 48 }}>
          <Spin tip="正在向量化查询并检索……" />
        </div>
      )}

      {error && !loading && (
        <Alert
          type="error"
          showIcon
          style={{ marginBottom: 24 }}
          message="检索失败"
          description={
            <>
              <div>{error}</div>
              <div style={{ marginTop: 8, color: '#9ca3af' }}>
                常见原因：Python 引擎未就绪（3xxx）、业务服务不可达。可稍后重试或到仪表盘查看组件健康。
              </div>
            </>
          }
          action={<Button size="small" onClick={() => void runSearch(query)}>重试</Button>}
        />
      )}

      {result && !loading && (
        <>
          <div className="search-page__meta">
            <span>命中 <b>{result.total}</b> 条</span>
            <span>耗时 {result.tookMs} ms</span>
            {result.cached && <Tag>缓存命中</Tag>}
            {result.degraded && <Tag color="warning">降级：{result.degradedReason ?? '精度受限'}</Tag>}
          </div>

          {result.items.length === 0 ? (
            <Empty
              description={
                <span>
                  没有找到与「{result.query}」相关的内容
                  <br />
                  <span style={{ color: '#9ca3af' }}>
                    试着换更短的关键词，或先到「文档管理」上传包含相关内容的文档
                  </span>
                </span>
              }
            />
          ) : (
            result.items.map((hit, index) => (
              <Card key={`${hit.documentId}-${hit.chunkIndex}`} size="small" className="search-page__hit">
                <div className="search-page__hit-header">
                  <span className="search-page__hit-rank">#{index + 1}</span>
                  <span
                    className="search-page__hit-doc"
                    onClick={() => navigate('/documents')}
                    title="到文档管理查看该文档"
                  >
                    {hit.documentName}
                  </span>
                  {sourceTag(hit.source)}
                  {hit.degraded && <Tag color="warning">降级块</Tag>}
                </div>
                <div className="search-page__hit-snippet">
                  {/* 文本模态下后端保证有片段；类型上可 null 是因为图片命中无 snippet */}
                  <HighlightText highlight={hit.highlight ?? hit.snippet ?? ''} />
                </div>
                <div className="search-page__hit-scores">
                  <span>融合分 {hit.score.toFixed(4)}</span>
                  <span>语义 {hit.vectorScore > 0 ? hit.vectorScore.toFixed(4) : '-'}</span>
                  <span>全文 {(hit.textScore ?? 0) > 0 ? (hit.textScore as number).toFixed(2) : '-'}</span>
                </div>
              </Card>
            ))
          )}
        </>
      )}

      {!result && !error && !loading && (
        <Empty description="输入查询词开始检索——语义与全文双路命中，片段中的命中词会高亮显示" />
      )}

      {history.length > 0 && (
        <Card size="small" className="search-page__history" title="本次会话查询历史">
          <div className="search-page__history-tags">
            {history.map((h) => (
              <Tag key={h} style={{ cursor: 'pointer' }} onClick={() => { setQuery(h); void runSearch(h) }}>
                {h}
              </Tag>
            ))}
          </div>
        </Card>
      )}
    </section>
  )
}

export default SearchPage
