import { useCallback, useEffect, useState } from 'react'
import {
  Button,
  Card,
  Input,
  Pagination,
  Select,
  Space,
  Table,
  Tag,
  Tooltip,
} from 'antd'
import {
  PlusOutlined,
  ReloadOutlined,
  SearchOutlined,
} from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import { listRequirements } from '../../api/requirements'
import type { RequirementStatus, RequirementSummary } from '../../api/types'
import type { ColumnsType } from 'antd/es/table'

const STATUS_OPTIONS: { value: RequirementStatus | ''; label: string }[] = [
  { value: '', label: '全部状态' },
  { value: 'DRAFT', label: '草稿' },
  { value: 'SUBMITTED', label: '已提交' },
  { value: 'EXPORTED', label: '已导出' },
]

const PRIORITY_OPTIONS = [
  { value: '', label: '全部优先级' },
  { value: 'HIGH', label: '高' },
  { value: 'MEDIUM', label: '中' },
  { value: 'LOW', label: '低' },
]

const STATUS_COLOR: Record<string, string> = {
  DRAFT: '#d9d9d9',
  SUBMITTED: '#1890ff',
  EXPORTED: '#52c41a',
}

const PRIORITY_COLOR: Record<string, string> = {
  HIGH: '#ff4d4f',
  MEDIUM: '#faad14',
  LOW: '#52c41a',
}

function RequirementListPage() {
  const navigate = useNavigate()
  const [items, setItems] = useState<RequirementSummary[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(1)
  const [loading, setLoading] = useState(false)
  const [status, setStatus] = useState<RequirementStatus | ''>('')
  const [priority, setPriority] = useState<string>('')
  const [keyword, setKeyword] = useState('')

  const fetchList = useCallback(async () => {
    setLoading(true)
    try {
      const result = await listRequirements({
        page,
        size: 20,
        status: status || null,
        priority: priority || null,
        q: keyword || null,
      })
      setItems(result.items)
      setTotal(result.total)
    } finally {
      setLoading(false)
    }
  }, [page, status, priority, keyword])

  useEffect(() => {
    fetchList()
  }, [fetchList])

  const columns: ColumnsType<RequirementSummary> = [
    {
      title: '需求标题',
      dataIndex: 'title',
      key: 'title',
      render: (text: string, record: RequirementSummary) => (
        <a onClick={() => navigate(`/requirements/${record.id}`)}>{text}</a>
      ),
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 100,
      render: (s: RequirementStatus) => (
        <Tag color={STATUS_COLOR[s] ?? '#d9d9d9'}>{s}</Tag>
      ),
    },
    {
      title: '优先级',
      dataIndex: 'priority',
      key: 'priority',
      width: 80,
      render: (p: string) => (
        <Tag color={PRIORITY_COLOR[p] ?? '#d9d9d9'}>{p}</Tag>
      ),
    },
    {
      title: '提出人',
      dataIndex: 'submitter',
      key: 'submitter',
      width: 100,
    },
    {
      title: '部门',
      dataIndex: 'department',
      key: 'department',
      width: 100,
      render: (d: string) => d || '-',
    },
    {
      title: '更新时间',
      dataIndex: 'updatedAt',
      key: 'updatedAt',
      width: 160,
      render: (t: string) => new Date(t).toLocaleString(),
    },
    {
      title: '操作',
      key: 'actions',
      width: 200,
      render: (_: unknown, record: RequirementSummary) => (
        <Space size="small">
          <Button
            type="link"
            size="small"
            onClick={() => navigate(`/requirements/${record.id}`)}
          >
            工坊
          </Button>
          <Button
            type="link"
            size="small"
            onClick={() => navigate(`/requirements/${record.id}/edit`)}
          >
            编辑
          </Button>
          <Button
            type="link"
            size="small"
            onClick={() => navigate(`/requirements/${record.id}/export`)}
          >
            导出
          </Button>
        </Space>
      ),
    },
  ]

  return (
    <section>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 24 }}>
        <h2 style={{ margin: 0, fontSize: 16, fontWeight: 600 }}>需求列表</h2>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => navigate('/requirements/new')}>
          提交需求
        </Button>
      </div>

      <Card size="small" style={{ marginBottom: 16 }}>
        <Space wrap>
          <Select
            value={status}
            onChange={(v) => { setStatus(v); setPage(1) }}
            options={STATUS_OPTIONS}
            style={{ width: 140 }}
          />
          <Select
            value={priority}
            onChange={(v) => { setPriority(v); setPage(1) }}
            options={PRIORITY_OPTIONS}
            style={{ width: 140 }}
          />
          <Input.Search
            placeholder="搜索标题"
            allowClear
            value={keyword}
            onChange={(e) => setKeyword(e.target.value)}
            onSearch={() => { setPage(1); fetchList() }}
            style={{ width: 260 }}
            enterButton={<SearchOutlined />}
          />
          <Tooltip title="刷新">
            <Button icon={<ReloadOutlined />} onClick={fetchList} />
          </Tooltip>
        </Space>
      </Card>

      <Card size="small">
        <Table
          columns={columns}
          dataSource={items}
          rowKey="id"
          loading={loading}
          pagination={false}
          size="middle"
          locale={{ emptyText: '暂无需求，点击右上角「提交需求」创建第一条' }}
        />
        {total > 0 && (
          <div style={{ marginTop: 16, textAlign: 'right' }}>
            <Pagination
              current={page}
              pageSize={20}
              total={total}
              onChange={(p) => setPage(p)}
              showTotal={(t) => `共 ${t} 条`}
            />
          </div>
        )}
      </Card>
    </section>
  )
}

export default RequirementListPage