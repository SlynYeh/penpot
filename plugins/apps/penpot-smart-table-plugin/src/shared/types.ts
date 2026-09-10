/**
 * 数据契约：表格定义 JSON 的 TypeScript 固化类型。
 * 对应 specs/table-json-schema/spec.md 的全部约束。
 */

import { PRESET_IMAGES } from './constants'

/**
 * 支持的列类型。
 * UI 仅提供 COLUMN_TYPES 中的 9 种；multi-select / icon 保留为 legacy，
 * 仅用于解析与渲染旧数据，不再出现在新建列的可选项中。
 */
export type ColumnType =
  | 'text'
  | 'checkbox'
  | 'radio'
  | 'dropdown'
  | 'input'
  | 'action'
  | 'label'
  | 'image'
  | 'switch'
  // legacy（旧数据兼容）
  | 'multi-select'
  | 'icon'

/** UI 可选择的列类型（新建/切换列时显示）。 */
export const COLUMN_TYPES: readonly ColumnType[] = [
  'text',
  'checkbox',
  'radio',
  'dropdown',
  'input',
  'action',
  'label',
  'image',
  'switch',
]

/** 全部合法列类型（含 legacy 兼容类型，供解析/校验旧数据）。 */
export const ALL_COLUMN_TYPES: readonly ColumnType[] = [...COLUMN_TYPES, 'multi-select', 'icon']

/** 操作按钮定义：字符串（启用）或 { label, disabled }（可禁用）。 */
export type ActionButton = string | { label: string; disabled?: boolean }

/** 表格样式：full=全边框；horizontal=仅横向边框；striped=横向边框+相邻行不同背景。 */
export type TableStyle = 'full' | 'horizontal' | 'striped'

export const TABLE_STYLES: readonly TableStyle[] = ['full', 'horizontal', 'striped']

/** 表格主题：light=浅色系列（仅表头带点背景色）；dark=深色系列（默认，表头深底白字）。 */
export type TableTheme = 'light' | 'dark'

export const TABLE_THEMES: readonly TableTheme[] = ['light', 'dark']

/** 内置默认图标识前缀（单元格值形如 `preset:success`）。 */
const PRESET_PREFIX = 'preset:'

/** 内置样图种类（与 PRESET_IMAGES 表一一对应，共 9 张）。 */
export const IMAGE_PRESETS: readonly string[] = PRESET_IMAGES.map((p) => p.id)

/** 判断单元格值是否为内置默认图标识。 */
export function isImagePreset(value: string): boolean {
  return value.startsWith(PRESET_PREFIX)
}

/** 可视化图标集合（icon 列可选值，渲染为符号字符）。 */
export const ICON_SET: readonly string[] = [
  '⭐',
  '🔔',
  '⚙️',
  '👤',
  '📌',
  '❤️',
  '✅',
  '⚠️',
  '🔍',
  '📦',
  '💰',
  '🎯',
  '🚀',
  '📈',
  '🧩',
  '🛡️',
]

/** 单列定义。 */
export interface ColumnDefinition {
  /** 列标识，供 cells 键引用，如 `0:name`。 */
  id: string
  /** 表头显示标题。缺省时使用 id。 */
  title: string
  /** 列类型。缺省视为 text。 */
  type?: ColumnType
  /** 列宽（px）。缺省使用默认列宽。 */
  width?: number
  /** select / dropdown 列的选项列表。 */
  options?: string[]
  /** action 列的按钮定义列表（字符串或 { label, disabled }）。 */
  actions?: ActionButton[]
  /** input 列（文本输入框）的占位提示文本。 */
  placeholder?: string
}

/** 渲染布局模式（已废弃）：grid=扁平网格；flex=按行分组。统一按 grid 渲染。 */
export type TableLayoutMode = 'grid' | 'flex'

/** 表格定义顶层结构。 */
export interface TableDefinition {
  /** 表格名（可选）。 */
  name?: string
  /** 数据行数，>=1 的整数。 */
  rows: number
  /** 列定义数组，非空。 */
  columns: ColumnDefinition[]
  /**
   * 单元格内容，键为 `${行索引}:${列标识}`（如 `0:name`）。
   * 值类型与所在列类型匹配：文本类为 string，switch 为 boolean，action 无内容。
   */
  cells: Record<string, string | boolean>
  /** 是否在表格底部渲染分页器。缺省 false。 */
  pagination?: boolean
  /** 分页器每页展示条数。缺省 10。 */
  pageSize?: number
  /** 表格主题。缺省 'dark'（深色系列）。 */
  theme?: TableTheme
  /** 渲染布局模式。已废弃：渲染统一走高性能扁平 grid，本字段仅用于解析旧数据。 */
  layout?: TableLayoutMode
  /** 是否显示表头。缺省 true。 */
  showHeader?: boolean
  /** 表格样式。缺省 'full'。 */
  tableStyle?: TableStyle
}

/** 生成单元格键。 */
export function cellKey(rowIndex: number, columnId: string): string {
  return `${rowIndex}:${columnId}`
}

/** 取操作按钮显示文本。 */
export function actionButtonLabel(btn: ActionButton): string {
  return typeof btn === 'string' ? btn : btn.label
}

/** 操作按钮是否禁用。 */
export function actionButtonDisabled(btn: ActionButton): boolean {
  return typeof btn !== 'string' && btn.disabled === true
}

/** 解析单元格键中的行索引。返回 null 表示格式非法。 */
export function parseCellKeyRow(key: string): number | null {
  const m = /^(\d+):/.exec(key)
  if (!m) return null
  return Number(m[1])
}
