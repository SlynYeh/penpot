/**
 * 手写校验器：将表格定义 JSON 文本解析为 TableDefinition，并输出中文字段级错误。
 * UI 与宿主共用本模块（设计 D2）。零运行时依赖。
 */

import { STYLE } from './constants'
import {
  ALL_COLUMN_TYPES,
  COLUMN_TYPES,
  IMAGE_PRESETS,
  TABLE_STYLES,
  TABLE_THEMES,
  cellKey,
  isImagePreset,
  type ActionButton,
  type ColumnDefinition,
  type ColumnType,
  type TableDefinition,
  type TableStyle,
  type TableTheme,
} from './types'

export type ValidationResult =
  | { ok: true; table: TableDefinition }
  | { ok: false; errors: string[] }

const isRecord = (v: unknown): v is Record<string, unknown> =>
  typeof v === 'object' && v !== null && !Array.isArray(v)

/** 解析并校验表格定义 JSON 文本。 */
export function parseAndValidate(jsonText: string): ValidationResult {
  let raw: unknown
  try {
    raw = JSON.parse(jsonText)
  } catch (e) {
    return {
      ok: false,
      errors: [`JSON 解析失败：${e instanceof Error ? e.message : String(e)}`],
    }
  }
  return validateTable(raw)
}

/** 校验已解析的表格定义对象。 */
export function validateTable(raw: unknown): ValidationResult {
  const errors: string[] = []
  if (!isRecord(raw)) {
    return { ok: false, errors: ['JSON 顶层必须是对象'] }
  }

  // rows
  if (raw.rows === undefined) errors.push('缺少字段 rows')
  let rows: number
  if (isPositiveInteger(raw.rows)) {
    rows = raw.rows
    if (rows > STYLE.maxRows) errors.push(`rows ${rows} 超过上限 ${STYLE.maxRows}`)
  } else {
    errors.push('rows 必须是不小于 1 的整数')
    rows = 1
  }

  // columns
  if (raw.columns === undefined) errors.push('缺少字段 columns')
  let cols: ColumnDefinition[] = []
  if (Array.isArray(raw.columns)) {
    if (raw.columns.length === 0) errors.push('columns 不能为空，至少需要一列')
    else if (raw.columns.length > STYLE.maxColumns)
      errors.push(`列数 ${raw.columns.length} 超过上限 ${STYLE.maxColumns}`)
    cols = validateColumns(raw.columns, errors)
  } else if (raw.columns !== undefined) {
    errors.push('columns 必须是数组')
  }

  // 行列乘积上限（防超大表格，设计 R5）
  if (errors.length === 0) {
    const product = rows * cols.length
    if (product > STYLE.maxCells)
      errors.push(`行列数乘积 ${rows} × ${cols.length} = ${product} 超过上限 ${STYLE.maxCells}`)
  }

  // 渲染选项
  if (raw.pagination !== undefined && typeof raw.pagination !== 'boolean')
    errors.push('pagination 必须是布尔值')
  if (
    raw.pageSize !== undefined &&
    (typeof raw.pageSize !== 'number' || !Number.isInteger(raw.pageSize) || raw.pageSize < 1)
  )
    errors.push('pageSize 必须是不小于 1 的整数')
  if (raw.layout !== undefined && raw.layout !== 'grid' && raw.layout !== 'flex')
    errors.push(`layout '${String(raw.layout)}' 不受支持（可选：grid/flex）`)
  if (raw.showHeader !== undefined && typeof raw.showHeader !== 'boolean')
    errors.push('showHeader 必须是布尔值')
  if (raw.tableStyle !== undefined && !TABLE_STYLES.includes(raw.tableStyle as TableStyle))
    errors.push(`tableStyle '${String(raw.tableStyle)}' 不受支持（可选：full/horizontal/striped）`)
  if (raw.theme !== undefined && !TABLE_THEMES.includes(raw.theme as TableTheme))
    errors.push(`theme '${String(raw.theme)}' 不受支持（可选：light/dark）`)

  // cells
  const cellCount = validateCells(raw, rows, cols, errors)

  if (errors.length > 0) return { ok: false, errors }

  return {
    ok: true,
    table: {
      name: typeof raw.name === 'string' && raw.name.trim() !== '' ? raw.name.trim() : undefined,
      rows,
      columns: cols,
      cells: cellCount,
      pagination: raw.pagination === true,
      pageSize:
        typeof raw.pageSize === 'number' && Number.isInteger(raw.pageSize) && raw.pageSize >= 1
          ? raw.pageSize
          : undefined,
      theme: TABLE_THEMES.includes(raw.theme as TableTheme) ? (raw.theme as TableTheme) : 'light',
      layout: raw.layout === 'flex' ? 'flex' : 'grid',
      showHeader: raw.showHeader === false ? false : true,
      tableStyle: TABLE_STYLES.includes(raw.tableStyle as TableStyle)
        ? (raw.tableStyle as TableStyle)
        : 'full',
    },
  }
}

function validateColumns(rawColumns: unknown[], errors: string[]): ColumnDefinition[] {
  const seen = new Set<string>()
  return rawColumns.map((c, i) => {
    const idx = i + 1
    if (!isRecord(c)) {
      errors.push(`第 ${idx} 列必须是对象`)
      return { id: `c${idx}`, title: `列${idx}`, type: 'text' }
    }

    let id = 'c' + idx
    if (c.id === undefined) errors.push(`第 ${idx} 列缺少 id`)
    else if (typeof c.id !== 'string' || c.id.trim() === '') {
      errors.push(`第 ${idx} 列 id 必须是非空字符串`)
    } else {
      id = c.id.trim()
      if (seen.has(id)) errors.push(`第 ${idx} 列 id 重复：${id}`)
      seen.add(id)
    }

    const title = c.title === undefined ? id : c.title
    if (c.title !== undefined && (typeof title !== 'string' || title.trim() === ''))
      errors.push(`第 ${idx} 列 title 必须是非空字符串`)

    let type: ColumnType = 'text'
    if (c.type !== undefined) {
      // 校验用全量集合（含 legacy 类型，旧数据可解析）；提示可选时列 UI 支持的 9 种
      if (typeof c.type !== 'string' || !ALL_COLUMN_TYPES.includes(c.type as ColumnType)) {
        errors.push(`第 ${idx} 列 type '${String(c.type)}' 不受支持（可选：${COLUMN_TYPES.join('/')}）`)
      } else {
        type = c.type as ColumnType
      }
    }

    if (c.width !== undefined && (typeof c.width !== 'number' || !Number.isFinite(c.width) || c.width <= 0))
      errors.push(`第 ${idx} 列 width 必须是正数`)

    if (c.options !== undefined && !isStringArray(c.options))
      errors.push(`第 ${idx} 列 options 必须是字符串数组`)

    if (c.actions !== undefined && !isActionArray(c.actions))
      errors.push(`第 ${idx} 列 actions 必须是字符串或 { label, disabled } 数组`)

    if (c.placeholder !== undefined && (typeof c.placeholder !== 'string' || c.placeholder.trim() === ''))
      errors.push(`第 ${idx} 列 placeholder 必须是非空字符串`)

    return {
      id,
      title: typeof title === 'string' ? title : id,
      type,
      width: typeof c.width === 'number' ? c.width : undefined,
      options: isStringArray(c.options) ? c.options : undefined,
      actions: isActionArray(c.actions) ? c.actions : undefined,
      placeholder:
        typeof c.placeholder === 'string' && c.placeholder.trim() !== ''
          ? c.placeholder.trim()
          : undefined,
    }
  })
}

function validateCells(
  raw: Record<string, unknown>,
  rows: number,
  cols: ColumnDefinition[],
  errors: string[],
): Record<string, string | boolean> {
  const byId = new Map(cols.map((c) => [c.id, c]))
  const cellKeys = new Set<string>()
  const result: Record<string, string | boolean> = {}

  const rawCells = raw.cells === undefined ? {} : raw.cells
  if (raw.cells !== undefined && !isRecord(rawCells))
    errors.push('cells 必须是对象')
  if (!isRecord(rawCells)) return result

  for (const key of Object.keys(rawCells)) {
    const m = /^(\d+):(.+)$/.exec(key)
    if (!m) {
      errors.push(`单元格键 ${key} 格式非法（应为 行索引:列标识，如 0:name）`)
      continue
    }
    const rowIdx = Number(m[1])
    const columnId = m[2]
    const col = byId.get(columnId)
    if (!col) {
      errors.push(`第${rowIdx + 1}行引用了不存在的列 "${columnId}"`)
      continue
    }
    if (rowIdx >= rows) {
      errors.push(`第${rowIdx + 1}行超出表格行数（rows=${rows}）`)
      continue
    }
    if (cellKeys.has(key)) {
      errors.push(`第${rowIdx + 1}行·${col.title} 重复定义`)
      continue
    }
    cellKeys.add(key)

    const value = rawCells[key]
    // 语义化错误定位：第 X 行 · 列标题，替代机器键（如 0:c3）
    const check = checkCellValue(`第${rowIdx + 1}行·${col.title}`, col, value)
    if (check) errors.push(check)
    else result[key] = value as string | boolean
  }
  return result
}

function checkCellValue(where: string, col: ColumnDefinition, value: unknown): string | null {
  switch (col.type) {
    case 'action':
      // action 列无需单元格内容，存在值也被忽略
      return null
    case 'switch':
    case 'checkbox':
      if (typeof value !== 'boolean') return `${where} 应为布尔值 true/false`
      return null
    case 'image': {
      if (typeof value !== 'string' || value.trim() === '')
        return `${where} 应为图片 URL 或内置默认图标识`
      if (isImagePreset(value)) {
        const preset = value.slice('preset:'.length)
        if (!IMAGE_PRESETS.includes(preset))
          return `${where} 的内置默认图标识 ${JSON.stringify(value)} 不受支持（可选：${IMAGE_PRESETS.map((p) => `preset:${p}`).join('/')}）`
      }
      return null
    }
    case 'dropdown': {
      if (typeof value !== 'string') return `${where} 应为字符串`
      if (col.options && col.options.length > 0 && !col.options.includes(value))
        return `${where} 的值 ${JSON.stringify(value)} 不在选项范围内`
      return null
    }
    case 'radio':
      // 单选框与复选框一样，单元格值为布尔勾选态
      if (typeof value !== 'boolean') return `${where} 应为布尔值 true/false`
      return null
    case 'multi-select': {
      if (typeof value !== 'string') return `${where} 应为字符串（逗号分隔的选项）`
      if (col.options && col.options.length > 0) {
        for (const part of value.split(',').map((s) => s.trim()).filter(Boolean)) {
          if (!col.options.includes(part))
            return `${where} 的选项 ${JSON.stringify(part)} 不在范围内`
        }
      }
      return null
    }
    default:
      if (typeof value !== 'string') return `${where} 应为字符串`
      return null
  }
}

function isStringArray(v: unknown): v is string[] {
  return Array.isArray(v) && v.every((x) => typeof x === 'string' && x.trim() !== '')
}

/** action 按钮数组：每项为字符串或 { label, disabled? }，label 必须为非空字符串。 */
function isActionArray(v: unknown): v is ActionButton[] {
  if (!Array.isArray(v)) return false
  return v.every((x) => {
    if (typeof x === 'string') return x.trim() !== ''
    if (typeof x === 'object' && x !== null) {
      const o = x as Record<string, unknown>
      return (
        typeof o.label === 'string' &&
        o.label.trim() !== '' &&
        (o.disabled === undefined || typeof o.disabled === 'boolean')
      )
    }
    return false
  })
}

function isPositiveInteger(v: unknown): v is number {
  return typeof v === 'number' && Number.isInteger(v) && v >= 1
}

/** 生成一份覆盖常用列类型的示例表格 JSON（供参考/测试）。 */
export function sampleTableJson(): string {
  const table: TableDefinition = {
    name: '示例项目列表',
    rows: 4,
    columns: [
      { id: 'name', title: '项目名称', type: 'text', width: 180 },
      { id: 'owner', title: '负责人', type: 'dropdown', options: ['张伟', '李娜', '王强'], width: 110 },
      { id: 'status', title: '状态', type: 'dropdown', options: ['进行中', '已完成', '待启动'], width: 110 },
      { id: 'tags', title: '标签', type: 'multi-select', options: ['核心', '重点', '常规'], width: 120 },
      { id: 'progress', title: '优先级', type: 'label', width: 90 },
      { id: 'active', title: '启用', type: 'checkbox', width: 80 },
      { id: 'avatar', title: '头像', type: 'image', width: 64 },
      { id: 'actions', title: '操作', type: 'action', actions: ['编辑', '删除'], width: 130 },
    ],
    cells: {
      [cellKey(0, 'name')]: '原型表格 插件',
      [cellKey(0, 'owner')]: '张伟',
      [cellKey(0, 'status')]: '进行中',
      [cellKey(0, 'tags')]: '核心,重点',
      [cellKey(0, 'progress')]: '高',
      [cellKey(0, 'active')]: true,
      [cellKey(0, 'avatar')]: 'https://picsum.photos/seed/1/64/44',
      [cellKey(1, 'name')]: '官网改版',
      [cellKey(1, 'owner')]: '李娜',
      [cellKey(1, 'status')]: '已完成',
      [cellKey(1, 'tags')]: '重点',
      [cellKey(1, 'progress')]: '中',
      [cellKey(1, 'active')]: false,
      [cellKey(1, 'avatar')]: 'https://picsum.photos/seed/2/64/44',
      [cellKey(2, 'name')]: '数据看板',
      [cellKey(2, 'owner')]: '王强',
      [cellKey(2, 'status')]: '待启动',
      [cellKey(2, 'tags')]: '常规',
      [cellKey(2, 'progress')]: '低',
      [cellKey(2, 'active')]: true,
      [cellKey(2, 'avatar')]: 'https://picsum.photos/seed/3/64/44',
      [cellKey(3, 'name')]: '会员体系 V2',
      [cellKey(3, 'owner')]: '张伟',
      [cellKey(3, 'status')]: '进行中',
      [cellKey(3, 'tags')]: '核心',
      [cellKey(3, 'progress')]: '高',
      [cellKey(3, 'active')]: true,
      [cellKey(3, 'avatar')]: 'https://picsum.photos/seed/4/64/44',
    },
  }
  return JSON.stringify(table, null, 2)
}
