import { useEffect, useState } from 'react'
import {
  Alert,
  Button,
  Card,
  Descriptions,
  Drawer,
  message,
  Modal,
  Pagination,
  Progress,
  Table,
  Tag,
  Upload,
} from 'antd'
import type { UploadProps } from 'antd'
import { DeleteOutlined, InboxOutlined, ReloadOutlined } from '@ant-design/icons'
import {
  deleteDocument,
  getDocument,
  listDocuments,
  uploadDocument,
} from '../../api/documents'
import type {
  DocumentDetailVo,
  DocumentStatus,
  DocumentSummary,
} from '../../api/types'
import { DocumentStatusBadge } from '../../components/StatusBadge'
import { usePolling } from '../../hooks/usePolling'
import './DocumentsPage.css'

/**
 * 文档管理页 —— 摄取全流程的操作台：上传 → 观察状态流转 → 查详情 → 删除。
 *
 * 【教学注释 · 状态流转为什么"自己会动"】
 * 列表用 usePolling 轮询（有处理中文档时 3 秒一拍，全静止时 15 秒一拍），
 * 后台摄取管线每推进一步（PARSING→CHUNKING→VECTORIZING→COMPLETED），
 * 下一次轮询就把新状态带回来——这就是 spec 场景"上传后无需手动刷新
 * 状态自动推进"的全部实现，没有魔法，就是定时拉。
 *
 * 【教学注释 · 页面状态的三层】
 * 1. 列表数据（轮询管）——本页唯一的服务端状态源；
 * 2. 上传进度（组件内 state）——纯客户端瞬时状态；
 * 3. 详情抽屉（点开才拉）——按需加载，不跟随列表轮询刷新。
 */
const PAGE_SIZE = 20

/** customRequest 回调参数类型（antd 未直接导出，从 UploadProps 签名提取） */
type UploadRequestOptions = Parameters<NonNullable<UploadProps['customRequest']>>[0]

/** 处理中状态集合：决定轮询节奏（有活动文档时加速） */
const ACTIVE_STATUSES: DocumentStatus[] = ['PENDING', 'PARSING', 'CHUNKING', 'VECTORIZING']

/** 字节数 → 人类可读（B/KB/MB），列表"大小"列用 */
function formatSize(bytes: number): string {
  if (bytes <= 0) return '-'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

function formatTime(iso: string): string {
  return new Date(iso).toLocaleString()
}

function DocumentsPage() {
  const [page, setPage] = useState(1)
  const [uploading, setUploading] = useState(false)
  const [uploadPercent, setUploadPercent] = useState(0)
  const [detailId, setDetailId] = useState<string | null>(null)
  const [detail, setDetail] = useState<DocumentDetailVo | null>(null)
  const [detailLoading, setDetailLoading] = useState(false)

  // 轮询节奏自适应：有处理中文档 → 3 秒一拍（状态流转肉眼可见）；静止 → 15 秒。
  // 【教学注释 · 为什么用"上一拍数据决定下一拍间隔"而不是直接算】
  // 间隔是 usePolling 的入参、数据是它的出参，直接互引就是循环依赖
  // （声明顺序无论怎么排都有一边"先用后声明"）。用 state 缓存上一拍
  // 的判断结果，一拍延迟换来依赖关系单向：data → effect → fastPoll → 间隔。
  const [fastPoll, setFastPoll] = useState(false)
  const { data, error, refresh } = usePolling(
    () => listDocuments(page, PAGE_SIZE),
    fastPoll ? 3_000 : 15_000,
  )

  useEffect(() => {
    const active = (data?.items ?? []).some((d) => ACTIVE_STATUSES.includes(d.status))
    setFastPoll(active)
  }, [data])

  const items = data?.items ?? []

  // 上传：接管 Upload 的默认行为（customRequest），走统一客户端
  const handleUpload = async (option: UploadRequestOptions) => {
    const { file } = option
    const raw = Array.isArray(file) ? file[0] : file
    setUploading(true)
    setUploadPercent(0)
    try {
      const summary = await uploadDocument(raw as unknown as File, setUploadPercent)
      message.success(`上传成功：${summary.filename}，已进入处理队列`)
      refresh() // 立即拉一次列表（不等下一拍轮询）
    } catch {
      // 错误 toast 已由 API 层统一弹出，这里只需复位 UI
    } finally {
      setUploading(false)
    }
  }

  // 删除：二次确认（spec：删除是联动清理，不可撤销）
  const confirmDelete = (doc: DocumentSummary) => {
    Modal.confirm({
      title: '确认删除文档',
      content: `将删除「${doc.filename}」及其全部分块、向量与全文索引条目，删除后不可恢复。`,
      okText: '删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        await deleteDocument(doc.id)
        message.success(`已删除：${doc.filename}`)
        refresh()
      },
    })
  }

  // 详情：按需拉取（含分块文本、模型、维度、降级原因）
  const openDetail = async (id: string) => {
    setDetailId(id)
    setDetail(null)
    setDetailLoading(true)
    try {
      setDetail(await getDocument(id))
    } catch {
      // API 层已 toast；抽屉保持空态
    } finally {
      setDetailLoading(false)
    }
  }

  return (
    <section>
      <div className="documents-page__header">
        <h2 className="documents-page__title">文档管理</h2>
        <Button icon={<ReloadOutlined />} onClick={refresh}>刷新</Button>
      </div>

      {!!error && !data && (
        <Alert
          type="error"
          showIcon
          style={{ marginBottom: 24 }}
          message="文档列表加载失败"
          description="请确认业务服务已启动；恢复后自动重试。"
        />
      )}

      <Card size="small" className="documents-page__upload">
        <Upload.Dragger
          multiple={false}
          showUploadList={false}
          accept=".txt,.pdf,.docx"
          customRequest={handleUpload}
          disabled={uploading}
        >
          <p className="ant-upload-drag-icon"><InboxOutlined /></p>
          <p className="ant-upload-text">点击或拖拽文件到此处上传</p>
          <p className="ant-upload-hint">支持 txt / docx / pdf，单文件不超过 50MB；上传后自动解析、分块、向量化</p>
        </Upload.Dragger>
        {uploading && (
          <Progress percent={uploadPercent} size="small" style={{ marginTop: 8 }} />
        )}
      </Card>

      <Table<DocumentSummary>
        rowKey="id"
        size="small"
        loading={!data && !!error}
        dataSource={items}
        pagination={false}
        onRow={(record) => ({ onClick: () => openDetail(record.id), style: { cursor: 'pointer' } })}
        columns={[
          {
            title: '文件名',
            dataIndex: 'filename',
            ellipsis: true,
            render: (name: string, record) => (
              <>
                {name}
                {record.degraded && <Tag color="warning" style={{ marginLeft: 8 }}>降级</Tag>}
              </>
            ),
          },
          { title: '大小', dataIndex: 'sizeBytes', width: 90, render: (v: number) => formatSize(v) },
          {
            title: '状态',
            dataIndex: 'status',
            width: 110,
            render: (_, record) => (
              <DocumentStatusBadge status={record.status} label={record.statusLabel} />
            ),
          },
          {
            title: '分块/已向量化',
            width: 130,
            render: (_, record) => `${record.chunkCount} / ${record.vectorizedCount}`,
          },
          {
            title: '上传时间',
            dataIndex: 'createdAt',
            width: 170,
            render: (v: string) => formatTime(v),
          },
          {
            title: '操作',
            width: 80,
            render: (_, record) => (
              <Button
                type="text"
                danger
                size="small"
                icon={<DeleteOutlined />}
                onClick={(e) => {
                  e.stopPropagation() // 阻止触发行点击（打开详情）
                  confirmDelete(record)
                }}
              />
            ),
          },
        ]}
      />

      {data && data.total > PAGE_SIZE && (
        <div className="documents-page__pagination">
          <Pagination
            current={page}
            pageSize={PAGE_SIZE}
            total={data.total}
            showSizeChanger={false}
            onChange={setPage}
          />
        </div>
      )}

      <Drawer
        title={detail?.document.filename ?? '文档详情'}
        width={520}
        open={detailId !== null}
        onClose={() => setDetailId(null)}
        loading={detailLoading}
      >
        {detail && <DocumentDetailBody detail={detail} />}
      </Drawer>
    </section>
  )
}

/** 详情抽屉正文：基础信息 + 失败/降级提示 + 分块文本预览 */
function DocumentDetailBody({ detail }: { detail: DocumentDetailVo }) {
  const { document: doc } = detail
  return (
    <>
      <Descriptions size="small" column={1} bordered>
        <Descriptions.Item label="状态">
          <DocumentStatusBadge status={doc.status} label={doc.statusLabel} />
        </Descriptions.Item>
        <Descriptions.Item label="文件大小">{formatSize(doc.sizeBytes)}</Descriptions.Item>
        <Descriptions.Item label="分块数">{doc.chunkCount}（已向量化 {doc.vectorizedCount}）</Descriptions.Item>
        <Descriptions.Item label="向量化模型">{detail.modelKey ?? '未向量化'}</Descriptions.Item>
        <Descriptions.Item label="向量维度">{detail.dimension ?? '-'}</Descriptions.Item>
        <Descriptions.Item label="上传时间">{formatTime(doc.createdAt)}</Descriptions.Item>
        <Descriptions.Item label="更新时间">{formatTime(doc.updatedAt)}</Descriptions.Item>
      </Descriptions>

      {doc.degraded && (
        <Alert
          type="warning"
          showIcon
          style={{ marginTop: 16 }}
          message="该文档在引擎降级模式下完成向量化"
          description={detail.degradedReason ?? '真实模型不可用，向量由哈希兜底生成，检索精度受限。'}
        />
      )}
      {doc.status === 'FAILED' && (
        <Alert
          type="error"
          showIcon
          style={{ marginTop: 16 }}
          message="摄取失败"
          description={doc.errorMessage ?? '未知原因'}
        />
      )}

      {detail.chunks.length > 0 && (
        <>
          <h3 style={{ fontSize: 14, margin: '24px 0 8px' }}>分块预览</h3>
          {detail.chunks.map((chunk) => (
            <div key={chunk.chunkIndex} className="documents-page__chunk">
              <span className="documents-page__chunk-index">#{chunk.chunkIndex}</span>
              {chunk.text}
            </div>
          ))}
        </>
      )}
    </>
  )
}

export default DocumentsPage
