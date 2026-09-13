import { Button, Form, Input, Select, DatePicker } from 'antd'
import { DeleteOutlined, PlusOutlined } from '@ant-design/icons'
import type { RequirementFormVo } from '../../api/types'

/**
 * 需求表单共享片段 —— 三步表单页（RequirementFormPage）与工坊页
 * （RequirementWorkshopPage 左栏）复用同一组字段定义。
 *
 * 【教学注释 · 为什么字段定义要抽成片段组件】
 * "同一份数据，两种编辑场景"（分步填写 vs 整页修订）。如果两页各写一版
 * 字段，很快就会漂移（一页加了字段另一页忘加，保存时互相覆盖丢数据）。
 * 字段的 name 路径就是 RequirementFormVo 的形状（basic.title、features[i]…），
 * 两个页面用同一个 Form 实例约定，setFieldsValue/getFieldsValue 互通。
 */

/** 优先级选项（与后端枚举对齐，改动需双向同步） */
export const PRIORITY_OPTIONS = [
  { value: 'HIGH', label: '高（影响核心流程）' },
  { value: 'MEDIUM', label: '中（有替代方案）' },
  { value: 'LOW', label: '低（可排队）' },
]

/** capabilitySlug 合法格式：小写 kebab-case（渲染为 openspec 变更目录名） */
const SLUG_PATTERN = /^[a-z0-9]+(-[a-z0-9]+)*$/

/** 日期选中后写回表单的格式（后端存 LocalDate 字符串） */
function pickDate(date: unknown): string {
  if (!date) return ''
  // DatePicker 的 dayjs 值转 yyyy-MM-dd（避免引入类型依赖，走 format 鸭子调用）
  return typeof (date as { format?: (f: string) => string }).format === 'function'
    ? (date as { format: (f: string) => string }).format('YYYY-MM-DD')
    : String(date)
}

/** 第一步：基本信息（标题/提出人必填；slug 选填但格式受约束） */
export function BasicInfoItems() {
  return (
    <>
      <Form.Item
        name="basicTitleProxy"
        hidden
      />
      <Form.Item
        label="需求标题"
        name={['basic', 'title']}
        rules={[
          { required: true, message: '请填写需求标题（一句话说清要什么）' },
          { max: 80, message: '标题不超过 80 字，细节放到背景/功能里' },
        ]}
        extra="示例：订单支持批量导出 Excel"
      >
        <Input placeholder="一句话说清要什么" maxLength={80} showCount />
      </Form.Item>

      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', columnGap: 16 }}>
        <Form.Item
          label="提出人"
          name={['basic', 'submitter']}
          rules={[{ required: true, message: '请填写提出人姓名' }]}
        >
          <Input placeholder="谁提出的" maxLength={30} />
        </Form.Item>

        <Form.Item label="所属部门" name={['basic', 'department']}>
          <Input placeholder="选填" maxLength={30} />
        </Form.Item>

        <Form.Item
          label="优先级"
          name={['basic', 'priority']}
          rules={[{ required: true, message: '请选择优先级' }]}
          initialValue="MEDIUM"
        >
          <Select options={PRIORITY_OPTIONS} />
        </Form.Item>

        <Form.Item label="期望上线" name={['basic', 'expectDate']} getValueFromEvent={pickDate}>
          <DatePicker style={{ width: '100%' }} placeholder="选填" />
        </Form.Item>
      </div>

      <Form.Item
        label="能力标识"
        name={['basic', 'capabilitySlug']}
        rules={[
          { pattern: SLUG_PATTERN, message: '小写字母/数字/中划线，如 order-export' },
        ]}
        extra="选填。渲染 openspec 包时的变更目录名；留空由后端取默认值"
      >
        <Input placeholder="如 order-export" maxLength={60} />
      </Form.Item>
    </>
  )
}

/** 第二步：业务背景（痛点/影响范围/量化指标，均为数组型输入） */
export function BackgroundItems() {
  return (
    <>
      <StringListField
        label="业务痛点"
        name={['background', 'painPoints']}
        addText="加一条痛点"
        placeholder="如：运营每月手工汇总 2000 行订单，耗时 2 天且易错"
      />
      <Form.Item
        label="影响范围"
        name={['background', 'impactScope']}
        rules={[{ required: true, message: '请描述影响范围（哪些角色/流程受影响）' }]}
      >
        <Input.TextArea
          rows={2}
          placeholder="如：客服部 30 人的工单流转；财务月末对账流程"
          maxLength={300}
          showCount
        />
      </Form.Item>
      <StringListField
        label="量化指标"
        name={['background', 'metrics']}
        addText="加一条指标"
        placeholder="如：处理时长从 2 天降到 10 分钟"
      />
    </>
  )
}

/** 第二步：期望功能（Form.List，每条 = 一个完整用户故事） */
export function FeatureItems() {
  return (
    <Form.List
      name="features"
      rules={[
        {
          validator: (_, value: unknown) =>
            (value as unknown[] | undefined)?.length
              ? Promise.resolve()
              : Promise.reject(new Error('至少填写一条期望功能（用户故事）')),
        },
      ]}
    >
      {(fields, { add, remove }, { errors }) => (
        <>
          {fields.map((field) => (
            <div className="req-form__story-card" key={field.key}>
              <div className="req-form__story-head">
                <span className="req-form__story-index">功能 {field.name + 1}</span>
                {fields.length > 1 && (
                  <Button
                    type="text"
                    danger
                    size="small"
                    icon={<DeleteOutlined />}
                    onClick={() => remove(field.name)}
                  >
                    移除
                  </Button>
                )}
              </div>
              <div style={{ display: 'grid', gridTemplateColumns: '1fr', rowGap: 12 }}>
                <Form.Item
                  label="作为（角色）"
                  name={[field.name, 'userStory', 'as']}
                  rules={[{ required: true, message: '用户故事缺「作为谁」' }]}
                >
                  <Input placeholder="如：客服专员" maxLength={50} />
                </Form.Item>
                <Form.Item
                  label="想要（动作）"
                  name={[field.name, 'userStory', 'want']}
                  rules={[{ required: true, message: '用户故事缺「想要什么」' }]}
                >
                  <Input placeholder="如：按日期范围批量导出工单" maxLength={120} />
                </Form.Item>
                <Form.Item
                  label="以便（价值）"
                  name={[field.name, 'userStory', 'so']}
                  rules={[{ required: true, message: '用户故事缺「达成什么价值」' }]}
                >
                  <Input placeholder="如：不用手工逐条复制粘贴" maxLength={120} />
                </Form.Item>
                <Form.Item label="补充细节" name={[field.name, 'details']}>
                  <Input.TextArea rows={2} placeholder="选填：边界、例外、交互细节" maxLength={500} />
                </Form.Item>
              </div>
            </div>
          ))}
          <Button type="dashed" block icon={<PlusOutlined />} onClick={() => add()}>
            加一条功能
          </Button>
          <Form.ErrorList errors={errors} />
        </>
      )}
    </Form.List>
  )
}

/** 第三步：验收标准（Form.List，每条 WHEN/THEN 对，闸门硬性要求 ≥1 条） */
export function AcceptanceItems() {
  return (
    <Form.List
      name="acceptance"
      rules={[
        {
          validator: (_, value: unknown) =>
            (value as unknown[] | undefined)?.length
              ? Promise.resolve()
              : Promise.reject(new Error('至少填写一条验收标准（WHEN/THEN）')),
        },
      ]}
    >
      {(fields, { add, remove }, { errors }) => (
        <>
          {fields.map((field) => (
            <div className="req-form__story-card" key={field.key}>
              <div className="req-form__story-head">
                <span className="req-form__story-index">验收 {field.name + 1}</span>
                {fields.length > 1 && (
                  <Button
                    type="text"
                    danger
                    size="small"
                    icon={<DeleteOutlined />}
                    onClick={() => remove(field.name)}
                  >
                    移除
                  </Button>
                )}
              </div>
              <Form.Item
                label="WHEN（什么条件下）"
                name={[field.name, 'when']}
                rules={[{ required: true, message: '验收标准缺 WHEN 条件' }]}
              >
                <Input placeholder="如：选择了日期范围且订单数 > 0 时点击导出" maxLength={200} />
              </Form.Item>
              <Form.Item
                label="THEN（期望的结果）"
                name={[field.name, 'then']}
                rules={[{ required: true, message: '验收标准缺 THEN 结果' }]}
              >
                <Input placeholder="如：10 秒内下载包含全部订单的 xlsx 文件" maxLength={200} />
              </Form.Item>
            </div>
          ))}
          <Button type="dashed" block icon={<PlusOutlined />} onClick={() => add()}>
            加一条验收标准
          </Button>
          <Form.ErrorList errors={errors} />
        </>
      )}
    </Form.List>
  )
}

/** 第三步：约束条件（技术约束/合规要求） */
export function ConstraintItems() {
  return (
    <>
      <StringListField
        label="技术约束"
        name={['constraints', 'technical']}
        addText="加一条技术约束"
        placeholder="如：不得新增外部中间件；数据量级 10 万行/月"
      />
      <StringListField
        label="合规要求"
        name={['constraints', 'compliance']}
        addText="加一条合规要求"
        placeholder="如：导出字段需脱敏（手机号打码）"
      />
    </>
  )
}

/** 字符串数组字段的统一形态：无索引卡片，每行一个 Input + 删除 */
function StringListField({
  label,
  name,
  addText,
  placeholder,
}: {
  label: string
  name: (string | number)[]
  addText: string
  placeholder: string
}) {
  return (
    <Form.Item label={label} required={false} style={{ marginBottom: 16 }}>
      <Form.List name={name}>
        {(fields, { add, remove }) => (
          <>
            {fields.map((field) => (
              <Form.Item key={field.key} noStyle>
                <div className="req-form__line-row">
                  <Input placeholder={placeholder} maxLength={300} />
                  <DeleteOutlined
                    className="req-form__line-remove"
                    onClick={() => remove(field.name)}
                  />
                </div>
              </Form.Item>
            ))}
            <Button type="dashed" block icon={<PlusOutlined />} onClick={() => add('')}>
              {addText}
            </Button>
          </>
        )}
      </Form.List>
    </Form.Item>
  )
}

/**
 * 表单回填归一化 —— 后端 JSON 里 null 数组/空串会破坏 Form.List 渲染，
 * 进表单前统一补全为空结构。编辑页/工坊页共用。
 */
export function normalizeForm(raw: Partial<RequirementFormVo> | null | undefined): RequirementFormVo {
  return {
    basic: {
      title: raw?.basic?.title ?? '',
      submitter: raw?.basic?.submitter ?? '',
      department: raw?.basic?.department ?? '',
      priority: raw?.basic?.priority ?? 'MEDIUM',
      expectDate: raw?.basic?.expectDate ?? '',
      capabilitySlug: raw?.basic?.capabilitySlug ?? '',
    },
    background: {
      painPoints: raw?.background?.painPoints ?? [''],
      impactScope: raw?.background?.impactScope ?? '',
      metrics: raw?.background?.metrics ?? [''],
    },
    features:
      raw?.features?.map((f) => ({
        userStory: { as: f?.userStory?.as ?? '', want: f?.userStory?.want ?? '', so: f?.userStory?.so ?? '' },
        details: f?.details ?? '',
      })) ?? [],
    acceptance:
      raw?.acceptance?.map((a) => ({ when: a?.when ?? '', then: a?.then ?? '' })) ?? [],
    constraints: {
      technical: raw?.constraints?.technical ?? [''],
      compliance: raw?.constraints?.compliance ?? [''],
    },
  }
}

/**
 * 提交前的清洗：去掉用户没填的空白行（空字符串数组项）与全空条目，
 * 避免把 [""] 存进 IR 渲染出空列表。
 */
export function cleanForm(form: RequirementFormVo): RequirementFormVo {
  const dropBlank = (list: string[] | undefined) =>
    (list ?? []).map((s) => (s ?? '').trim()).filter((s) => s.length > 0)
  return {
    basic: {
      ...form.basic,
      title: form.basic?.title?.trim() ?? '',
      submitter: form.basic?.submitter?.trim() ?? '',
      department: form.basic?.department?.trim() ?? '',
      capabilitySlug: form.basic?.capabilitySlug?.trim() ?? '',
      expectDate: form.basic?.expectDate ?? '',
    },
    background: {
      painPoints: dropBlank(form.background?.painPoints),
      impactScope: form.background?.impactScope?.trim() ?? '',
      metrics: dropBlank(form.background?.metrics),
    },
    features: (form.features ?? [])
      .map((f) => ({
        userStory: {
          as: f?.userStory?.as?.trim() ?? '',
          want: f?.userStory?.want?.trim() ?? '',
          so: f?.userStory?.so?.trim() ?? '',
        },
        details: f?.details?.trim() ?? '',
      }))
      .filter((f) => f.userStory.as || f.userStory.want || f.userStory.so || f.details),
    acceptance: (form.acceptance ?? [])
      .map((a) => ({ when: a?.when?.trim() ?? '', then: a?.then?.trim() ?? '' }))
      .filter((a) => a.when || a.then),
    constraints: {
      technical: dropBlank(form.constraints?.technical),
      compliance: dropBlank(form.constraints?.compliance),
    },
  }
}
