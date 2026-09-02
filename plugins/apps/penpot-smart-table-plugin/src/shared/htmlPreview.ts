/**
 * HTML 预览渲染器：把 TableDefinition 渲染为自包含的 HTML 表格字符串，
 * 用于面板内预览弹层（iframe srcDoc），所见即所得还原画布渲染效果。
 * 覆盖：表头显隐、三种表格样式、各列类型形态（含 action 禁用、input placeholder、内置默认图）。
 */

import { presetImage, STYLE } from './constants'
import { columnWidths } from './layout'
import {
  actionButtonDisabled,
  actionButtonLabel,
  cellKey,
  type ColumnDefinition,
  type TableDefinition,
} from './types'

function esc(s: string): string {
  return s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

function cellValue(table: TableDefinition, row: number, col: ColumnDefinition): string {
  const v = table.cells[cellKey(row, col.id)]
  return v === undefined || v === null ? '' : String(v)
}

function pickLabelColor(value: string): string {
  let h = 0
  for (let i = 0; i < value.length; i++) h = (h * 31 + value.charCodeAt(i)) >>> 0
  return STYLE.labelPalette[h % STYLE.labelPalette.length]
}

function renderCell(table: TableDefinition, row: number, col: ColumnDefinition): string {
  const type = col.type ?? 'text'
  const v = cellValue(table, row, col)
  switch (type) {
    case 'checkbox':
      return `<span class="chk ${v === 'true' ? 'on' : ''}"></span>`
    case 'switch':
      // 开关：渲染为胶囊滑块（不是复选框）
      return `<span class="sw ${v === 'true' ? 'on' : ''}"></span>`
    case 'radio':
      // 单选框与复选框一致：值为布尔勾选态
      return `<span class="radio ${v === 'true' ? 'on' : ''}"></span>`
    case 'dropdown': {
      // 空值时默认显示第一个有效选项
      const text = v || col.options?.[0] || ''
      return `<span class="cell-text">${esc(text)}</span><span class="caret">▾</span>`
    }
    case 'multi-select': {
      const chips = v
        .split(',')
        .map((s) => s.trim())
        .filter(Boolean)
      return chips.map((c) => `<span class="chip">${esc(c)}</span>`).join('') || '<span class="cell-text"></span>'
    }
    case 'action': {
      const btns = col.actions ?? []
      return (
        btns
          .map((b) => {
            const label = actionButtonLabel(b)
            const disabled = actionButtonDisabled(b)
            return `<button class="abtn${disabled ? ' disabled' : ''}" disabled>${esc(label)}</button>`
          })
          .join('') || '<span class="cell-text"></span>'
      )
    }
    case 'image': {
      if (v.startsWith('preset:')) {
        const def = presetImage(v.slice('preset:'.length))
        if (def) {
          return `<span class="img-preset" style="background:${def.bg};color:${def.fg}">${esc(def.symbol)}</span>`
        }
        // 未知的 preset 标识：降级为内置占位图
        return '<span class="img-preset">图</span>'
      }
      if (v) return `<img class="cell-img" src="${esc(v)}" alt="">`
      return '<span class="img-preset">图</span>'
    }
    case 'icon':
      return `<span class="icon">${esc(v) || '●'}</span>`
    case 'label': {
      const text = v || '—'
      return `<span class="label-badge" style="background:${pickLabelColor(text)}">${esc(text)}</span>`
    }
    case 'input':
      return `<input class="cell-input" value="${esc(v)}" placeholder="${esc(col.placeholder ?? '')}" disabled>`
    default:
      return `<span class="cell-text">${esc(v)}</span>`
  }
}

function renderHead(table: TableDefinition): string {
  const cells = table.columns
    .map((col) => `<th>${esc(col.title)}</th>`)
    .join('')
  return `<thead><tr>${cells}</tr></thead>`
}

/** 布尔/勾选类控件列内容居中（与画布渲染一致）。 */
function centerClass(type?: string): string {
  return type === 'checkbox' || type === 'radio' || type === 'switch' ? ' class="c-center"' : ''
}

function renderBody(table: TableDefinition): string {
  const rows: string[] = []
  for (let r = 0; r < table.rows; r++) {
    const cells = table.columns
      .map((col) => `<td${centerClass(col.type)}>${renderCell(table, r, col)}</td>`)
      .join('')
    rows.push(`<tr>${cells}</tr>`)
  }
  return `<tbody>${rows.join('')}</tbody>`
}

/** 生成带省略号的页码序列（与画布渲染一致）。 */
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

/** 分页器底部条（与画布渲染一致：页码含省略号、页码输入框、每页条数）。 */
function renderPager(table: TableDefinition): string {
  const rows = table.rows
  const pageSize = table.pageSize && table.pageSize >= 1 ? table.pageSize : 10
  const total = Math.max(1, Math.ceil(rows / pageSize))
  const current = 1
  const chips = pageItems(total, current)
    .map((p) =>
      p === '…'
        ? '<span class="pg pg-ellipsis">…</span>'
        : `<span class="pg ${p === current ? 'active' : ''}">${p}</span>`,
    )
    .join('')
  return `<div class="pager">
    <span>共 ${rows} 条 · 每页 ${pageSize} 条</span>
    <div class="pager-right"><span class="pg">‹</span>${chips}<span class="pg">›</span><input class="pg-input" value="${current}" disabled><span>/ 共 ${total} 页</span></div>
  </div>`
}

/** 生成自包含的 HTML 表格预览。 */
export function renderTableHtml(table: TableDefinition): string {
  const style = table.tableStyle ?? 'full'
  const showHeader = table.showHeader !== false
  const light = table.theme === 'light'
  const styleClass =
    style === 'horizontal' ? 'style-horizontal' : style === 'striped' ? 'style-striped' : 'style-full'
  // 列宽与画布一致：显式 width 优先，缺省默认列宽；table-layout:fixed 保证每列精确等宽，
  // 文本在该宽度内自动换行，避免预览比实际渲染更宽/不换行导致错位。
  const widths = columnWidths(table)
  const totalWidth = widths.reduce((a, b) => a + b, 0)
  const colgroup = `<colgroup>${widths.map((w) => `<col style="width:${w}px">`).join('')}</colgroup>`
  const head = showHeader ? renderHead(table) : ''
  const body = renderBody(table)
  const pager = table.pagination ? renderPager(table) : ''

  return `<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<style>
  :root {
    --header-bg:${light ? '#f4f6f9' : '#2f3542'};
    --header-text:${light ? '#24292f' : '#ffffff'};
    --border:${light ? '#e6ebf0' : '#d0d7de'};
    --striped-bg:${light ? '#f7f9fb' : '#f6f8fa'};
  }
  * { box-sizing: border-box; }
  body { margin:0; background:#fff; color:#24292f; font:13px/1.5 Inter,-apple-system,'Segoe UI','Microsoft YaHei',sans-serif; padding:20px; }
  .preview-title { color:#9aa4b2; font-size:12px; margin:0 0 10px; }
  .style-full, .style-horizontal, .style-striped { width: max-content; }
  table { border-collapse: collapse; table-layout: fixed; }
  th, td { padding: 12px 10px; text-align: left; white-space: normal; overflow-wrap: anywhere; word-break: break-word; }
  .c-center { text-align: center; }
  th { background:var(--header-bg); color:var(--header-text); font-weight:600; font-size:12px; }
  .style-full th, .style-full td { border: 1px solid var(--border); }
  .style-horizontal tr { border-top: 1px solid var(--border); }
  .style-striped tr { border-top: 1px solid var(--border); }
  .style-horizontal tbody tr:last-child, .style-striped tbody tr:last-child { border-bottom: 1px solid var(--border); }
  .style-striped tbody tr:nth-child(even) { background:var(--striped-bg); }
  .cell-text { vertical-align: middle; }
  .caret { color:#57606a; margin-left: 4px; }
  .chk { display:inline-block; width:14px; height:14px; border:1px solid var(--border); border-radius:3px; background:#fff; vertical-align:middle; }
  .chk.on { background:#2da44e; border-color:#2da44e; position:relative; }
  .chk.on::after { content:'✓'; color:#fff; font-size:11px; position:absolute; left:2px; top:-1px; }
  .radio { display:inline-block; width:14px; height:14px; border:1px solid var(--border); border-radius:50%; background:#fff; margin-right:6px; vertical-align:middle; }
  .radio.on { border-color:#4377d9; box-shadow: inset 0 0 0 3px #fff, 0 0 0 2px #4377d9; }
  .sw { position:relative; display:inline-block; width:30px; height:17px; border-radius:9px; background:#d0d7de; vertical-align:middle; }
  .sw::after { content:''; position:absolute; top:2px; left:2px; width:13px; height:13px; border-radius:50%; background:#fff; box-shadow:0 1px 2px rgba(0,0,0,.25); }
  .sw.on { background:#2da44e; }
  .sw.on::after { left:15px; }
  .chip { display:inline-block; background:#eef2f6; border-radius:10px; padding:1px 8px; font-size:11px; margin-right:4px; }
  .abtn { appearance:none; border:none; border-radius:4px; background:#eef2f6; color:#24292f; font-size:11px; padding:3px 9px; margin-right:6px; }
  .abtn.disabled { background:#eef1f3; color:#9aa4b2; }
  .cell-input { border:1px solid #c9d1d9; border-radius:2px; padding:3px 6px; font-size:12px; background:#fff; color:#24292f; }
  .cell-input::placeholder { color:#8b949e; }
  .cell-img { width:64px; height:44px; object-fit:cover; border-radius:2px; display:block; }
  .img-preset { display:inline-flex; align-items:center; justify-content:center; width:64px; height:44px; background:#eef1f4; color:#57606a; font-size:16px; border-radius:2px; }
  .icon { font-size:16px; color:#8b949e; }
  .label-badge { display:inline-block; border-radius:11px; padding:1px 10px; font-size:12px; color:#24292f; }
  .pager { display:flex; align-items:center; justify-content:space-between; background:var(--striped-bg); border-top:1px solid var(--border); padding:5px 10px; font-size:12px; color:#57606a; }
  .pager-right { display:flex; align-items:center; gap:4px; }
  .pg { display:inline-flex; align-items:center; justify-content:center; min-width:22px; height:22px; padding:0 4px; border-radius:4px; background:#eef2f6; color:#57606a; }
  .pg.active { background:#4377d9; color:#fff; }
  .pg-ellipsis { background:transparent; }
  .pg-input { width:30px; height:22px; border:1px solid #c9d1d9; border-radius:4px; background:#fff; color:#57606a; text-align:center; font-size:12px; }
</style>
</head>
<body>
  <p class="preview-title">表格预览</p>
  <div class="${styleClass}">
    <table style="width:${totalWidth}px">${colgroup}${head}${body}</table>
    ${pager}
  </div>
</body>
</html>`
}
