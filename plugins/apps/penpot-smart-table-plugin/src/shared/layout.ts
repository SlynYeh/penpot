/**
 * 几何布局计算：把 TableDefinition 摊平成单元格矩形网格。
 * 宿主按此布局创建形状。对应设计 D3/D7。
 */

import { STYLE } from './constants'
import type { TableDefinition } from './types'

export interface Rect {
  x: number
  y: number
  width: number
  height: number
}

export interface CellLayout extends Rect {
  row: number
  column: number
  columnId: string
}

export interface TableLayout {
  /** 表格总宽（px） */
  tableWidth: number
  /** 表格总高（px），含表头 */
  tableHeight: number
  /** 表头行高（px） */
  headerHeight: number
  /** 数据行高（px） */
  rowHeight: number
  /** 各列宽（px），与 columns 一一对应 */
  columnWidths: number[]
  /** 表头单元格布局 */
  headerCells: Rect[]
  /** 数据单元格布局 */
  bodyCells: CellLayout[]
}

/** 计算列宽：显式 width 优先，缺省用默认列宽。 */
export function columnWidths(table: TableDefinition): number[] {
  return table.columns.map((c) =>
    typeof c.width === 'number' && c.width > 0 ? c.width : STYLE.defaultColumnWidth,
  )
}

/**
 * 数据网格（UI 表单）的 CSS grid 列模板：
 * `行号列 + 数据列×N + 复制列 + 删除列`，共 N+3 个 track，
 * 与每行渲染元素数（行号 + N 个单元格 + 复制 + 删除按钮）严格一致，避免折行错位。
 */
export function dataGridTemplateColumns(columnCount: number): string {
  return `minmax(36px, 44px) repeat(${columnCount}, minmax(96px, 1fr)) 48px 48px`
}

/**
 * 把数组元素从 from 移到 to（列拖拽排序）。
 * 越界、相等或数组太短时原样返回（不产生副作用）。
 */
export function moveColumn<T>(items: readonly T[], from: number, to: number): T[] {
  if (
    from === to ||
    from < 0 ||
    to < 0 ||
    from >= items.length ||
    to >= items.length ||
    items.length < 2
  ) {
    return [...items]
  }
  const next = [...items]
  const [moved] = next.splice(from, 1)
  next.splice(to, 0, moved)
  return next
}

/** 解析单元格键为 (行索引, 列标识)。非法键返回 null。 */
function splitCellKey(k: string): [number, string] | null {
  const m = /^(\d+):(.*)$/.exec(k)
  return m ? [Number(m[1]), m[2]] : null
}

/** 快速复制行：在 r 行下方插入内容与 r 行相同的新行（原行保留），后续行整体下移（rows 上限 100）。原对象不变。 */
export function copyRowCells(
  rows: number,
  cells: Record<string, string | boolean>,
  r: number,
): { rows: number; cells: Record<string, string | boolean> } {
  if (rows >= 100 || r < 0 || r >= rows) return { rows, cells }
  const next: Record<string, string | boolean> = {}
  for (const [k, v] of Object.entries(cells)) {
    const m = splitCellKey(k)
    if (!m) continue
    const [row, rest] = m
    if (row === r) {
      // 原行保留，同时在 r+1 行写入一份副本
      next[`${r}:${rest}`] = v
      next[`${r + 1}:${rest}`] = v
    } else if (row > r) {
      next[`${row + 1}:${rest}`] = v
    } else {
      next[k] = v
    }
  }
  return { rows: rows + 1, cells: next }
}

/** 删除指定行：移除该行单元格，后续行整体上移（至少保留 1 行）。原对象不变。 */
export function deleteRowCells(
  rows: number,
  cells: Record<string, string | boolean>,
  r: number,
): { rows: number; cells: Record<string, string | boolean> } {
  if (rows <= 1 || r < 0 || r >= rows) return { rows, cells }
  const next: Record<string, string | boolean> = {}
  for (const [k, v] of Object.entries(cells)) {
    const m = splitCellKey(k)
    if (!m) continue
    const [row, rest] = m
    if (row === r) continue
    if (row > r) next[`${row - 1}:${rest}`] = v
    else next[k] = v
  }
  return { rows: rows - 1, cells: next }
}

/** 摊平整个表格为网格布局（坐标基于表格组原点 (0,0)，宿主在创建后整体平移）。 */
export function computeLayout(table: TableDefinition): TableLayout {
  const widths = columnWidths(table)
  const rowHeight = STYLE.defaultRowHeight
  const headerHeight = table.showHeader === false ? 0 : STYLE.headerRowHeight

  const tableWidth = widths.reduce((a, b) => a + b, 0)
  const tableHeight = headerHeight + rowHeight * table.rows

  const colX = new Array(widths.length).fill(0)
  let acc = 0
  for (let i = 0; i < widths.length; i++) {
    colX[i] = acc
    acc += widths[i]
  }

  const headerCells: Rect[] = table.columns.map((_, i) => ({
    x: colX[i],
    y: 0,
    width: widths[i],
    height: headerHeight,
  }))

  const bodyCells: CellLayout[] = []
  for (let r = 0; r < table.rows; r++) {
    for (let i = 0; i < table.columns.length; i++) {
      bodyCells.push({
        x: colX[i],
        y: headerHeight + r * rowHeight,
        width: widths[i],
        height: rowHeight,
        row: r,
        column: i,
        columnId: table.columns[i].id,
      })
    }
  }

  return {
    tableWidth,
    tableHeight,
    headerHeight,
    rowHeight,
    columnWidths: widths,
    headerCells,
    bodyCells,
  }
}
