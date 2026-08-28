/**
 * 宿主脚本：运行于 Penpot 主上下文，负责把经过校验的表格定义渲染到画布。
 * 通过 penpot.ui 桥与 UI 页面通信（对应设计 D3）。
 *
 * 多表格模型（对应 specs/table-edit-workflow）：
 * - 每次渲染在画布上新增一个表格组，组的 pluginData 保存其 JSON；
 * - 面板所需的表格列表由「扫描页面」得出（可在插件重开、多表格并存时保持正确）；
 * - 「保存重渲」用原表格的 x/y 创建新组并移除旧组，坐标保持不变。
 */

import type { Group, Rectangle, Shape, Text } from '@penpot/plugin-types'
import { presetImage, resolveTheme, STYLE, type ThemePalette } from './shared/constants'
import { computeLayout, type CellLayout, type Rect, type TableLayout } from './shared/layout'
import {
  actionButtonDisabled,
  actionButtonLabel,
  cellKey,
  isImagePreset,
  type ActionButton,
  type ColumnDefinition,
  type TableDefinition,
  type TableStyle,
} from './shared/types'
import { parseAndValidate } from './shared/validator'
import type { HostToUiMessage, TableInfoMsg, UiToHostMessage } from './shared/messaging'

/** 表格组上存储插件数据的键：值为该表格的 JSON 原文。 */
const PLUGIN_DATA_KEY = 'smart-table:json'
/** 表格创建序号（用于默认命名与按创建时间排序）。 */
const SEQ_DATA_KEY = 'smart-table:seq'

/** 面板当前正在编辑的表格 id（null 表示新建表格模式）。 */
let activeTableId: string | null = null
/** 用户显式进入「新建」后保持该意图，避免状态回退到画布选中的旧表格。 */
let newTableIntent = false

/* ---------------------------------- 桥 ---------------------------------- */

function sendToUi(msg: HostToUiMessage): void {
  try {
    penpot.ui.sendMessage(msg)
  } catch {
    // UI 可能已关闭，忽略
  }
}

/* ------------------------------- 基础形状 ------------------------------ */

function createRect(
  rect: Rect,
  fill: string,
  opts: { stroke?: boolean; radius?: number; borderColor?: string } = {},
): Rectangle {
  const shape = penpot.createRectangle()
  shape.name = 'smart-table-cell'
  shape.x = rect.x
  shape.y = rect.y
  shape.resize(rect.width, rect.height)
  shape.fills = [{ fillColor: fill, fillOpacity: 1 }]
  shape.strokes =
    opts.stroke === false
      ? []
      : [
          {
            strokeColor: opts.borderColor ?? STYLE.borderColor,
            strokeStyle: 'solid',
            strokeWidth: STYLE.borderWidth,
            strokeAlignment: 'center',
          },
        ]
  shape.borderRadius = opts.radius ?? STYLE.cellRadius
  return shape
}

function createTextInCell(
  rect: Rect,
  text: string,
  opts: {
    color?: string
    fontSize?: number
    bold?: boolean
    center?: boolean
    padding?: number
    /** 右侧预留（用于给下拉控件/指示让位），避免内容溢出重叠。 */
    rightInset?: number
  } = {},
): Text | null {
  const color = opts.color ?? STYLE.cellText
  const fontSize = opts.fontSize ?? STYLE.fontSize
  const padding = opts.padding ?? STYLE.cellPadding
  const rightInset = opts.rightInset ?? 0
  const lineHeight = Math.round(fontSize * 1.35)
  const shape = penpot.createText(text)
  if (!shape) return null
  shape.name = 'smart-table-text'
  shape.x = rect.x + padding
  shape.y = rect.y + (rect.height - lineHeight) / 2
  shape.resize(Math.max(rect.width - padding * 2 - rightInset, 10), lineHeight)
  shape.growType = 'fixed'
  shape.fontSize = String(fontSize)
  shape.fontWeight = opts.bold ? '600' : '400'
  shape.align = opts.center ? 'center' : 'left'
  shape.verticalAlign = 'center'
  shape.fills = [{ fillColor: color, fillOpacity: 1 }]
  return shape
}

/* ----------------------------- 列类型渲染 ------------------------------ */

function buildCell(
  cell: CellLayout,
  col: ColumnDefinition,
  value: string | boolean | undefined,
): Shape[] {
  // 单元格背景与网格线由 buildTableGroup 统一绘制（扁平高性能：节点最少）；
  // 本函数只产内容形状（文本/控件）。外部 URL 图片由调用方收集后并发加载。
  const shapes: Shape[] = []
  switch (col.type ?? 'text') {
    case 'text': {
      const t = createTextInCell(cell, valueString(value))
      if (t) shapes.push(t)
      break
    }
    case 'dropdown': {
      // 空值时默认显示第一个有效选项（值为空，仅视觉默认）
      const shown = valueString(value) || col.options?.[0] || ''
      const t = createTextInCell(cell, shown, { rightInset: 30 })
      if (t) shapes.push(t)
      shapes.push(...createDropdownControl(cell))
      break
    }
    case 'radio':
      // 单选框与复选框一致：值为布尔勾选态，渲染为圆形勾选框
      shapes.push(...createRadio(cell, value === true))
      break
    case 'multi-select': {
      shapes.push(...createMultiSelectChips(cell, valueString(value)))
      break
    }
    case 'checkbox':
      shapes.push(...createCheckbox(cell, value === true))
      break
    case 'label':
      shapes.push(...createLabel(cell, valueString(value)))
      break
    case 'switch':
      shapes.push(...createSwitch(cell, value === true))
      break
    case 'action':
      shapes.push(...createActionButtons(cell, col.actions ?? []))
      break
    case 'image': {
      const url = valueString(value).trim()
      if (!url) shapes.push(...createPresetImage(cell, 'placeholder'))
      else if (isImagePreset(url))
        shapes.push(...createPresetImage(cell, url.slice('preset:'.length)))
      // 外部 URL 图片在 buildTableGroup 中收集后并发 uploadMediaUrl
      break
    }
    case 'icon':
      shapes.push(...createIcon(cell, valueString(value)))
      break
    case 'input':
      shapes.push(...createInput(cell, valueString(value), col.placeholder))
      break
  }
  return shapes
}

function valueString(v: string | boolean | undefined): string {
  return v === undefined || v === null ? '' : String(v)
}

/** 下拉控件：右侧一个圆角色块 + ▾，模拟真实下拉框。 */
function createDropdownControl(cell: CellLayout): Shape[] {
  const w = 24
  const h = 18
  const x = cell.x + cell.width - STYLE.cellPadding - w
  const y = cell.y + (cell.height - h) / 2
  const shapes: Shape[] = [
    createRect({ x, y, width: w, height: h }, STYLE.actionButtonBg, { stroke: false, radius: 4 }),
  ]
  const t = penpot.createText('▾')
  if (t) {
    t.x = x
    t.y = y
    t.resize(w, h)
    t.growType = 'fixed'
    t.fontSize = '13'
    t.align = 'center'
    t.verticalAlign = 'center'
    t.fills = [{ fillColor: STYLE.footerText, fillOpacity: 1 }]
    shapes.push(t)
  }
  return shapes
}

/** 复选框：圆角方块 + 勾选标记（列内水平居中）。 */
function createCheckbox(cell: CellLayout, on: boolean): Shape[] {
  const s = 16
  const x = cell.x + (cell.width - s) / 2
  const y = cell.y + (cell.height - s) / 2
  const shapes: Shape[] = [
    createRect({ x, y, width: s, height: s }, on ? STYLE.checkboxColor : '#ffffff', { radius: 4 }),
  ]
  if (on) {
    const t = penpot.createText('✓')
    if (t) {
      t.x = x
      t.y = y
      t.resize(s, s)
      t.growType = 'fixed'
      t.fontSize = '12'
      t.fontWeight = '600'
      t.align = 'center'
      t.verticalAlign = 'center'
      t.fills = [{ fillColor: '#ffffff', fillOpacity: 1 }]
      shapes.push(t)
    }
  }
  return shapes
}

/** 单选：圆形 + 选中圆点（列内水平居中）。 */
function createRadio(cell: CellLayout, on: boolean): Shape[] {
  const s = 16
  const x = cell.x + (cell.width - s) / 2
  const y = cell.y + (cell.height - s) / 2
  const shapes: Shape[] = [createRect({ x, y, width: s, height: s }, '#ffffff', { radius: s / 2 })]
  if (on) {
    const d = 8
    const pad = (s - d) / 2
    shapes.push(
      createRect({ x: x + pad, y: y + pad, width: d, height: d }, STYLE.radioColor, {
        stroke: false,
        radius: d / 2,
      }),
    )
  }
  return shapes
}

/** 多选：把选中项渲染为标签 chip。 */
function createMultiSelectChips(cell: CellLayout, value: string): Shape[] {
  const shapes: Shape[] = []
  const selected = value
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean)
  if (selected.length === 0) return shapes
  const chipH = 20
  let cx = cell.x + STYLE.cellPadding
  const cy = cell.y + (cell.height - chipH) / 2
  for (const s of selected) {
    const avail = cell.x + cell.width - STYLE.cellPadding - cx
    if (avail < 16) break
    const w = Math.min(Math.max(s.length * 12 + 12, 26), avail)
    shapes.push(createRect({ x: cx, y: cy, width: w, height: chipH }, pickLabelColor(s), { stroke: false, radius: chipH / 2 }))
    const t = createTextInCell({ x: cx, y: cy, width: w, height: chipH }, s, {
      color: STYLE.labelText,
      fontSize: 11,
      padding: 2,
      center: true,
    })
    if (t) shapes.push(t)
    cx += w + 4
  }
  return shapes
}

/** 标签：按值哈希取色的圆角色块 + 文本。 */
function createLabel(cell: CellLayout, value: string): Shape[] {
  const text = value || '—'
  const textW = Math.min(
    Math.max(text.length * 13 + STYLE.labelPaddingX * 2, 40),
    cell.width - STYLE.cellPadding * 2,
  )
  const h = 22
  const x = cell.x + STYLE.cellPadding
  const y = cell.y + (cell.height - h) / 2
  const shapes: Shape[] = [
    createRect({ x, y, width: textW, height: h }, pickLabelColor(text), {
      stroke: false,
      radius: h / 2,
    }),
  ]
  const t = createTextInCell(
    { x, y, width: textW, height: h },
    text,
    { color: STYLE.labelText, fontSize: 12, padding: 0, center: true },
  )
  if (t) shapes.push(t)
  return shapes
}

function pickLabelColor(value: string): string {
  let h = 0
  for (let i = 0; i < value.length; i++) h = (h * 31 + value.charCodeAt(i)) >>> 0
  return STYLE.labelPalette[h % STYLE.labelPalette.length]
}

/** 开关：轨道 + 滑块。 */
function createSwitch(cell: CellLayout, on: boolean): Shape[] {
  const trackW = STYLE.switchWidth
  const trackH = STYLE.switchHeight
  const x = cell.x + (cell.width - trackW) / 2
  const y = cell.y + (cell.height - trackH) / 2
  const track = createRect({ x, y, width: trackW, height: trackH }, on ? STYLE.switchTrackOn : STYLE.switchTrackOff, {
    stroke: false,
    radius: trackH / 2,
  })
  const d = STYLE.switchKnobDiameter
  const pad = (trackH - d) / 2
  const knobX = on ? x + trackW - d - pad : x + pad
  const knob = createRect({ x: knobX, y: y + pad, width: d, height: d }, STYLE.switchKnob, {
    stroke: false,
    radius: d / 2,
  })
  return [track, knob]
}

/** 操作按钮：actions 数组横向排布，超宽时等比缩小以适配单元格（防列错乱）；禁用的按钮以弱化配色渲染。 */
function createActionButtons(cell: CellLayout, actions: ActionButton[]): Shape[] {
  const shapes: Shape[] = []
  const pad = STYLE.actionButtonPadding
  const gap = 6
  const btnH = 22
  const avail = cell.width - pad * 2
  const raw = actions.map((btn) => Math.max(actionButtonLabel(btn).length * 13 + pad * 2, 40))
  const total = raw.reduce((a, b) => a + b, 0) + gap * Math.max(raw.length - 1, 0)
  const scale = total > avail && total > 0 ? avail / total : 1
  const widths = raw.map((w) => Math.max(Math.floor(w * scale), 14))
  let cx = cell.x + pad
  const cy = cell.y + (cell.height - btnH) / 2
  actions.forEach((btn, i) => {
    const disabled = actionButtonDisabled(btn)
    const label = actionButtonLabel(btn)
    const w = widths[i]
    shapes.push(
      createRect(
        { x: cx, y: cy, width: w, height: btnH },
        disabled ? STYLE.actionButtonDisabledBg : STYLE.actionButtonBg,
        { stroke: false, radius: STYLE.actionButtonRadius },
      ),
    )
    const t = createTextInCell(
      { x: cx, y: cy, width: w, height: btnH },
      label,
      {
        color: disabled ? STYLE.actionButtonDisabledText : STYLE.actionButtonText,
        fontSize: 11,
        padding: 2,
        center: true,
      },
    )
    if (t) shapes.push(t)
    cx += w + gap
  })
  return shapes
}

/** 图标：渲染所选符号字符（居中，视觉可见的图标）。 */
function createIcon(cell: CellLayout, symbol: string): Shape[] {
  const shapes: Shape[] = []
  const text = symbol || '●'
  const fontSize = 18
  const lineHeight = Math.round(fontSize * 1.2)
  const x = cell.x + (cell.width - lineHeight) / 2
  const y = cell.y + (cell.height - lineHeight) / 2
  const t = penpot.createText(text)
  if (!t) return shapes
  t.x = x
  t.y = y
  t.resize(lineHeight, lineHeight)
  t.growType = 'fixed'
  t.fontSize = String(fontSize)
  t.align = 'center'
  t.verticalAlign = 'center'
  t.fills = [{ fillColor: STYLE.iconFill, fillOpacity: 1 }]
  shapes.push(t)
  return shapes
}

/** 图片：URL 加载失败或为空时降级为内置默认图；`preset:` 标识走本地绘制，不依赖外部图片服务。 */
async function createImage(cell: CellLayout, url: string): Promise<Shape[]> {
  const trimmed = url.trim()
  if (!trimmed) return createPresetImage(cell, 'placeholder')
  if (isImagePreset(trimmed)) return createPresetImage(cell, trimmed.slice('preset:'.length))
  try {
    const imageData = await penpot.uploadMediaUrl(`smart-table-image-${cell.row}-${cell.column}`, trimmed)
    const shape = penpot.createRectangle()
    shape.name = 'smart-table-image'
    shape.x = cell.x
    shape.y = cell.y
    shape.resize(cell.width, cell.height)
    shape.fills = [{ fillOpacity: 1, fillImage: imageData }]
    shape.strokes = []
    shape.borderRadius = STYLE.cellRadius
    return [shape]
  } catch {
    return createPresetImage(cell, 'placeholder')
  }
}

/** 内置样图：按 PRESET_IMAGES 表本地绘制（成功=绿勾、失败=红叉、占位=浅「图」…共 9 种），不依赖外网。 */
function createPresetImage(cell: CellLayout, preset: string): Shape[] {
  const def = presetImage(preset) ?? presetImage('placeholder')!
  const rect = createRect(cell, def.bg, { stroke: false })
  const t = createTextInCell(cell, def.symbol, {
    color: def.fg,
    fontSize: def.symbol.length > 1 ? 13 : 16,
    padding: 0,
    center: true,
  })
  return t ? [rect, t] : [rect]
}

/** 文本输入框：白底 + 边框；值为空且有 placeholder 时显示灰色占位提示。 */
function createInput(cell: CellLayout, value: string, placeholder?: string): Shape[] {
  const shape = createRect(cell, STYLE.inputBg, { stroke: false })
  shape.borderRadius = STYLE.cellRadius
  shape.strokes = [
    {
      strokeColor: STYLE.inputBorder,
      strokeStyle: 'solid',
      strokeWidth: 1,
      strokeAlignment: 'center',
    },
  ]
  const shapes: Shape[] = [shape]
  const empty = value.trim() === ''
  const text = empty && placeholder ? placeholder : value
  const t = createTextInCell(cell, text, {
    color: empty && placeholder ? STYLE.inputPlaceholder : STYLE.cellText,
    fontSize: 12,
  })
  if (t) shapes.push(t)
  return shapes
}

/**
 * 网格线：用最少的线型形状勾勒表格边框。
 * - full：表头底边 + 顶边/每数据行底边的横线 + 每列边界的竖线；
 * - horizontal/striped：仅顶边 + 每数据行底边的横线。
 * 相比逐单元格画描边 rect，节点数从「行×列」降为「行数+列数」，渲染显著更快。
 */
function createGridLines(layout: TableLayout, color: string, withVertical: boolean): Shape[] {
  const lines: Shape[] = []
  const rowCount = layout.bodyCells.length / Math.max(layout.columnWidths.length, 1)
  const ys: number[] = [0]
  if (withVertical && layout.headerHeight > 0) ys.push(layout.headerHeight)
  for (let r = 0; r < rowCount; r++) ys.push(layout.headerHeight + (r + 1) * layout.rowHeight)
  for (const y of ys) {
    lines.push(
      createRect({ x: 0, y, width: layout.tableWidth, height: STYLE.hLineWidth }, color, {
        stroke: false,
      }),
    )
  }
  if (withVertical) {
    const xs: number[] = [0]
    let acc = 0
    for (const w of layout.columnWidths) {
      acc += w
      xs.push(acc)
    }
    for (const x of xs) {
      lines.push(
        createRect({ x, y: 0, width: STYLE.hLineWidth, height: layout.tableHeight }, color, {
          stroke: false,
        }),
      )
    }
  }
  return lines
}

/* ------------------------------ 表格组装 ------------------------------- */

async function buildTableGroup(
  table: TableDefinition,
  position?: { x: number; y: number },
): Promise<Group | null> {
  const layout = computeLayout(table)
  const showHeader = table.showHeader !== false
  const style: TableStyle = table.tableStyle ?? 'full'
  const palette = resolveTheme(table.theme)
  const dataY = showHeader ? layout.headerHeight : 0

  // 高性能扁平组装：节点最少（背景/网格线按表格整体绘制，而非逐单元格描边）。
  const tableShapes: Shape[] = []

  // 1) 数据区背景：full/horizontal 整表白底 1 个 rect；striped 每行一个交替背景
  if (style === 'striped') {
    for (let r = 0; r < table.rows; r++) {
      tableShapes.push(
        createRect(
          { x: 0, y: dataY + r * layout.rowHeight, width: layout.tableWidth, height: layout.rowHeight },
          r % 2 === 1 ? palette.stripedBg : palette.cellBg,
          { stroke: false },
        ),
      )
    }
  } else {
    tableShapes.push(
      createRect(
        { x: 0, y: dataY, width: layout.tableWidth, height: layout.tableHeight - dataY },
        palette.cellBg,
        { stroke: false },
      ),
    )
  }

  // 2) 表头：整行 1 个背景 rect + 每列文字（showHeader 为 false 时不构建）
  if (showHeader) {
    tableShapes.push(
      createRect({ x: 0, y: 0, width: layout.tableWidth, height: layout.headerHeight }, palette.headerBg, {
        stroke: false,
      }),
    )
    table.columns.forEach((col, i) => {
      const hc = layout.headerCells[i]
      const t = createTextInCell(hc, col.title, {
        color: palette.headerText,
        fontSize: STYLE.headerFontSize,
        bold: true,
        center: true,
      })
      if (t) tableShapes.push(t)
    })
  }

  // 3) 单元格内容：buildCell 同步绘制；外部 URL 图片收集后并发加载，避免逐格串行 await 拖慢渲染
  const imageJobs: Array<{ cell: CellLayout; url: string }> = []
  for (const cell of layout.bodyCells) {
    const col = table.columns[cell.column]
    const value = table.cells[cellKey(cell.row, col.id)]
    if ((col.type ?? 'text') === 'image') {
      const url = valueString(value).trim()
      if (url && !isImagePreset(url)) {
        imageJobs.push({ cell, url })
        continue
      }
    }
    tableShapes.push(...buildCell(cell, col, value))
  }
  const loaded = await Promise.all(imageJobs.map((job) => createImage(job.cell, job.url)))
  for (const shapes of loaded) tableShapes.push(...shapes)

  // 4) 网格线：full=横线+竖线；horizontal/striped=仅横线（节点数=行数+列数，远小于逐格边框）
  tableShapes.push(...createGridLines(layout, palette.hLineColor, style === 'full'))

  // 5) 分页器底部条（每页条数缺省 10，页数按 rows / pageSize 计算）
  if (table.pagination) {
    const y = layout.headerHeight + layout.rowHeight * table.rows
    const pageSize = table.pageSize && table.pageSize >= 1 ? table.pageSize : 10
    tableShapes.push(
      ...createPaginationFooter(table.rows, pageSize, layout.tableWidth, layout.rowHeight, y, palette),
    )
  }

  const group = penpot.group(tableShapes)
  if (group) {
    group.name = table.name && table.name.trim() ? table.name : '原型表格'
    // 内容全部锁定：递归锁定子形状，表格组本身保持未锁定 → 仍可整体点选与拖拽
    blockAllDescendants(group)
    const pos = position ?? targetPosition(layout.tableWidth, layout.tableHeight)
    group.x = pos.x
    group.y = pos.y
  }
  return group
}

/** 递归锁定组内所有形状。 */
function blockAllDescendants(shape: Group): void {
  try {
    for (const child of shape.children) {
      try {
        child.blocked = true
      } catch {
        // 忽略：某些形状可能不支持
      }
      if (child.type === 'group') blockAllDescendants(child)
    }
  } catch {
    // 忽略
  }
}

interface PageItem {
  kind: 'prev' | 'next' | 'page' | 'ellipsis' | 'input' | 'label'
  text: string
  active?: boolean
  width: number
}

/** 生成带省略号的页码序列（如 1 2 3 … 8）。 */
function pageItems(total: number, current: number): Array<number | '…'> {
  if (total <= 7) return Array.from({ length: total }, (_, i) => i + 1)
  const set = new Set<number>([1, total, current - 1, current, current + 1])
  const arr: Array<number | '…'> = []
  let prev = 0
  for (const n of [...set].filter((n) => n >= 1 && n <= total).sort((a, b) => a - b)) {
    if (n - prev > 1) arr.push('…')
    arr.push(n)
    prev = n
  }
  return arr
}

function buildPageItems(total: number, current: number): PageItem[] {
  const items: PageItem[] = [{ kind: 'prev', text: '‹', width: 26 }]
  for (const p of pageItems(total, current)) {
    if (p === '…') items.push({ kind: 'ellipsis', text: '…', width: 20 })
    else items.push({ kind: 'page', text: String(p), width: 26, active: p === current })
  }
  items.push({ kind: 'next', text: '›', width: 26 })
  items.push({ kind: 'input', text: String(current), width: 34 })
  items.push({ kind: 'label', text: `/ 共 ${total} 页`, width: 14 * String(total).length + 28 })
  return items
}

/**
 * 分页器：整宽底部条，左侧「共 N 条 · 每页 P 条」，右侧右对齐的页码控件
 * （‹ 上一页 | 1 2 3 … N | 下一页 › | 页码输入框 | / 共 N 页）。
 * 页码较多时用省略号省略中间页码；页数由 rows / pageSize 决定。
 */
function createPaginationFooter(
  rows: number,
  pageSize: number,
  tableWidth: number,
  rowHeight: number,
  y: number,
  palette: ThemePalette,
): Shape[] {
  const shapes: Shape[] = []
  const bar: Rect = { x: 0, y, width: tableWidth, height: rowHeight }
  shapes.push(createRect(bar, palette.footerBg, { stroke: false }))

  const left = createTextInCell(
    { x: 0, y, width: 190, height: rowHeight },
    `共 ${rows} 条 · 每页 ${pageSize} 条`,
    {
      color: STYLE.footerText,
      fontSize: 12,
      padding: STYLE.cellPadding + 6,
    },
  )
  if (left) shapes.push(left)

  const total = Math.max(1, Math.ceil(rows / pageSize))
  const current = 1
  const items = buildPageItems(total, current)
  const gap = 4
  const totalW = items.reduce((a, i) => a + i.width, 0) + gap * (items.length - 1)
  const chipH = 22
  const cy = y + (rowHeight - chipH) / 2
  let cx = tableWidth - STYLE.cellPadding - totalW
  for (const it of items) {
    const box: Rect = { x: cx, y: cy, width: it.width, height: chipH }
    if (it.kind === 'page' || it.kind === 'prev' || it.kind === 'next' || it.kind === 'input') {
      shapes.push(
        createRect(box, it.active ? STYLE.primary : STYLE.paginationChipBg, { stroke: false, radius: 4 }),
      )
      const t = createTextInCell(box, it.text, {
        color: it.active ? '#ffffff' : STYLE.footerText,
        fontSize: 12,
        padding: 0,
        center: true,
      })
      if (t) shapes.push(t)
    } else {
      const t = createTextInCell(box, it.text, {
        color: STYLE.footerText,
        fontSize: 12,
        padding: 0,
        center: it.kind === 'ellipsis',
      })
      if (t) shapes.push(t)
    }
    cx += it.width + gap
  }
  return shapes
}

function targetPosition(width: number, height: number): { x: number; y: number } {
  try {
    if (penpot.selection.length > 0 && penpot.selection[0]) {
      return { x: penpot.selection[0].x, y: penpot.selection[0].y }
    }
    const center = penpot.viewport.center
    return { x: center.x - width / 2, y: center.y - height / 2 }
  } catch {
    // 回退到 (0,0)
  }
  return { x: 0, y: 0 }
}

/* ----------------------------- 多表格状态 ------------------------------ */

interface TableInfo extends TableInfoMsg {
  json: string
  /** 创建序号（用于默认命名与排序）。 */
  seq: number
}

/** 扫描页面，收集本插件渲染的所有表格（按插件数据识别），按创建序号排序。 */
function scanTables(): TableInfo[] {
  try {
    const page = penpot.currentPage
    if (!page) return []
    const groups = page.findShapes({ type: 'group' })
    const tables: TableInfo[] = []
    for (const g of groups) {
      try {
        const json = g.getPluginData(PLUGIN_DATA_KEY)
        if (!json || !json.trim()) continue
        const result = parseAndValidate(json)
        if (!result.ok) continue
        const seqRaw = parseInt(g.getPluginData(SEQ_DATA_KEY), 10)
        // 早期无序号的表格按父层顺序回退
        const seq = Number.isFinite(seqRaw) ? seqRaw : (typeof g.parentIndex === 'number' ? g.parentIndex + 1 : 0)
        tables.push({
          id: g.id,
          seq,
          name: result.table.name ?? `表格 ${seq}`,
          rows: result.table.rows,
          cols: result.table.columns.length,
          json,
        })
      } catch {
        // 跳过无法读取的组
      }
    }
    return tables.sort((a, b) => a.seq - b.seq)
  } catch {
    return []
  }
}

/** 下一个创建序号：现有最大序号 + 1。 */
function nextSeq(): number {
  return scanTables().reduce((max, t) => Math.max(max, t.seq), 0) + 1
}

/** 下发下一个自动编号的默认表格名（新建时 UI 填入输入框，可修改）。 */
function sendNextName(): void {
  sendToUi({ type: 'next-name', name: `表格 ${nextSeq()}` })
}

/** 下发面板状态快照。 */
function sendState(): void {
  const tables = scanTables()
  let activeId = activeTableId
  if (!activeId || !tables.some((t) => t.id === activeId)) {
    if (newTableIntent) {
      // 用户显式点了「新建」：保持新建模式，不回退到选中的旧表格
      activeId = null
    } else {
      // 否则回退到画布选中对象
      const sel = penpot.selection[0]
      activeId = sel && tables.some((t) => t.id === sel.id) ? sel.id : null
    }
  }
  const active = activeId ? tables.find((t) => t.id === activeId) : undefined
  sendToUi({
    type: 'state',
    tables: tables.map(({ id, name, rows, cols }) => ({ id, name, rows, cols })),
    activeId,
    json: active ? active.json : null,
  })
}

/** 在画布上选中指定表格组。 */
function selectTableOnCanvas(id: string): void {
  try {
    const page = penpot.currentPage
    const group = page ? page.getShapeById(id) : null
    if (group) penpot.selection = [group]
  } catch {
    // 忽略
  }
}

/* ------------------------------- 渲染动作 ------------------------------ */

async function renderNew(jsonText: string): Promise<void> {
  const result = parseAndValidate(jsonText)
  if (!result.ok) {
    sendToUi({ type: 'render:error', errors: result.errors })
    return
  }
  try {
    // 新建表格错落摆放，避免多表完全重叠；分配创建序号用于命名与排序
    const layout = computeLayout(result.table)
    const base = targetPosition(layout.tableWidth, layout.tableHeight)
    const offset = scanTables().length * 28
    const seq = nextSeq()
    const group = await buildTableGroup(result.table, { x: base.x + offset, y: base.y + offset })
    if (group) {
      try {
        group.setPluginData(PLUGIN_DATA_KEY, jsonText)
        group.setPluginData(SEQ_DATA_KEY, String(seq))
      } catch {
        // 插件数据写入失败不阻断渲染
      }
      group.name = result.table.name && result.table.name.trim() ? result.table.name : `表格 ${seq}`
    }
    activeTableId = group ? group.id : null
    newTableIntent = false
    if (group) selectTableOnCanvas(group.id)
    sendState()
  } catch (e) {
    sendToUi({ type: 'render:error', errors: [`渲染失败：${e instanceof Error ? e.message : String(e)}`] })
  }
}

async function renderUpdate(id: string, jsonText: string): Promise<void> {
  const result = parseAndValidate(jsonText)
  if (!result.ok) {
    sendToUi({ type: 'render:error', errors: result.errors })
    return
  }
  try {
    const page = penpot.currentPage
    const old = page ? page.getShapeById(id) : null
    // 保留原表格坐标与创建序号（对应需求：重渲坐标不变、排序稳定）
    const position = old ? { x: old.x, y: old.y } : undefined
    const oldSeq = old ? parseInt(old.getPluginData(SEQ_DATA_KEY), 10) : NaN
    const seq = Number.isFinite(oldSeq) ? oldSeq : nextSeq()
    const group = await buildTableGroup(result.table, position)
    if (group) {
      try {
        group.setPluginData(PLUGIN_DATA_KEY, jsonText)
        group.setPluginData(SEQ_DATA_KEY, String(seq))
      } catch {
        // 忽略
      }
      group.name = result.table.name && result.table.name.trim() ? result.table.name : `表格 ${seq}`
      if (old) {
        try {
          old.remove()
        } catch {
          // 忽略
        }
      }
    }
    activeTableId = group ? group.id : null
    newTableIntent = false
    if (group) selectTableOnCanvas(group.id)
    sendState()
  } catch (e) {
    sendToUi({ type: 'render:error', errors: [`渲染失败：${e instanceof Error ? e.message : String(e)}`] })
  }
}

/* ------------------------------ 选中联动 ------------------------------- */

/** 画布选中变化：选中本插件表格则切换到该表格；失焦/选非表格则回到空状态（新建中除外）。 */
function handleSelectionChanged(): void {
  const sel = penpot.selection[0]
  if (!sel) {
    // 失焦（点击空白）：若不在新建模式，回到空状态
    if (!newTableIntent) {
      activeTableId = null
      sendState()
    }
    return
  }
  const tables = scanTables()
  const t = tables.find((x) => x.id === sel.id)
  if (!t) {
    // 选中了非本插件表格的对象：同样回到空状态（新建中除外）
    if (!newTableIntent) {
      activeTableId = null
      sendState()
    }
    return
  }
  activeTableId = t.id
  newTableIntent = false
  sendState()
}

/* -------------------------------- 启动 -------------------------------- */

penpot.ui.open('原型表格', './ui.html', { width: 720, height: 780 })
penpot.ui.onMessage(async (msg: UiToHostMessage) => {
  if (!msg || typeof msg !== 'object') return
  switch (msg.type) {
    case 'request-state':
      sendState()
      break
    case 'render-new':
      if (typeof msg.json === 'string') await renderNew(msg.json)
      break
    case 'render-update':
      if (typeof msg.id === 'string' && typeof msg.json === 'string') await renderUpdate(msg.id, msg.json)
      break
    case 'select-table':
      if (typeof msg.id === 'string') {
        activeTableId = msg.id
        newTableIntent = false
        selectTableOnCanvas(msg.id)
        sendState()
      }
      break
    case 'new-table':
      activeTableId = null
      newTableIntent = true
      sendState()
      sendNextName()
      break
    case 'request-next-name':
      sendNextName()
      break
  }
})
penpot.on('selectionchange', handleSelectionChanged)
