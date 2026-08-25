/**
 * 内置场景模板：点击后载入表单进行编辑再导入。
 * 所有模板默认提供 10 条模拟数据；图片列使用内置默认图（preset），不依赖外网。
 */

import { cellKey, type ColumnDefinition, type TableDefinition, type TableStyle, type TableTheme } from './types'

export interface TableTemplate {
  id: string
  name: string
  desc: string
  /** 缩略图主色 */
  accent: string
  table: TableDefinition
}

function buildTable(
  name: string,
  columns: ColumnDefinition[],
  data: Array<Array<string | boolean>>,
  opts?: {
    showHeader?: boolean
    tableStyle?: TableStyle
    theme?: TableTheme
    pagination?: boolean
    pageSize?: number
  },
): TableDefinition {
  const cells: Record<string, string | boolean> = {}
  data.forEach((row, r) => {
    columns.forEach((col, c) => {
      const v = row[c]
      if (v !== undefined && v !== '') cells[cellKey(r, col.id)] = v
    })
  })
  return { name, rows: data.length, columns, cells, ...opts }
}

const text = (id: string, title: string, width?: number): ColumnDefinition => ({
  id,
  title,
  type: 'text',
  width,
})

const IMG = {
  ok: 'preset:success',
  no: 'preset:placeholder',
  warn: 'preset:fail',
} as const

export const TABLE_TEMPLATES: TableTemplate[] = [
  {
    id: 'all-types',
    name: '全类型示例',
    desc: '覆盖全部 9 种列类型 · 分页器',
    accent: '#4377d9',
    table: buildTable(
      '全类型示例',
      [
        { id: 'check', title: '复选', type: 'checkbox', width: 70 },
        { id: 'radio', title: '单选', type: 'radio', width: 100 },
        text('text', '文本', 150),
        { id: 'dropdown', title: '下拉', type: 'dropdown', options: ['待办', '进行中', '已完成'], width: 110 },
        { id: 'input', title: '文本输入框', type: 'input', width: 190, placeholder: '请输入…' },
        { id: 'label', title: '标签', type: 'label', width: 90 },
        { id: 'image', title: '图片', type: 'image', width: 64 },
        { id: 'switch', title: '开关', type: 'switch', width: 80 },
        { id: 'actions', title: '操作', type: 'action', actions: ['编辑', '删除'], width: 130 },
      ],
      [
        [true, true, '接口联调', '进行中', '进度 80%', '核心', IMG.ok, true, ''],
        [false, false, '前端页面', '待办', '', '常规', IMG.no, false, ''],
        [true, true, '数据库设计', '进行中', '评审中', '重点', IMG.ok, true, ''],
        [false, true, '文档整理', '已完成', '', '新锐', IMG.no, true, ''],
        [true, false, '性能优化', '进行中', '压测中', '高', IMG.warn, false, ''],
        [false, true, '测试用例', '待办', '', '常规', IMG.no, false, ''],
        [true, true, '部署上线', '已完成', '已发布', '核心', IMG.ok, true, ''],
        [false, false, '需求评审', '待办', '', '重点', IMG.no, false, ''],
        [true, true, '日志监控', '进行中', '接入中', '常规', IMG.ok, true, ''],
        [false, true, '安全加固', '已完成', '已验收', '高', IMG.ok, false, ''],
      ],
      { tableStyle: 'full', pagination: true, pageSize: 5 },
    ),
  },
  {
    id: 'users',
    name: '用户信息',
    desc: '用户资料：性别/部门/角色/在职',
    accent: '#2da44e',
    table: buildTable(
      '用户信息',
      [
        text('name', '姓名', 100),
        { id: 'gender', title: '性别', type: 'dropdown', options: ['男', '女'], width: 80 },
        text('age', '年龄', 60),
        { id: 'dept', title: '部门', type: 'dropdown', options: ['产品部', '设计部', '研发部', '运营部'], width: 100 },
        { id: 'role', title: '角色', type: 'label', width: 90 },
        { id: 'active', title: '在职', type: 'switch', width: 70 },
        { id: 'note', title: '备注', type: 'input', width: 160, placeholder: '如：休假中…' },
        { id: 'avatar', title: '头像', type: 'image', width: 64 },
        { id: 'actions', title: '操作', type: 'action', actions: ['详情'], width: 90 },
      ],
      [
        ['张伟', '男', '28', '产品部', '产品经理', true, '', IMG.no, ''],
        ['李娜', '女', '25', '设计部', 'UI 设计师', true, '', IMG.ok, ''],
        ['王强', '男', '31', '研发部', '前端工程师', true, '核心骨干', IMG.no, ''],
        ['赵敏', '女', '26', '运营部', '运营专员', true, '', IMG.ok, ''],
        ['陈晨', '男', '35', '研发部', '后端工程师', true, '轮岗中', IMG.no, ''],
        ['刘洋', '女', '29', '产品部', '数据分析师', false, '产假中', IMG.no, ''],
        ['孙浩', '男', '24', '设计部', '交互设计师', true, '', IMG.ok, ''],
        ['周婷', '女', '27', '运营部', '市场专员', true, '', IMG.no, ''],
        ['吴磊', '男', '33', '研发部', '测试工程师', false, '外派中', IMG.warn, ''],
        ['郑爽', '女', '23', '产品部', '产品助理', true, '试用期', IMG.no, ''],
      ],
      { theme: 'light' },
    ),
  },
  {
    id: 'orders',
    name: '订单管理',
    desc: '订单号/金额/状态流转/加急',
    accent: '#d97706',
    table: buildTable(
      '订单管理',
      [
        { id: 'urgent', title: '加急', type: 'checkbox', width: 70 },
        text('no', '订单号', 150),
        text('customer', '客户', 110),
        { id: 'amount', title: '金额', type: 'label', width: 90 },
        { id: 'status', title: '状态', type: 'dropdown', options: ['待支付', '已支付', '已发货', '已完成'], width: 100 },
        text('date', '日期', 100),
        { id: 'img', title: '图片', type: 'image', width: 64 },
        { id: 'actions', title: '操作', type: 'action', actions: ['发货'], width: 90 },
      ],
      [
        [true, 'PO-2026-0001', '华信科技', '¥1,280', '已支付', '2026-08-01', IMG.ok, ''],
        [false, 'PO-2026-0002', '云启网络', '¥860', '待支付', '2026-08-02', IMG.no, ''],
        [true, 'PO-2026-0003', '锐达软件', '¥3,420', '已发货', '2026-08-03', IMG.no, ''],
        [false, 'PO-2026-0004', '澜创设计', '¥520', '已完成', '2026-08-05', IMG.ok, ''],
        [false, 'PO-2026-0005', '星云数据', '¥2,150', '待支付', '2026-08-06', IMG.no, ''],
        [true, 'PO-2026-0006', '中科智联', '¥980', '已发货', '2026-08-08', IMG.warn, ''],
        [false, 'PO-2026-0007', '青橙传媒', '¥6,800', '已支付', '2026-08-10', IMG.no, ''],
        [true, 'PO-2026-0008', '恒宇科技', '¥1,560', '已完成', '2026-08-12', IMG.ok, ''],
        [false, 'PO-2026-0009', '蓝湾贸易', '¥3,100', '已发货', '2026-08-15', IMG.no, ''],
        [true, 'PO-2026-0010', '先锋电子', '¥2,600', '待支付', '2026-08-18', IMG.no, ''],
      ],
      { tableStyle: 'striped' },
    ),
  },
  {
    id: 'projects',
    name: '项目列表',
    desc: '项目/负责人/状态/优先级',
    accent: '#8250df',
    table: buildTable(
      '项目列表',
      [
        text('name', '项目名称', 180),
        { id: 'owner', title: '负责人', type: 'dropdown', options: ['张伟', '李娜', '王强'], width: 90 },
        { id: 'status', title: '状态', type: 'dropdown', options: ['进行中', '已完成', '待启动'], width: 100 },
        { id: 'priority', title: '优先级', type: 'label', width: 80 },
        { id: 'active', title: '启用', type: 'switch', width: 70 },
        text('tags', '标签', 110),
        { id: 'actions', title: '操作', type: 'action', actions: ['编辑', '删除'], width: 110 },
      ],
      [
        ['原型表格 插件', '张伟', '进行中', '高', true, '核心,重点', ''],
        ['官网改版', '李娜', '进行中', '中', true, '重点', ''],
        ['数据看板', '王强', '待启动', '低', true, '常规', ''],
        ['会员体系 V2', '张伟', '进行中', '高', true, '核心', ''],
        ['移动端 App', '李娜', '进行中', '中', true, '重点', ''],
        ['运维监控平台', '王强', '待启动', '低', false, '常规', ''],
        ['开放平台', '张伟', '已完成', '高', false, '核心,重点', ''],
        ['客服系统升级', '李娜', '进行中', '中', true, '常规', ''],
        ['数据仓库建设', '王强', '待启动', '高', true, '重点', ''],
        ['品牌官网', '张伟', '已完成', '低', false, '常规', ''],
      ],
      { tableStyle: 'full' },
    ),
  },
]

/** 生成模板缩略图（mini 表格 SVG，用作 data URI）。 */
export function thumbSvg(accent: string, cols: number, rows: number): string {
  const w = 200
  const h = 120
  const cw = w / Math.max(cols, 1)
  const ch = (h - 26) / Math.max(rows, 1)
  const cell = (x: number, y: number, wd: number, ht: number, fill: string) =>
    `<rect x="${x.toFixed(1)}" y="${y.toFixed(1)}" width="${wd.toFixed(1)}" height="${ht.toFixed(1)}" fill="${fill}" rx="2"/>`
  let parts = `<svg xmlns="http://www.w3.org/2000/svg" width="${w}" height="${h}" viewBox="0 0 ${w} ${h}">`
  parts += `<rect width="${w}" height="${h}" fill="#ffffff"/>`
  parts += cell(0, 0, w, 24, accent)
  for (let c = 0; c < cols; c++) parts += cell(c * cw, 2, cw - 1, 20, 'rgba(255,255,255,0.28)')
  for (let r = 0; r < rows; r++) {
    const y = 26 + r * ch
    const fill = ['#f6f8fa', '#eef2f6'][r % 2]
    parts += cell(0, y, w, ch - 2, fill)
    for (let c = 0; c < cols; c++) {
      if ((c + r) % 3 === 0) parts += cell(c * cw + 3, y + 4, cw - 8, 8, accent)
    }
  }
  parts += `</svg>`
  return parts
}
