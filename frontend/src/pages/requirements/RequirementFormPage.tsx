import { useEffect, useState } from 'react'
import {
  Button,
  Card,
  Form,
  message,
  Result,
  Space,
  Steps,
  Spin,
} from 'antd'
import { CheckCircleOutlined } from '@ant-design/icons'
import { useNavigate, useParams } from 'react-router-dom'
import {
  BasicInfoItems,
  BackgroundItems,
  FeatureItems,
  AcceptanceItems,
  ConstraintItems,
  normalizeForm,
  cleanForm,
} from './formFragments'
import {
  createRequirement,
  getRequirement,
  saveRequirement,
  submitRequirement,
} from '../../api/requirements'
import type { RequirementFormVo, RequirementDetailVo } from '../../api/types'

const STEPS = [
  { title: '基本信息' },
  { title: '业务背景与功能' },
  { title: '验收标准与约束' },
]

function RequirementFormPage() {
  const navigate = useNavigate()
  const { id } = useParams<{ id: string }>()
  const isEdit = Boolean(id)

  const [form] = Form.useForm<RequirementFormVo>()
  const [step, setStep] = useState(0)
  const [loading, setLoading] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [done, setDone] = useState(false)
  const [doneId, setDoneId] = useState<string | null>(null)
  const [savingId, setSavingId] = useState<string | null>(id ?? null)

  // 编辑模式：回填已有数据
  useEffect(() => {
    if (!id) return
    setLoading(true)
    getRequirement(id)
      .then((detail: RequirementDetailVo) => {
        form.setFieldsValue(normalizeForm(detail.form))
      })
      .catch(() => message.error('加载需求失败'))
      .finally(() => setLoading(false))
  }, [id, form])

  const handleSave = async () => {
    try {
      const values = await form.validateFields()
      const cleaned = cleanForm(values)
      if (savingId) {
        await saveRequirement(savingId, cleaned)
        message.success('已保存')
      } else {
        const created = await createRequirement(cleaned)
        setSavingId(created.id)
        message.success('已暂存为草稿')
      }
    } catch {
      // 校验不通过，表单会自动提示
    }
  }

  const handleSubmit = async () => {
    try {
      const values = await form.validateFields()
      const cleaned = cleanForm(values)
      if (!savingId) {
        const created = await createRequirement(cleaned)
        setSavingId(created.id)
      }
      setSubmitting(true)
      const submitted = await submitRequirement(savingId!)
      setDone(true)
      setDoneId(submitted.id)
    } catch {
      // notifyError 已在 API 层处理
    } finally {
      setSubmitting(false)
    }
  }

  const handleNext = async () => {
    try {
      await form.validateFields()
      if (step < STEPS.length - 1) {
        setStep(step + 1)
      }
    } catch {
      // 校验不通过
    }
  }

  if (done) {
    return (
      <Result
        status="success"
        title="需求已成功提交"
        subTitle="需求已通过完整性闸门，可在工坊页渲染为 VibeCoding/OpenSpec 规约"
        extra={[
          <Button key="list" onClick={() => navigate('/requirements')}>
            返回列表
          </Button>,
          <Button
            key="workshop"
            type="primary"
            onClick={() => navigate(`/requirements/${doneId}`)}
          >
            进入工坊
          </Button>,
        ]}
      />
    )
  }

  return (
    <section>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 24 }}>
        <h2 style={{ margin: 0, fontSize: 16, fontWeight: 600 }}>
          {isEdit ? '编辑需求' : '提交需求'}
        </h2>
      </div>

      <Steps
        current={step}
        items={STEPS}
        style={{ marginBottom: 24, maxWidth: 480 }}
      />

      <Spin spinning={loading}>
        <Card size="small">
          <Form
            form={form}
            layout="vertical"
            initialValues={normalizeForm(null)}
          >
            {/* Step 0: 基本信息 */}
            {step === 0 && <BasicInfoItems />}

            {/* Step 1: 业务背景 + 期望功能 */}
            {step === 1 && (
              <>
                <BackgroundItems />
                <div style={{ marginTop: 32 }}>
                  <h3 style={{ fontSize: 14, fontWeight: 600, marginBottom: 16 }}>期望功能</h3>
                  <FeatureItems />
                </div>
              </>
            )}

            {/* Step 2: 验收标准 + 约束 */}
            {step === 2 && (
              <>
                <h3 style={{ fontSize: 14, fontWeight: 600, marginBottom: 16 }}>验收标准</h3>
                <AcceptanceItems />
                <div style={{ marginTop: 32 }}>
                  <h3 style={{ fontSize: 14, fontWeight: 600, marginBottom: 16 }}>约束条件</h3>
                  <ConstraintItems />
                </div>
              </>
            )}
          </Form>

          <div style={{ marginTop: 24, display: 'flex', justifyContent: 'space-between' }}>
            <Space>
              {step > 0 && (
                <Button onClick={() => setStep(step - 1)}>上一步</Button>
              )}
            </Space>
            <Space>
              <Button onClick={handleSave}>暂存</Button>
              {step < STEPS.length - 1 ? (
                <Button type="primary" onClick={handleNext}>
                  下一步
                </Button>
              ) : (
                <Button
                  type="primary"
                  icon={<CheckCircleOutlined />}
                  loading={submitting}
                  onClick={handleSubmit}
                >
                  提交需求
                </Button>
              )}
            </Space>
          </div>
        </Card>
      </Spin>
    </section>
  )
}

export default RequirementFormPage