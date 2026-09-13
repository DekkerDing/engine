import { useEffect, useState } from 'react'
import {
  Alert,
  Button,
  Card,
  Descriptions,
  List,
  message,
  Modal,
  Pagination,
  Progress,
  Tag,
  Tooltip,
  Upload,
} from 'antd'
import type { UploadProps } from 'antd'
import { CloudUploadOutlined, DeleteOutlined, InboxOutlined, ReloadOutlined } from '@ant-design/icons'
import { deleteImage, getImportTask, imageUrl, listImages, uploadImagesBatch } from '../../api/images'
import type { ImageImportFileItemVo, ImageImportTaskVo, ImageStatus, ImageSummary } from '../../api/types'
import { ImageStatusBadge } from '../../components/StatusBadge'
import { usePolling } from '../../hooks/usePolling'
import './ImagesPage.css'

/**
 * 图片管理页 —— 图片摄取的操作台：批量导入 → 缩略图网格观察状态推进 → 删除。
 *
 * 【教学注释 · 与文档管理页的同与不同】
 * 同：受理-即返 + 后台异步向量化 + 轮询推进状态机（usePolling 自适应节奏）。
 * 不同：图片"一图双向量"没有分块进度，列表形态从表格变成缩略图网格——
 * 图片是视觉资产，文件名表格浪费了它最直观的信息（内容本身）。
 *
 * 【教学注释 · 批量导入的两段进度】photo-semantic-search 后多文件走
 * /images/batch：第一段"上传"是网络耗时（axios onUploadProgress 驱动进度条），
 * 第二段"导入"是模型计算耗时（受理回执 taskId → 1.5s 轮询 processed/total）。
 * 两段用一个 Modal 无缝接力，用户看到的是一根连续推进的进度条。
 *
 * 【教学注释 · 标注三态的视觉语义】卡片标注行按 annotationMocked 三态分化：
 * null → 灰字"未拆分"占位（存量图/处理中）；true → 紫 Tag"模拟识别"
 * （mock 标注，内容仅供参考）；false → 直接展示主题·描述（真实 VLM 产出）。
 */
const PAGE_SIZE = 24

/** antd beforeUpload 收到的文件（File + antd 注入的 uid），暂存队列元素 */
type StagedFile = File & { uid: string }

/** 批量导入所处阶段：暂存 → 上传（网络）→ 导入（模型计算） */
type ImportPhase = 'idle' | 'uploading' | 'importing'

/** 处理中状态集合：决定轮询节奏（有活动图片时加速） */
const ACTIVE_STATUSES: ImageStatus[] = ['PENDING', 'VECTORIZING']

/** 字节数 → 人类可读（B/KB/MB） */
function formatSize(bytes: number): string {
  if (bytes <= 0) return '-'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

/** 文件明细状态 → 徽标颜色（完成绿 / 失败红 / 拒绝灰） */
const FILE_STATE_COLOR: Record<string, string> = {
  COMPLETED: 'success',
  FAILED: 'error',
  REJECTED: 'default',
}

function ImagesPage() {
  const [page, setPage] = useState(1)
  const [previewId, setPreviewId] = useState<string | null>(null)

  // 暂存队列：Dragger 收集待导入文件（beforeUpload 返回 false 阻止逐文件直传）
  const [staged, setStaged] = useState<StagedFile[]>([])
  const [phase, setPhase] = useState<ImportPhase>('idle')
  const [uploadPercent, setUploadPercent] = useState(0)
  const [importTask, setImportTask] = useState<ImageImportTaskVo | null>(null)

  // 轮询节奏自适应：与文档页同款（有处理中图片 → 3 秒一拍；静止 → 15 秒）
  const [fastPoll, setFastPoll] = useState(false)
  const { data, error, refresh } = usePolling(
    () => listImages(page, PAGE_SIZE),
    fastPoll ? 3_000 : 15_000,
  )

  useEffect(() => {
    const active = (data?.items ?? []).some((img) => ACTIVE_STATUSES.includes(img.status))
    setFastPoll(active)
  }, [data])

  // 导入任务轮询：RUNNING 期间 1.5s 一拉，COMPLETED 即停（effect 依赖重跑自动清理）
  useEffect(() => {
    if (!importTask || importTask.status !== 'RUNNING') return
    const timer = setInterval(() => {
      getImportTask(importTask.taskId)
        .then(setImportTask)
        .catch(() => { /* 任务端点只在 24h 后才 404，运行中失败按网络抖动忽略 */ })
    }, 1_500)
    return () => clearInterval(timer)
  }, [importTask])

  // 任务完成：刷列表（新图入网格）+ 汇总提示（成功/失败/拒绝一眼可见）
  useEffect(() => {
    if (importTask?.status !== 'COMPLETED') return
    const { succeeded, failed, rejected } = importTask
    message.info(`批量导入完成：成功 ${succeeded} / 失败 ${failed}${rejected ? ` / 受理期拒绝 ${rejected}` : ''}`)
    refresh()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [importTask?.status])

  const items = data?.items ?? []
  const importing = phase !== 'idle'

  // 暂存收集：多选/拖拽进来的文件先进队列（去重同名不拦——疑似重复由后端提示）
  const collect: NonNullable<UploadProps['beforeUpload']> = (file) => {
    setStaged((prev) => [...prev, file as StagedFile])
    return false // 阻止 antd 逐文件自动直传——统一走批量端点
  }

  // 批量导入两段接力：上传（网络进度）→ 受理回执 → 导入（轮询进度）
  const startImport = async () => {
    if (staged.length === 0 || importing) return
    setPhase('uploading')
    setUploadPercent(0)
    setImportTask(null)
    try {
      const task = await uploadImagesBatch(staged, setUploadPercent)
      setImportTask(task)
      setPhase('importing')
      setStaged([])
      if (task.status === 'COMPLETED') {
        message.info('导入已完成（量小执行快，进度已到终态）')
      }
    } catch {
      setPhase('idle') // 受理失败（整单超限/网络）回到暂存态，用户可调整后重试
    }
  }

  // 删除：二次确认（spec：向量+原件+记录三处联动清理，不可撤销）
  const confirmDelete = (image: ImageSummary) => {
    Modal.confirm({
      title: '确认删除图片',
      content: `将删除「${image.filename}」的原图、向量与记录，删除后不再出现在图片检索中。`,
      okText: '删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        await deleteImage(image.id)
        message.success(`已删除：${image.filename}`)
        refresh()
      },
    })
  }

  // 详情弹窗数据源：previewId 恒来自本页卡片点击，items 内必在
  const previewImage = previewId ? items.find((img) => img.id === previewId) ?? null : null

  return (
    <section>
      <div className="images-page__header">
        <h2 className="images-page__title">图片管理</h2>
        <Button icon={<ReloadOutlined />} onClick={refresh}>刷新</Button>
      </div>

      {!!error && !data && (
        <Alert
          type="error"
          showIcon
          style={{ marginBottom: 24 }}
          message="图片列表加载失败"
          description="请确认业务服务已启动；恢复后自动重试。"
        />
      )}

      <Card size="small" className="images-page__upload">
        <Upload.Dragger
          multiple
          showUploadList={false}
          accept=".jpg,.jpeg,.png,.webp,.bmp,.gif"
          beforeUpload={collect}
          disabled={importing}
        >
          <p className="ant-upload-drag-icon"><InboxOutlined /></p>
          <p className="ant-upload-text">点击或拖拽图片到此处（可多选，先暂存后统一导入）</p>
          <p className="ant-upload-hint">支持 jpg / png / webp / bmp / gif，单张不超过 20MB；导入后自动识别拆分并双向量化，即可用文字搜图</p>
        </Upload.Dragger>
        {staged.length > 0 && !importing && (
          <div className="images-page__staged">
            <div className="images-page__staged-list">
              {staged.map((file) => (
                <Tag
                  key={file.uid}
                  closable
                  onClose={() => setStaged((prev) => prev.filter((f) => f !== file))}
                >
                  {file.name}
                </Tag>
              ))}
            </div>
            <Button type="primary" icon={<CloudUploadOutlined />} onClick={() => void startImport()}>
              开始导入（{staged.length} 张）
            </Button>
          </div>
        )}
      </Card>

      {items.length === 0 && !error ? (
        <Alert
          type="info"
          showIcon
          message="还没有图片"
          description="上传几张照片后，就能在「图片检索」页用自然语言描述搜到它们。"
        />
      ) : (
        <div className="images-page__grid">
          {items.map((image) => (
            <Card key={image.id} size="small" className="images-page__card" bodyStyle={{ padding: 8 }}>
              <div className="images-page__thumb" onClick={() => setPreviewId(image.id)}>
                <img
                  src={imageUrl(image.id)}
                  alt={image.filename}
                  loading="lazy"
                />
                {image.status !== 'COMPLETED' && (
                  <div className={`images-page__mask${image.status === 'FAILED' ? ' images-page__mask--failed' : ''}`}>
                    {image.status === 'FAILED' ? (
                      <>
                        <Alert type="error" style={{ margin: 8, fontSize: 12 }} message="摄取失败" />
                        <Tooltip title={image.errorMessage ?? '未知原因'} placement="bottom">
                          <span style={{ cursor: 'help', textDecoration: 'underline dotted' }}>
                            查看失败原因
                          </span>
                        </Tooltip>
                      </>
                    ) : (
                      <>
                        <ImageStatusBadge status={image.status} label={image.statusLabel} />
                        <span>识别与向量化中…</span>
                      </>
                    )}
                  </div>
                )}
              </div>
              {/* 标注三态行：null=未拆分占位 / true=模拟识别徽标 / false=真实产出 */}
              <div className="images-page__annotation">
                {image.annotationMocked == null ? (
                  <span className="images-page__annotation--none">未拆分（等待识别）</span>
                ) : (
                  <>
                    <span className="images-page__annotation-subject">{image.subject || '（无主题）'}</span>
                    <span className="images-page__annotation-desc">{image.description}</span>
                    {image.annotationMocked && (
                      <Tooltip title="当前为 mock 标注器产出（内容按文件名/caption 规则派生），真实 VLM 接入后自动替换">
                        <Tag color="purple" className="images-page__annotation-badge">模拟识别</Tag>
                      </Tooltip>
                    )}
                  </>
                )}
                {image.annotationError && (
                  <Tooltip title={image.annotationError}>
                    <span className="images-page__annotation--error">标注失败</span>
                  </Tooltip>
                )}
              </div>
              {image.tags && image.tags.length > 0 && (
                <div className="images-page__tags">
                  {image.tags.slice(0, 3).map((tag) => <Tag key={tag}>{tag}</Tag>)}
                  {image.tags.length > 3 && <Tag>+{image.tags.length - 3}</Tag>}
                </div>
              )}
              <div className="images-page__meta">
                <ImageStatusBadge status={image.status} label={image.statusLabel} />
                {image.degraded && <Tooltip title="向量化时真实模型不可用，向量由哈希兜底生成"><span style={{ color: '#faad14' }}>降级</span></Tooltip>}
                <span style={{ flex: 1 }} />
                <Button
                  type="text"
                  danger
                  size="small"
                  icon={<DeleteOutlined />}
                  onClick={() => confirmDelete(image)}
                />
              </div>
            </Card>
          ))}
        </div>
      )}

      {data && data.total > PAGE_SIZE && (
        <div className="images-page__pagination">
          <Pagination
            current={page}
            pageSize={PAGE_SIZE}
            total={data.total}
            showSizeChanger={false}
            onChange={setPage}
          />
        </div>
      )}

      {/* 详情弹窗：原图 + 标注五字段全景（6.1 的"详情"形态） */}
      <Modal
        open={!!previewImage}
        title={previewImage?.filename}
        footer={null}
        width={720}
        onCancel={() => setPreviewId(null)}
      >
        {previewImage && (
          <>
            <img
              src={imageUrl(previewImage.id)}
              alt={previewImage.filename}
              style={{ maxWidth: '100%', maxHeight: 420, objectFit: 'contain', display: 'block', margin: '0 auto' }}
            />
            <Descriptions size="small" column={1} style={{ marginTop: 16 }}>
              <Descriptions.Item label="主题">
                {previewImage.annotationMocked == null ? '—' : previewImage.subject || '—'}
              </Descriptions.Item>
              <Descriptions.Item label="描述">
                {previewImage.annotationMocked == null ? '—' : previewImage.description || '—'}
              </Descriptions.Item>
              <Descriptions.Item label="标签">
                {previewImage.annotationMocked == null || previewImage.tags.length === 0
                  ? '—'
                  : previewImage.tags.map((tag) => <Tag key={tag}>{tag}</Tag>)}
              </Descriptions.Item>
              <Descriptions.Item label="标注来源">
                {previewImage.annotationMocked == null && '未拆分'}
                {previewImage.annotationMocked === true && <Tag color="purple">模拟识别（mock）</Tag>}
                {previewImage.annotationMocked === false && <Tag color="green">真实识别</Tag>}
                {previewImage.annotationError && (
                  <span style={{ color: '#faad14', marginLeft: 8 }}>（上次标注失败：{previewImage.annotationError}）</span>
                )}
              </Descriptions.Item>
              <Descriptions.Item label="状态">
                <ImageStatusBadge status={previewImage.status} label={previewImage.statusLabel} />
                {previewImage.degraded && <Tag color="warning">向量降级</Tag>}
              </Descriptions.Item>
              <Descriptions.Item label="大小">{formatSize(previewImage.sizeBytes)}</Descriptions.Item>
            </Descriptions>
          </>
        )}
      </Modal>

      {/* 批量导入进度弹窗：上传段（网络）→ 导入段（轮询）+ 文件级明细 */}
      <Modal
        open={importing}
        title="批量导入"
        footer={importTask?.status === 'COMPLETED'
          ? <Button type="primary" onClick={() => setPhase('idle')}>完成</Button>
          : null}
        closable={importTask?.status === 'COMPLETED'}
        keyboard={false}
        maskClosable={false}
        onCancel={() => setPhase('idle')}
      >
        {phase === 'uploading' && (
          <>
            <Progress percent={uploadPercent} size="small" status="active" />
            <div style={{ color: '#6b7280', fontSize: 12 }}>正在上传文件（网络传输）…</div>
          </>
        )}
        {importTask && (phase === 'importing' || importTask.status === 'COMPLETED') && (
          <>
            <Progress
              percent={importTask.total === 0 ? 100 : Math.round((importTask.processed / importTask.total) * 100)}
              size="small"
              status={importTask.status === 'RUNNING' ? 'active' : (importTask.failed > 0 ? 'normal' : 'success')}
            />
            <div className="images-page__import-stats">
              <span>进度 <b>{importTask.processed}/{importTask.total}</b></span>
              <span>成功 <b style={{ color: '#16a34a' }}>{importTask.succeeded}</b></span>
              <span>失败 <b style={{ color: '#dc2626' }}>{importTask.failed}</b></span>
              {importTask.rejected > 0 && <span>受理拒绝 <b style={{ color: '#6b7280' }}>{importTask.rejected}</b></span>}
            </div>
            {importTask.files.length > 0 && (
              <List
                size="small"
                style={{ maxHeight: 260, overflowY: 'auto' }}
                dataSource={importTask.files}
                renderItem={(item: ImageImportFileItemVo) => (
                  <List.Item style={{ padding: '4px 0' }}>
                    <div className="images-page__import-item">
                      <span className="images-page__import-name" title={item.filename}>{item.filename}</span>
                      <Tag color={FILE_STATE_COLOR[item.state] ?? 'default'}>{item.state}</Tag>
                      {item.duplicateSuspected && (
                        <Tooltip title="与库内已有图片同名且同大小，可能重复导入（仅提示不阻断）">
                          <Tag color="warning">疑似重复</Tag>
                        </Tooltip>
                      )}
                      {item.reason && (
                        <Tooltip title={item.reason}>
                          <span className="images-page__import-reason">原因</span>
                        </Tooltip>
                      )}
                    </div>
                  </List.Item>
                )}
              />
            )}
            {importTask.status === 'RUNNING' && (
              <div style={{ color: '#9ca3af', fontSize: 12 }}>
                识别拆分 + 双向量化进行中（后台并发 2），此页可离开，任务进度后台持续推进。
              </div>
            )}
          </>
        )}
      </Modal>
    </section>
  )
}

export default ImagesPage
