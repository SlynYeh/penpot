/**
 * 样式常量：集中管理表格渲染涉及的尺寸、颜色与限制。
 * 宿主渲染逻辑与 UI 摘要均引用本模块，保证视觉一致。
 * 对应设计 D7。
 */

export const STYLE = {
  /** 默认列宽（px）：新增列未指定宽度时使用 */
  defaultColumnWidth: 120,
  /** 默认数据行行高（px） */
  defaultRowHeight: 44,
  /** 表头行行高（px） */
  headerRowHeight: 44,
  /** 单元格横向内边距（px） */
  cellPadding: 10,
  /** 表头背景色 */
  headerBg: '#2f3542',
  /** 表头文字色 */
  headerText: '#ffffff',
  /** 表头字号 */
  headerFontSize: 13,
  /** 数据单元格背景色 */
  cellBg: '#ffffff',
  /** 数据单元格文字色 */
  cellText: '#24292f',
  /** 数据单元格文字字号 */
  fontSize: 13,
  /** 边框颜色 */
  borderColor: '#d0d7de',
  /** 边框宽度 */
  borderWidth: 1,
  /** 操作按钮背景色 */
  actionButtonBg: '#eef2f6',
  /** 操作按钮文字色 */
  actionButtonText: '#24292f',
  /** 操作按钮圆角 */
  actionButtonRadius: 4,
  /** 操作按钮内边距（px） */
  actionButtonPadding: 6,
  /** 标签（label）调色板：按值哈希取色 */
  labelPalette: ['#d4f0d4', '#ffe8d1', '#d1e6ff', '#fbe4e4', '#e8e4fb', '#fff3c4'],
  /** 标签文字色 */
  labelText: '#24292f',
  /** 标签横向内边距 */
  labelPaddingX: 8,
  /** 图标占位尺寸 */
  iconSize: 18,
  /** 图标占位颜色 */
  iconFill: '#8b949e',
  /** 图片占位背景色 */
  imagePlaceholderBg: '#eef1f4',
  /** 开关：轨道尺寸 */
  switchWidth: 36,
  switchHeight: 20,
  switchTrackOn: '#2da44e',
  switchTrackOff: '#d0d7de',
  switchKnob: '#ffffff',
  switchKnobDiameter: 16,
  /** 单元格圆角 */
  cellRadius: 2,

  /** 复选框勾选色 / 单选框选中色 */
  checkboxColor: '#2da44e',
  radioColor: '#4377d9',

  /** 表格样式：横向分隔线（horizontal/striped 样式） */
  hLineColor: '#d0d7de',
  hLineWidth: 1,
  /** 斑马纹交替行背景色（striped 样式） */
  stripedBg: '#f6f8fa',

  /** input（文本输入框）列渲染 */
  inputBg: '#ffffff',
  inputBorder: '#c9d1d9',
  inputPlaceholder: '#8b949e',

  /** action 按钮禁用态 */
  actionButtonDisabledBg: '#eef1f3',
  actionButtonDisabledText: '#9aa4b2',

  /** 内置样图（preset）兜底配色（详见 PRESET_IMAGES 表） */
  imagePresetBg: '#eef1f4',
  imagePresetFg: '#57606a',

  /** 分页器（表格底部条）配色 */
  footerBg: '#f6f8fa',
  footerText: '#57606a',
  paginationChipBg: '#eef2f6',
  primary: '#4377d9',

  /** 校验限制 */
  maxRows: 100,
  maxColumns: 20,
  /** 行 × 列 乘积上限（防超大表格，对应设计 R5） */
  maxCells: 500,
} as const

export type StyleConstants = typeof STYLE

/** 主题相关的渲染配色（渲染层按主题解析后使用）。 */
export interface ThemePalette {
  /** 表头背景色 */
  headerBg: string
  /** 表头文字色 */
  headerText: string
  /** 数据单元格背景色 */
  cellBg: string
  /** 数据单元格文字色 */
  cellText: string
  /** 单元格/边框颜色 */
  borderColor: string
  /** 横向分隔线颜色（horizontal/striped 样式） */
  hLineColor: string
  /** 斑马纹交替行背景色（striped 样式） */
  stripedBg: string
  /** 分页器底部条背景色 */
  footerBg: string
}

/** 深色系列（默认，即现有样式）：表头深底白字。 */
const DARK_PALETTE: ThemePalette = {
  headerBg: STYLE.headerBg,
  headerText: STYLE.headerText,
  cellBg: STYLE.cellBg,
  cellText: STYLE.cellText,
  borderColor: STYLE.borderColor,
  hLineColor: STYLE.hLineColor,
  stripedBg: STYLE.stripedBg,
  footerBg: STYLE.footerBg,
}

/** 浅色系列（默认）：仅表头带很淡的背景色，数据区更亮、边框更浅。 */
const LIGHT_PALETTE: ThemePalette = {
  headerBg: '#f4f6f9',
  headerText: '#24292f',
  cellBg: '#ffffff',
  cellText: '#24292f',
  borderColor: '#e6ebf0',
  hLineColor: '#e6ebf0',
  stripedBg: '#f7f9fb',
  footerBg: '#f7f9fb',
}

/** 按主题解析渲染配色（缺省为浅色系列）。 */
export function resolveTheme(theme?: string): ThemePalette {
  return theme === 'dark' ? DARK_PALETTE : LIGHT_PALETTE
}

/** 内置样图定义（图片列图库：上传失败/占位图/其他样图，共 9 张）。 */
export interface PresetImageDef {
  /** preset 标识，单元格值形如 `preset:success` */
  id: string
  /** 图库中显示的名称 */
  label: string
  /** 中央符号 */
  symbol: string
  /** 背景色 */
  bg: string
  /** 符号前景色 */
  fg: string
}

/** 9 张内置样图：成功/失败/占位/信息/警告/锁定/下载/星标/时钟。 */
export const PRESET_IMAGES: readonly PresetImageDef[] = [
  { id: 'success', label: '成功', symbol: '✓', bg: '#dafbe1', fg: '#1a7f37' },
  { id: 'fail', label: '上传失败', symbol: '✕', bg: '#ffebe9', fg: '#cf222e' },
  { id: 'placeholder', label: '占位图', symbol: '图', bg: '#eef1f4', fg: '#57606a' },
  { id: 'info', label: '信息', symbol: 'ℹ', bg: '#ddf4ff', fg: '#0969da' },
  { id: 'warning', label: '警告', symbol: '⚠', bg: '#fff8c5', fg: '#9a6700' },
  { id: 'lock', label: '锁定', symbol: '🔒', bg: '#eef1f4', fg: '#57606a' },
  { id: 'download', label: '下载', symbol: '⬇', bg: '#e6f4ea', fg: '#1a7f37' },
  { id: 'star', label: '星标', symbol: '★', bg: '#fff8c5', fg: '#9a6700' },
  { id: 'clock', label: '时钟', symbol: '⏱', bg: '#eef1f4', fg: '#57606a' },
]

/** 按 preset 标识查内置样图；未知标识返回 undefined。 */
export function presetImage(id: string): PresetImageDef | undefined {
  return PRESET_IMAGES.find((p) => p.id === id)
}
