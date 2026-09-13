import { useState } from 'react'
import { Alert, Button, Card, Empty, Image, Input, Space, Spin, Switch, Tag, Tooltip } from 'antd'
import { SearchOutlined } from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import { imageUrl } from '../../api/images'
import { search } from '../../api/search'
import type { SearchHitVo, SearchResultVo } from '../../api/types'
import './ImageSearchPage.css'

/**
 * 图片检索页 —— 跨模态检索（文字 → 图片）的交互界面。
 *
 * 【教学注释 · 与文本检索页的差异化】同一检索 API，两种命中形态：
 * 文本命中是"片段卡片"（snippet + 高亮），图片命中是"缩略图卡片"——
 * 图片的信息就在像素里，snippet/highlight 恒为 null（后端契约），
 * 卡片以图为主体、分数徽标盖图上。这就是 spec 说的"按 sourceType
 * 差异化渲染"：不是一套卡片硬兼容两种模态，而是两页各自最优。
 *
 * 【教学注释 · 分数语义（photo-semantic-search 后）】图片模态是
 * 双路召回 + RRF 融合 + 交叉编码器重排的三段管线：
 * - vectorScore = CLIP 像素路余弦（描述路命中时为融合序分语义）
 * - rerankScore = 重排分（仅参与重排的已标注候选携带，null=未参与）
 * 响应级 reranked/reason 标明本次是否真走了重排——降级（关闭/不可用/
 * 候选全无标注）时直通召回序并给出原因，UI 不猜、如实展示。
 */
function ImageSearchPage() {
  const navigate = useNavigate()
  const [query, setQuery] = useState('')
  const [rerankOn, setRerankOn] = useState(true)
  const [loading, setLoading] = useState(false)
  const [result, setResult] = useState<SearchResultVo | null>(null)
  const [error, setError] = useState<string | null>(null)

  const runSearch = async (rawQuery: string) => {
    const q = rawQuery.trim()
    if (!q || loading) return
    setLoading(true)
    setError(null)
    try {
      const data = await search({ query: q, topK: 24, modality: 'image', rerank: rerankOn })
      setResult(data)
    } catch (e) {
      setResult(null)
      setError(e instanceof Error ? e.message : '检索失败，请重试')
    } finally {
      setLoading(false)
    }
  }

  /** 标注徽标：三态（true=模拟识别 / false=真实 / null=未拆分占位） */
  const annotationBadge = (hit: SearchHitVo) => {
    if (hit.annotationMocked === true) {
      return (
        <Tooltip title="命中的是 mock 标注文本（规则派生），真实 VLM 接入后自动替换">
          <Tag color="purple">模拟识别</Tag>
        </Tooltip>
      )
    }
    if (hit.annotationMocked === false) return <Tag color="green">已识别</Tag>
    return <Tag>未拆分</Tag>
  }

  /** 单张命中卡片：缩略图 + 分数徽标 + 标注行（主题·描述 + 三态徽标 + 重排分） */
  const hitCard = (hit: SearchHitVo, index: number) => (
    <Card key={hit.documentId} size="small" className="image-search-page__card" bodyStyle={{ padding: 8 }}>
      <div className="image-search-page__hit-thumb">
        <span className="image-search-page__score">相似度 {hit.vectorScore.toFixed(3)}</span>
        <span className="image-search-page__rank">#{index + 1}</span>
        <Image
          src={imageUrl(hit.documentId)}
          alt={hit.documentName}
          loading="lazy"
          wrapperClassName="ant-image"
        />
      </div>
      <div className="image-search-page__hit-meta">
        <span className="image-search-page__hit-name" title={hit.documentName}>{hit.documentName}</span>
        {hit.degraded && <Tag color="warning">降级</Tag>}
        {hit.rerankScore != null && (
          <Tooltip title="交叉编码器重排分（query × 标注文本精算），决定该命中在结果中的最终序位">
            <Tag color="blue">重排 {hit.rerankScore.toFixed(3)}</Tag>
          </Tooltip>
        )}
      </div>
      {(hit.subject || hit.description) && (
        <div className="image-search-page__hit-annotation">
          <span className="image-search-page__hit-subject">{hit.subject}</span>
          <span className="image-search-page__hit-desc" title={hit.description}>{hit.description}</span>
          {annotationBadge(hit)}
        </div>
      )}
    </Card>
  )

  return (
    <section>
      <div className="image-search-page__header">
        <h2 className="image-search-page__title">图片检索</h2>
      </div>

      <Card size="small" className="image-search-page__form">
        <Space.Compact style={{ width: '100%' }}>
          <Input
            size="large"
            placeholder="用自然语言描述要找的图，如：红塔与小花、夕阳下的湖面……"
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
            搜图
          </Button>
        </Space.Compact>
        <div className="image-search-page__options">
          <span className="image-search-page__option-label">
            重排
            <Tooltip title="融合序后由交叉编码器对「查询 × 候选标注文本」精算重排序（首次使用需加载 ~1.1GB 模型，稍慢）；关闭则直通召回融合结果">
              <Switch size="small" checked={rerankOn} onChange={setRerankOn} />
            </Tooltip>
          </span>
          <span className="image-search-page__hint">
            双路召回：查询同时经 CLIP 文本塔比对图片向量、经文本模型比对识别拆分出的描述，RRF 融合后再可选重排。
          </span>
        </div>
      </Card>

      {loading && (
        <div style={{ textAlign: 'center', padding: 48 }}>
          <Spin tip="正在编码查询、双路召回与重排……" />
        </div>
      )}

      {error && !loading && (
        <Alert
          type="error"
          showIcon
          style={{ marginBottom: 24 }}
          message="图片检索失败"
          description={
            <>
              <div>{error}</div>
              <div style={{ marginTop: 8, color: '#9ca3af' }}>
                常见原因：Python 引擎或模型未就绪（3xxx）、业务服务不可达。可稍后重试或到仪表盘查看组件健康。
              </div>
            </>
          }
          action={<Button size="small" onClick={() => void runSearch(query)}>重试</Button>}
        />
      )}

      {result && !loading && (
        <>
          {result.degraded && (
            <Alert
              type="warning"
              showIcon
              style={{ marginBottom: 16 }}
              message="检索精度受限（降级模式）"
              description={result.degradedReason ?? '真实模型不可用，查询与图片向量由哈希兜底生成，相似度仅供参考。'}
            />
          )}
          <div className="image-search-page__meta">
            <span>命中 <b>{result.total}</b> 张</span>
            <span>耗时 {result.tookMs} ms</span>
            {result.cached && <Tag>缓存命中</Tag>}
            {result.reranked
              ? <Tag color="blue">已重排</Tag>
              : (result.rerankReason && (
                <Tooltip title={result.rerankReason}>
                  <Tag color="default" style={{ cursor: 'help' }}>未重排</Tag>
                </Tooltip>
              ))}
          </div>

          {result.items.length === 0 ? (
            <Empty
              description={
                <span>
                  没有找到与「{result.query}」匹配的图片
                  <br />
                  <span style={{ color: '#9ca3af' }}>
                    试着换更具体的视觉描述，或先到「图片管理」上传照片
                  </span>
                </span>
              }
            >
              <Button type="primary" onClick={() => navigate('/images')}>去上传图片</Button>
            </Empty>
          ) : (
            <div className="image-search-page__grid">
              {result.items.map(hitCard)}
            </div>
          )}
        </>
      )}

      {!result && !error && !loading && (
        <Empty description="输入对画面内容的描述开始搜图——双路召回把文字和图片、图片描述放进同一检索管线" />
      )}
    </section>
  )
}

export default ImageSearchPage
