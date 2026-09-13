import { useEffect, useState } from 'react'
import {
  Button,
  Card,
  Descriptions,
  Divider,
  Empty,
  message,
  Radio,
  Space,
  Spin,
  Tag,
  Typography,
} from 'antd'
import { DownloadOutlined, FileTextOutlined } from '@ant-design/icons'
import { useParams, useNavigate } from 'react-router-dom'
import { exportUrl, getRequirement, listArtifacts, getArtifact } from '../../api/requirements'
import type {
  ArtifactContentVo,
  ArtifactSummaryVo,
  RenderTarget,
  RequirementDetailVo,
} from '../../api/types'

const { Paragraph, Text } = Typography

function RequirementExportPage() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()

  const [detail, setDetail] = useState<RequirementDetailVo | null>(null)
  const [artifacts, setArtifacts] = useState<ArtifactSummaryVo[]>([])
  const [loading, setLoading] = useState(false)
  const [target, setTarget] = useState<RenderTarget>('vibecoding')
  const [content, setContent] = useState<ArtifactContentVo | null>(null)
  const [contentLoading, setContentLoading] = useState(false)

  useEffect(() => {
    if (!id) return
    setLoading(true)
    Promise.all([getRequirement(id), listArtifacts(id)])
      .then(([d, arts]) => {
        setDetail(d)
        setArtifacts(arts)
      })
      .catch(() => message.error('加载失败'))
      .finally(() => setLoading(false))
  }, [id])

  // 选中目标后自动加载最新工件内容
  useEffect(() => {
    if (!id) return
    const latest = artifacts
      .filter((a) => a.target === target)
      .sort((a, b) => b.version - a.version)[0]
    if (!latest) {
      setContent(null)
      return
    }
    setContentLoading(true)
    getArtifact(id, latest.version, latest.target)
      .then(setContent)
      .catch(() => message.error('加载工件失败'))
      .finally(() => setContentLoading(false))
  }, [id, target, artifacts])

  const handleDownload = () => {
    if (!id) return
    window.open(exportUrl(id, target), '_blank')
  }

  return (
    <section>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 24 }}>
        <h2 style={{ margin: 0, fontSize: 16, fontWeight: 600 }}>导出规约</h2>
        <Space>
          <Button onClick={() => navigate(`/requirements/${id}`)}>返回工坊</Button>
          <Button
            type="primary"
            icon={<DownloadOutlined />}
            onClick={handleDownload}
            disabled={!content}
          >
            下载文件
          </Button>
        </Space>
      </div>

      <Spin spinning={loading}>
        {detail && (
          <Card size="small" style={{ marginBottom: 16 }}>
            <Descriptions column={2} size="small">
              <Descriptions.Item label="需求">{detail.summary.title}</Descriptions.Item>
              <Descriptions.Item label="状态">
                <Tag>{detail.summary.status}</Tag>
              </Descriptions.Item>
              <Descriptions.Item label="提出人">{detail.summary.submitter}</Descriptions.Item>
              <Descriptions.Item label="优先级">{detail.summary.priority}</Descriptions.Item>
            </Descriptions>
          </Card>
        )}

        <Card size="small" style={{ marginBottom: 16 }}>
          <Space>
            <Text strong>渲染目标：</Text>
            <Radio.Group value={target} onChange={(e) => setTarget(e.target.value)}>
              <Radio.Button value="vibecoding">VibeCoding (TASK.md)</Radio.Button>
              <Radio.Button value="openspec">OpenSpec (完整包)</Radio.Button>
            </Radio.Group>
          </Space>
        </Card>

        <Card
          size="small"
          title={
            <Space>
              <FileTextOutlined />
              <span>文件预览</span>
              {content && (
                <Tag color="blue">v{content.version}</Tag>
              )}
            </Space>
          }
        >
          <Spin spinning={contentLoading}>
            {!content && (
              <Empty
                description="该目标下暂无工件，请先在工坊页渲染"
                image={Empty.PRESENTED_IMAGE_SIMPLE}
              />
            )}
            {content &&
              Object.entries(content.files).map(([name, fileContent]) => (
                <div key={name} style={{ marginBottom: 24 }}>
                  <Text
                    strong
                    style={{
                      display: 'block',
                      marginBottom: 8,
                      padding: '4px 8px',
                      background: '#f0f2f5',
                      borderRadius: 4,
                      fontFamily: 'monospace',
                    }}
                  >
                    {name}
                  </Text>
                  <Paragraph>
                    <pre
                      style={{
                        whiteSpace: 'pre-wrap',
                        wordBreak: 'break-word',
                        background: '#fafafa',
                        padding: 16,
                        borderRadius: 8,
                        border: '1px solid #e5e7eb',
                        fontSize: 13,
                        fontFamily: 'monospace',
                        lineHeight: 1.6,
                        maxHeight: 500,
                        overflow: 'auto',
                      }}
                    >
                      {fileContent}
                    </pre>
                  </Paragraph>
                  {Object.keys(content.files).length > 1 && <Divider />}
                </div>
              ))}
          </Spin>
        </Card>
      </Spin>
    </section>
  )
}

export default RequirementExportPage