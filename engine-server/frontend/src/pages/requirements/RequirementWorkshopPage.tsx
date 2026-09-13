import { useCallback, useEffect, useState } from 'react'
import {
  Button,
  Card,
  Descriptions,
  Divider,
  Empty,
  Form,
  Input,
  message,
  Modal,
  Radio,
  Space,
  Spin,
  Table,
  Tabs,
  Tag,
  Typography,
} from 'antd'
import {
  ExportOutlined,
  EyeOutlined,
  RocketOutlined,
  SaveOutlined,
} from '@ant-design/icons'
import { useNavigate, useParams } from 'react-router-dom'
import {
  getRequirement,
  renderRequirement,
  listArtifacts,
  getArtifact,
  saveRevision,
} from '../../api/requirements'
import {
  BackgroundItems,
  FeatureItems,
  AcceptanceItems,
  ConstraintItems,
  BasicInfoItems,
  normalizeForm,
} from './formFragments'
import type {
  ArtifactContentVo,
  ArtifactSummaryVo,
  RequirementDetailVo,
  RenderTarget,
} from '../../api/types'

const { TextArea } = Input
const { Text } = Typography

function RequirementWorkshopPage() {
  const navigate = useNavigate()
  const { id } = useParams<{ id: string }>()
  const [form] = Form.useForm()

  const [detail, setDetail] = useState<RequirementDetailVo | null>(null)
  const [loading, setLoading] = useState(false)
  const [renderTarget, setRenderTarget] = useState<RenderTarget>('vibecoding')
  const [artifacts, setArtifacts] = useState<ArtifactSummaryVo[]>([])
  const [rendering, setRendering] = useState(false)
  const [previewVisible, setPreviewVisible] = useState(false)
  const [previewContent, setPreviewContent] = useState<ArtifactContentVo | null>(null)
  const [previewLoading, setPreviewLoading] = useState(false)
  const [editingFiles, setEditingFiles] = useState<Record<string, string>>({})

  const loadDetail = useCallback(async () => {
    if (!id) return
    setLoading(true)
    try {
      const d = await getRequirement(id)
      setDetail(d)
      form.setFieldsValue(normalizeForm(d.form))
      // 同时加载工件列表
      const arts = await listArtifacts(id)
      setArtifacts(arts)
    } catch {
      message.error('加载需求失败')
    } finally {
      setLoading(false)
    }
  }, [id, form])

  useEffect(() => {
    loadDetail()
  }, [loadDetail])

  const handleRender = async () => {
    if (!id) return
    setRendering(true)
    try {
      await renderRequirement(id, renderTarget)
      message.success(`渲染完成（${renderTarget}）`)
      const arts = await listArtifacts(id)
      setArtifacts(arts)
    } catch {
      // handled in API
    } finally {
      setRendering(false)
    }
  }

  const handlePreview = async (version: number, target: RenderTarget) => {
    if (!id) return
    setPreviewLoading(true)
    setPreviewVisible(true)
    try {
      const content = await getArtifact(id, version, target)
      setPreviewContent(content)
      setEditingFiles({ ...content.files })
    } catch {
      message.error('加载工件内容失败')
    } finally {
      setPreviewLoading(false)
    }
  }

  const handleSaveRevision = async () => {
    if (!id || !previewContent) return
    try {
      await saveRevision(id, previewContent.version, previewContent.target, editingFiles)
      message.success('修订已保存')
      setPreviewVisible(false)
      const arts = await listArtifacts(id)
      setArtifacts(arts)
    } catch {
      // handled
    }
  }

  const statusTag = detail?.summary.status
  const statusColor: Record<string, string> = {
    DRAFT: '#d9d9d9',
    SUBMITTED: '#1890ff',
    EXPORTED: '#52c41a',
  }

  return (
    <section>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 24 }}>
        <h2 style={{ margin: 0, fontSize: 16, fontWeight: 600 }}>需求工坊</h2>
        <Space>
          <Button onClick={() => navigate('/requirements')}>返回列表</Button>
          <Button onClick={() => navigate(`/requirements/${id}/edit`)}>编辑表单</Button>
          <Button
            type="primary"
            icon={<ExportOutlined />}
            onClick={() => navigate(`/requirements/${id}/export`)}
          >
            导出
          </Button>
        </Space>
      </div>

      <Spin spinning={loading}>
        {detail && (
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 24 }}>
            {/* 左栏：需求摘要 + 表单只读 */}
            <div>
              <Card size="small" title="需求摘要" style={{ marginBottom: 16 }}>
                <Descriptions column={2} size="small">
                  <Descriptions.Item label="标题">{detail.summary.title}</Descriptions.Item>
                  <Descriptions.Item label="状态">
                    <Tag color={statusColor[statusTag ?? ''] ?? '#d9d9d9'}>
                      {statusTag}
                    </Tag>
                  </Descriptions.Item>
                  <Descriptions.Item label="提出人">{detail.summary.submitter}</Descriptions.Item>
                  <Descriptions.Item label="部门">{detail.summary.department || '-'}</Descriptions.Item>
                  <Descriptions.Item label="优先级">{detail.summary.priority}</Descriptions.Item>
                  <Descriptions.Item label="更新时间">
                    {new Date(detail.summary.updatedAt).toLocaleString()}
                  </Descriptions.Item>
                </Descriptions>
              </Card>

              <Card size="small" title="需求表单">
                <Form form={form} layout="vertical" disabled>
                  <BasicInfoItems />
                  <Divider />
                  <BackgroundItems />
                  <Divider />
                  <h3 style={{ fontSize: 14, fontWeight: 600, marginBottom: 16 }}>期望功能</h3>
                  <FeatureItems />
                  <Divider />
                  <h3 style={{ fontSize: 14, fontWeight: 600, marginBottom: 16 }}>验收标准</h3>
                  <AcceptanceItems />
                  <Divider />
                  <h3 style={{ fontSize: 14, fontWeight: 600, marginBottom: 16 }}>约束条件</h3>
                  <ConstraintItems />
                </Form>
              </Card>
            </div>

            {/* 右栏：渲染 + 工件列表 */}
            <div>
              <Card size="small" title="渲染规约" style={{ marginBottom: 16 }}>
                <Space direction="vertical" style={{ width: '100%' }}>
                  <Radio.Group
                    value={renderTarget}
                    onChange={(e) => setRenderTarget(e.target.value)}
                  >
                    <Radio.Button value="vibecoding">VibeCoding (TASK.md)</Radio.Button>
                    <Radio.Button value="openspec">OpenSpec (完整包)</Radio.Button>
                  </Radio.Group>
                  <Button
                    type="primary"
                    icon={<RocketOutlined />}
                    loading={rendering}
                    onClick={handleRender}
                    block
                  >
                    渲染规约
                  </Button>
                </Space>
              </Card>

              <Card size="small" title="工件版本">
                {artifacts.length === 0 ? (
                  <Empty description="暂无工件，请先渲染" image={Empty.PRESENTED_IMAGE_SIMPLE} />
                ) : (
                  <Table
                    dataSource={artifacts}
                    rowKey={(r) => `${r.target}-${r.version}`}
                    size="small"
                    pagination={false}
                    columns={[
                      { title: '目标', dataIndex: 'target', width: 100 },
                      { title: '版本', dataIndex: 'version', width: 60 },
                      {
                        title: '状态',
                        key: 'stale',
                        width: 80,
                        render: (_: unknown, r: ArtifactSummaryVo) =>
                          r.stale ? <Tag color="orange">已过期</Tag> : <Tag color="green">有效</Tag>,
                      },
                      {
                        title: '文件',
                        key: 'files',
                        render: (_: unknown, r: ArtifactSummaryVo) =>
                          r.fileNames?.join(', ') || '-',
                      },
                      {
                        title: '时间',
                        dataIndex: 'createdAt',
                        render: (v: string) => new Date(v).toLocaleString(),
                      },
                      {
                        title: '操作',
                        key: 'actions',
                        render: (_: unknown, r: ArtifactSummaryVo) => (
                          <Button
                            type="link"
                            size="small"
                            icon={<EyeOutlined />}
                            onClick={() => handlePreview(r.version, r.target)}
                          >
                            预览
                          </Button>
                        ),
                      },
                    ]}
                  />
                )}
              </Card>
            </div>
          </div>
        )}
      </Spin>

      {/* 工件预览 + 修订模态框 */}
      <Modal
        title={`工件预览${previewContent ? ` — v${previewContent.version}` : ''}`}
        open={previewVisible}
        onCancel={() => setPreviewVisible(false)}
        width={800}
        footer={[
          <Button key="close" onClick={() => setPreviewVisible(false)}>
            关闭
          </Button>,
          <Button
            key="save"
            type="primary"
            icon={<SaveOutlined />}
            onClick={handleSaveRevision}
          >
            保存修订
          </Button>,
        ]}
      >
        <Spin spinning={previewLoading}>
          {previewContent && (
            <Tabs
              items={Object.entries(previewContent.files).map(([name, content]) => ({
                key: name,
                label: name,
                children: (
                  <div>
                    <Text type="secondary" style={{ marginBottom: 8, display: 'block' }}>
                      可直接编辑；保存修订后版本号递增
                    </Text>
                    <TextArea
                      value={editingFiles[name] ?? content}
                      onChange={(e) =>
                        setEditingFiles((prev) => ({ ...prev, [name]: e.target.value }))
                      }
                      rows={20}
                      style={{ fontFamily: 'monospace', fontSize: 13 }}
                    />
                  </div>
                ),
              }))}
            />
          )}
        </Spin>
      </Modal>
    </section>
  )
}

export default RequirementWorkshopPage