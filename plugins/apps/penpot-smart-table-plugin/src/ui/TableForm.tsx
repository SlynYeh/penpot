/**
 * 表单模式编辑器：可视化编辑表格结构、列与数据（替代 JSON 编辑器）。
 * 对应需求：去 JSON 编辑器、便捷增删行列、单个/批量修改、模拟图片、分页器、flex/grid 布局。
 */

import { useMemo, useState, type ReactNode } from 'react'
import { renderTableHtml } from '../shared/htmlPreview'
import { presetImage } from '../shared/constants'
import { copyRowCells, dataGridTemplateColumns, deleteRowCells, moveColumn } from '../shared/layout'
import {
  actionButtonDisabled,
  actionButtonLabel,
  cellKey,
  COLUMN_TYPES,
  ICON_SET,
  isImagePreset,
  TABLE_STYLES,
  TABLE_THEMES,
  type ColumnDefinition,
  type ColumnType,
  type TableDefinition,
  type TableStyle,
  type TableTheme,
} from '../shared/types'
import ImagePicker from './ImagePicker'

export interface FormModel {
  name: string
  rows: number
  pagination: boolean
  pageSize: number
  showHeader: boolean
  tableStyle: TableStyle
  theme: TableTheme
  columns: ColumnDefinition[]
  cells: Record<string, string | boolean>
}

export function fromTable(t: TableDefinition): FormModel {
  return {
    name: t.name ?? '',
    rows: t.rows,
    pagination: t.pagination ?? false,
    pageSize: t.pageSize && t.pageSize >= 1 ? t.pageSize : 10,
    showHeader: t.showHeader !== false,
    tableStyle: t.tableStyle ?? 'full',
    theme: t.theme ?? 'light',
    columns: t.columns.map((c) => ({ ...c })),
    cells: { ...t.cells },
  }
}

export function toTable(m: FormModel): TableDefinition {
  const cells: Record<string, string | boolean> = {}
  for (const [k, v] of Object.entries(m.cells)) {
    if (v !== '' && v !== undefined) cells[k] = v
  }
  return {
    name: m.name.trim() || undefined,
    rows: Math.max(1, m.rows),
    columns: m.columns.map((c) => ({
      ...c,
      title: c.title.trim() || c.id,
      width: c.width && c.width > 0 ? c.width : undefined,
      options: c.options && c.options.length ? c.options : undefined,
      actions: c.actions && c.actions.length ? c.actions : undefined,
    })),
    cells,
    pagination: m.pagination,
    pageSize: m.pageSize,
    showHeader: m.showHeader,
    tableStyle: m.tableStyle,
    theme: m.theme,
  }
}

export function blankTable(): TableDefinition {
  return {
    name: undefined,
    rows: 2,
    columns: [
      { id: 'c1', title: '名称', type: 'text' },
      { id: 'c2', title: '状态', type: 'text' },
      { id: 'c3', title: '启用', type: 'switch' },
    ],
    cells: {
      [cellKey(0, 'c1')]: '示例',
      [cellKey(0, 'c2')]: '进行中',
      [cellKey(0, 'c3')]: true,
    },
  }
}

const TYPE_LABELS: Record<ColumnType, string> = {
  text: '文本',
  dropdown: '下拉选择',
  'multi-select': '多选',
  radio: '单选',
  checkbox: '多选框',
  switch: '开关',
  action: '操作',
  image: '图片',
  icon: '图标',
  label: '标签',
  input: '文本输入框',
}

function randId(): string {
  return `c${Math.random().toString(36).slice(2, 8)}`
}

interface TableFormProps {
  initial: TableDefinition
  mode: 'new' | 'edit'
  onSave: (t: TableDefinition) => void
  /** 渲染进行中（宿主尚未回执），按钮置灰并显示处理中。 */
  busy?: boolean
}

export default function TableForm({ initial, mode, onSave, busy }: TableFormProps) {
  const [m, setM] = useState<FormModel>(() => fromTable(initial))
  /** 预览弹层开关。 */
  const [showPreview, setShowPreview] = useState(false)
  /** 拖拽列排序：当前被拖拽列的索引（null 表示未在拖拽）。 */
  const [dragCol, setDragCol] = useState<number | null>(null)
  /** 图片选择弹层：默认图（列索引）或数据单元格（{row,colId}）。 */
  const [pickCol, setPickCol] = useState<number | null>(null)
  const [pickCell, setPickCell] = useState<{ row: number; colId: string } | null>(null)
  /** 预览 HTML 按需生成：仅在打开预览时渲染一次，避免编辑大表格时每次输入都重算。 */
  const [previewHtml, setPreviewHtml] = useState('')

  function patch(p: Partial<FormModel>) {
    setM((prev) => ({ ...prev, ...p }))
  }

  function setCell(row: number, colId: string, value: string | boolean) {
    setM((prev) => {
      const cells = { ...prev.cells }
      const key = cellKey(row, colId)
      if (value === '') delete cells[key]
      else cells[key] = value
      return { ...prev, cells }
    })
  }

  function addRow() {
    setM((prev) => ({ ...prev, rows: Math.min(prev.rows + 1, 100) }))
  }

  /** 删除指定行：移除该行单元格，后续行整体上移。 */
  function removeRow(r: number) {
    setM((prev) => {
      const { rows, cells } = deleteRowCells(prev.rows, prev.cells, r)
      return { ...prev, rows, cells }
    })
  }

  /** 快速复制行：在 r 行下方插入一行，内容与该行相同，后续行整体下移。 */
  function duplicateRow(r: number) {
    setM((prev) => {
      const { rows, cells } = copyRowCells(prev.rows, prev.cells, r)
      return { ...prev, rows, cells }
    })
  }

  function addColumn() {
    setM((prev) => ({
      ...prev,
      columns: [...prev.columns, { id: randId(), title: `列${prev.columns.length + 1}`, type: 'text' }],
    }))
  }
  function removeColumn(i: number) {
    setM((prev) => {
      if (prev.columns.length <= 1) return prev
      const col = prev.columns[i]
      const columns = prev.columns.filter((_, idx) => idx !== i)
      const cells: typeof prev.cells = {}
      for (const [k, v] of Object.entries(prev.cells)) {
        if (!k.includes(`:${col.id}`)) cells[k] = v
      }
      return { ...prev, columns, cells }
    })
  }

  /** 拖拽到目标列索引：重排 columns。cells 键基于列 id，重排后数据自动跟随。 */
  function handleDropColumn(i: number) {
    if (dragCol === null || dragCol === i) {
      setDragCol(null)
      return
    }
    setM((prev) => ({ ...prev, columns: moveColumn(prev.columns, dragCol, i) }))
    setDragCol(null)
  }

  function updateColumn(i: number, patchCol: Partial<ColumnDefinition>) {
    setM((prev) => {
      const columns = prev.columns.map((c, idx) => (idx === i ? { ...c, ...patchCol } : c))
      return { ...prev, columns }
    })
  }

  /** 切换列类型时，把该列已有单元格的值做类型化转换（如文本 ⇄ 布尔）。 */
  function changeColumnType(i: number, type: ColumnType) {
    setM((prev) => {
      const col = prev.columns[i]
      const columns = prev.columns.map((c, idx) => (idx === i ? { ...c, type } : c))
      const cells: typeof prev.cells = {}
      for (const [k, v] of Object.entries(prev.cells)) {
        if (!k.endsWith(`:${col.id}`)) {
          cells[k] = v
          continue
        }
        if (type === 'switch' || type === 'checkbox' || type === 'radio')
          cells[k] = v === true || v === 'true' || v === 'on'
        else cells[k] = typeof v === 'boolean' ? String(v) : v
      }
      return { ...prev, columns, cells }
    })
  }

  /** 更新 action 列第 bi 个按钮（label / disabled）。 */
  function updateAction(i: number, bi: number, patchBtn: { label?: string; disabled?: boolean }) {
    setM((prev) => {
      const columns = prev.columns.map((c, idx) => {
        if (idx !== i) return c
        const actions = [...(c.actions ?? [])]
        const cur = actions[bi]
        actions[bi] =
          typeof cur === 'string'
            ? { label: patchBtn.label ?? cur, ...patchBtn }
            : { ...cur, ...patchBtn }
        return { ...c, actions }
      })
      return { ...prev, columns }
    })
  }

  /** 为 action 列新增一个按钮。 */
  function addAction(i: number) {
    setM((prev) => {
      const columns = prev.columns.map((c, idx) =>
        idx !== i ? c : { ...c, actions: [...(c.actions ?? []), '新按钮'] },
      )
      return { ...prev, columns }
    })
  }

  /** 更新枚举列第 oi 个选项文本。 */
  function updateOption(i: number, oi: number, text: string) {
    setM((prev) => {
      const columns = prev.columns.map((c, idx) => {
        if (idx !== i) return c
        const options = [...(c.options ?? [])]
        options[oi] = text
        return { ...c, options }
      })
      return { ...prev, columns }
    })
  }

  /** 为枚举列新增一个选项。 */
  function addOption(i: number) {
    setM((prev) => {
      const columns = prev.columns.map((c, idx) =>
        idx !== i ? c : { ...c, options: [...(c.options ?? []), '新选项'] },
      )
      return { ...prev, columns }
    })
  }

  /** 删除枚举列第 oi 个选项，并清空引用了它的单元格值。 */
  function removeOption(i: number, oi: number) {
    setM((prev) => {
      const col = prev.columns[i]
      const removed = (col.options ?? [])[oi]
      const columns = prev.columns.map((c, idx) =>
        idx !== i ? c : { ...c, options: (c.options ?? []).filter((_, x) => x !== oi) },
      )
      const cells: typeof prev.cells = {}
      for (const [k, v] of Object.entries(prev.cells)) {
        if (k.endsWith(`:${col.id}`) && v === removed) continue
        cells[k] = v
      }
      return { ...prev, columns, cells }
    })
  }

  /** 删除 action 列第 bi 个按钮。 */
  function removeAction(i: number, bi: number) {
    setM((prev) => {
      const columns = prev.columns.map((c, idx) =>
        idx !== i ? c : { ...c, actions: (c.actions ?? []).filter((_, x) => x !== bi) },
      )
      return { ...prev, columns }
    })
  }

  /** 用选中图（preset:xxx 或 URL）填满图片列所有单元格。 */
  function fillImageColumn(i: number, value: string) {
    setM((prev) => {
      const col = prev.columns[i]
      if (!col || !value.trim()) return prev
      const cells = { ...prev.cells }
      for (let r = 0; r < prev.rows; r++) cells[cellKey(r, col.id)] = value.trim()
      return { ...prev, cells }
    })
  }

  /** 批量填充：把指定列的所有单元格设为同一值。 */
  const [batchCol, setBatchCol] = useState('')
  const [batchVal, setBatchVal] = useState('')
  function applyBatch() {
    if (!batchCol) return
    setM((prev) => {
      const col = prev.columns.find((c) => c.id === batchCol)
      if (!col) return prev
      const cells = { ...prev.cells }
      const value: string | boolean =
        col.type === 'switch' || col.type === 'checkbox' || col.type === 'radio'
          ? batchVal === 'true' || batchVal === '1' || batchVal === 'on'
          : batchVal
      for (let r = 0; r < prev.rows; r++) {
        const key = cellKey(r, batchCol)
        if (value === '') delete cells[key]
        else cells[key] = value
      }
      return { ...prev, cells }
    })
  }

  // 行号 + 数据列 + 复制按钮 + 删除按钮，共 columns+3 列，track 数与每行元素数严格一致（见 5.1 回归）
  const colWidth = useMemo(() => dataGridTemplateColumns(m.columns.length), [m.columns.length])

  return (
    <div className="table-form">
      {/* 基本信息 */}
      <section className="form-section">
        <div className="field-row">
          <label className="field field-inline field-grow">
            <span className="field-label">表格名</span>
            <input
              className="input"
              value={m.name}
              onChange={(e) => patch({ name: e.target.value })}
              placeholder="留空自动编号"
            />
          </label>
          <label className="field field-inline">
            <input
              type="checkbox"
              checked={m.showHeader}
              onChange={(e) => patch({ showHeader: e.target.checked })}
            />
            <span className="field-label">显示表头</span>
          </label>
          <label className="field field-inline">
            <span className="field-label">表格样式</span>
            <select
              className="input"
              value={m.tableStyle}
              onChange={(e) => patch({ tableStyle: e.target.value as TableStyle })}
            >
              {TABLE_STYLES.map((s) => (
                <option key={s} value={s}>
                  {s === 'full' ? '全边框' : s === 'horizontal' ? '仅横向线' : '斑马纹'}
                </option>
              ))}
            </select>
          </label>
        </div>
        <div className="field-row">
          <div className="stepper">
            <span className="field-label">行数</span>
            <button type="button" className="btn btn-sm" onClick={() => removeRow(m.rows - 1)}>
              −
            </button>
            <span className="stepper-value">{m.rows}</span>
            <button type="button" className="btn btn-sm" onClick={addRow}>
              ＋
            </button>
          </div>
          <div className="stepper">
            <span className="field-label">列数</span>
            <button type="button" className="btn btn-sm" onClick={() => removeColumn(m.columns.length - 1)}>
              −
            </button>
            <span className="stepper-value">{m.columns.length}</span>
            <button type="button" className="btn btn-sm" onClick={addColumn}>
              ＋
            </button>
          </div>
          <label className="field field-inline">
            <input
              type="checkbox"
              checked={m.pagination}
              onChange={(e) => patch({ pagination: e.target.checked })}
            />
            <span className="field-label">分页器</span>
          </label>
          {m.pagination && (
            <label className="field field-inline">
              <span className="field-label">每页</span>
              <select
                className="input input-slim"
                value={m.pageSize}
                onChange={(e) => patch({ pageSize: Number(e.target.value) })}
              >
                {[5, 10, 20, 50].map((n) => (
                  <option key={n} value={n}>
                    {n} 条
                  </option>
                ))}
              </select>
            </label>
          )}
          <label className="field field-inline">
            <span className="field-label">主题</span>
            <select
              className="input"
              value={m.theme}
              onChange={(e) => patch({ theme: e.target.value as TableTheme })}
            >
              {TABLE_THEMES.map((th) => (
                <option key={th} value={th}>
                  {th === 'light' ? '浅色系列' : '深色系列'}
                </option>
              ))}
            </select>
          </label>
        </div>
      </section>

      {/* 列配置 */}
      <section className="form-section">
        <h3 className="section-title">列配置</h3>
        {m.columns.map((col, i) => (
          <div
            key={col.id}
            className={`col-config ${dragCol === i ? 'col-dragging' : ''}`}
            draggable
            onDragStart={() => setDragCol(i)}
            onDragOver={(e) => e.preventDefault()}
            onDrop={() => handleDropColumn(i)}
            onDragEnd={() => setDragCol(null)}
            title="拖动整行可调整列顺序"
          >
            <input
              className="input"
              value={col.title}
              onChange={(e) => updateColumn(i, { title: e.target.value })}
              placeholder="列名"
            />
            <select
              className="input"
              value={col.type ?? 'text'}
              onChange={(e) => changeColumnType(i, e.target.value as ColumnType)}
            >
              {COLUMN_TYPES.map((t) => (
                <option key={t} value={t}>
                  {TYPE_LABELS[t]}
                </option>
              ))}
            </select>
            <input
              className="input input-width"
              type="number"
              min={40}
              value={col.width ?? ''}
              onChange={(e) => updateColumn(i, { width: e.target.value ? Number(e.target.value) : undefined })}
              placeholder="宽(可选)"
              title="列宽 px"
            />
            {(col.type === 'dropdown' || col.type === 'multi-select') && (
              <div className="option-edit">
                {(col.options ?? []).map((opt, oi) => (
                  <div key={oi} className="option-edit-row">
                    <input
                      className="input input-grow"
                      value={opt}
                      onChange={(e) => updateOption(i, oi, e.target.value)}
                      placeholder="选项内容"
                    />
                    <button type="button" className="btn btn-sm btn-danger" onClick={() => removeOption(i, oi)}>
                      删
                    </button>
                  </div>
                ))}
                <button type="button" className="btn btn-sm" onClick={() => addOption(i)}>
                  ＋ 添加选项
                </button>
              </div>
            )}
            {col.type === 'input' && (
              <input
                className="input input-grow"
                value={col.placeholder ?? ''}
                onChange={(e) => updateColumn(i, { placeholder: e.target.value })}
                placeholder="占位提示（可选）"
              />
            )}
            {col.type === 'action' && (
              <div className="action-edit">
                {(col.actions ?? []).map((btn, bi) => (
                  <div key={bi} className="action-edit-row">
                    <input
                      className="input input-grow"
                      value={actionButtonLabel(btn)}
                      onChange={(e) => updateAction(i, bi, { label: e.target.value })}
                      placeholder="按钮名"
                    />
                    <label className="field field-inline">
                      <input
                        type="checkbox"
                        checked={actionButtonDisabled(btn)}
                        onChange={(e) => updateAction(i, bi, { disabled: e.target.checked })}
                      />
                      <span className="field-label">禁用</span>
                    </label>
                    <button type="button" className="btn btn-sm btn-danger" onClick={() => removeAction(i, bi)}>
                      删
                    </button>
                  </div>
                ))}
                <button type="button" className="btn btn-sm" onClick={() => addAction(i)}>
                  ＋ 添加按钮
                </button>
              </div>
            )}
            {col.type === 'image' && (
              <button type="button" className="btn btn-sm" onClick={() => setPickCol(i)}>
                选择默认图…
              </button>
            )}
            <button type="button" className="btn btn-sm btn-danger" onClick={() => removeColumn(i)}>
              删
            </button>
          </div>
        ))}
        <button type="button" className="btn btn-sm" onClick={addColumn}>
          ＋ 添加列
        </button>
      </section>

      {/* 批量操作 */}
      <section className="form-section">
        <div className="field-row">
          <span className="field-label">批量填充</span>
          <select className="input" value={batchCol} onChange={(e) => setBatchCol(e.target.value)}>
            <option value="">选择列…</option>
            {m.columns.map((c) => (
              <option key={c.id} value={c.id}>
                {c.title || c.id}
              </option>
            ))}
          </select>
          <input
            className="input"
            value={batchVal}
            onChange={(e) => setBatchVal(e.target.value)}
            placeholder={
              m.columns.find((c) => c.id === batchCol)?.type === 'switch' ||
              m.columns.find((c) => c.id === batchCol)?.type === 'checkbox' ||
              m.columns.find((c) => c.id === batchCol)?.type === 'radio'
                ? 'true / false'
                : '批量值'
            }
          />
          <button type="button" className="btn" onClick={applyBatch}>
            批量插入
          </button>
        </div>
      </section>

      {/* 数据网格 */}
      <section className="form-section">
        <div className="section-head">
          <h3 className="section-title">数据</h3>
          <p className="hint">模拟数据行数越多，渲染越慢，开发调试时建议只保留一行</p>
        </div>
        <div className="grid-wrap">
          <div className="cell-grid" style={{ gridTemplateColumns: colWidth }}>
            <div className="cell cell-head" />
            {m.columns.map((col) => (
              <div key={col.id} className="cell cell-head" title={col.type}>
                {col.title || col.id}
              </div>
            ))}
            <div className="cell cell-head" title="快速复制该行">
              复制
            </div>
            <div className="cell cell-head">删</div>
            {Array.from({ length: m.rows }, (_, r) => (
              <CellRow
                key={r}
                row={r}
                model={m}
                onSet={(colId, v) => setCell(r, colId, v)}
                onRemoveRow={() => removeRow(r)}
                onDuplicateRow={() => duplicateRow(r)}
                onOpenImage={(colId) => setPickCell({ row: r, colId })}
              />
            ))}
          </div>
        </div>
        <button type="button" className="btn btn-sm" onClick={addRow}>
          ＋ 添加行
        </button>
      </section>

      <div className="row">
        <button
          type="button"
          className="btn btn-primary"
          onClick={() => onSave(toTable(m))}
          disabled={busy}
        >
          {busy ? '处理中…' : mode === 'edit' ? '保存并重渲' : '导入为新表格'}
        </button>
        <button
          type="button"
          className="btn"
          onClick={() => {
            setPreviewHtml(renderTableHtml(toTable(m)))
            setShowPreview(true)
          }}
          disabled={busy}
        >
          预览
        </button>
      </div>
      <p className="hint">保存后按原表格坐标原位重渲；画布选中表格会自动切到对应编辑。</p>

      {showPreview && (
        <div className="preview-overlay" onClick={() => setShowPreview(false)}>
          <div className="preview-panel" onClick={(e) => e.stopPropagation()}>
            <div className="preview-bar">
              <span className="field-label">表格预览（所见即所得）</span>
              <button type="button" className="btn btn-sm" onClick={() => setShowPreview(false)}>
                返回
              </button>
            </div>
            <iframe className="preview-frame" title="表格预览" srcDoc={previewHtml} />
          </div>
        </div>
      )}

      {pickCol !== null && m.columns[pickCol] && (
        <ImagePicker
          title="选择默认图（将填满整列）"
          onPick={(v) => {
            fillImageColumn(pickCol, v)
            setPickCol(null)
          }}
          onClose={() => setPickCol(null)}
        />
      )}

      {pickCell !== null && (
        <ImagePicker
          title="选择图片"
          value={
            typeof m.cells[cellKey(pickCell.row, pickCell.colId)] === 'string'
              ? (m.cells[cellKey(pickCell.row, pickCell.colId)] as string)
              : undefined
          }
          onPick={(v) => {
            setCell(pickCell.row, pickCell.colId, v)
            setPickCell(null)
          }}
          onClose={() => setPickCell(null)}
        />
      )}
    </div>
  )
}

/** 一行单元格：行号 + 各列单元格 + 复制按钮 + 删除按钮。 */
function CellRow({
  row,
  model,
  onSet,
  onRemoveRow,
  onDuplicateRow,
  onOpenImage,
}: {
  row: number
  model: FormModel
  onSet: (colId: string, v: string | boolean) => void
  onRemoveRow: () => void
  onDuplicateRow: () => void
  onOpenImage: (colId: string) => void
}) {
  return (
    <>
      <div className="cell cell-idx">{row + 1}</div>
      {model.columns.map((col) => {
        const value = model.cells[cellKey(row, col.id)]
        const type = col.type ?? 'text'
        const options = col.options ?? []
        let control: ReactNode
        if (type === 'switch' || type === 'checkbox') {
          control = (
            <input
              type="checkbox"
              checked={value === true}
              onChange={(e) => onSet(col.id, e.target.checked)}
            />
          )
        } else if (type === 'dropdown' && options.length > 0) {
          control = (
            <select
              className="input input-cell"
              value={typeof value === 'string' ? value : ''}
              onChange={(e) => onSet(col.id, e.target.value)}
            >
              <option value="">（空）</option>
              {options.map((o) => (
                <option key={o} value={o}>
                  {o}
                </option>
              ))}
            </select>
          )
        } else if (type === 'radio') {
          control = (
            <input
              type="radio"
              checked={value === true}
              onChange={(e) => onSet(col.id, e.target.checked)}
              title="单选框：点击勾选/取消"
            />
          )
        } else if (type === 'multi-select') {
          control = (
            <input
              className="input input-cell"
              type="text"
              value={typeof value === 'string' ? value : ''}
              placeholder="逗号分隔，如 核心,重点"
              onChange={(e) => onSet(col.id, e.target.value)}
            />
          )
        } else if (type === 'icon') {
          control = (
            <select
              className="input input-cell"
              value={typeof value === 'string' ? value : ''}
              onChange={(e) => onSet(col.id, e.target.value)}
            >
              <option value="">（无）</option>
              {ICON_SET.map((s) => (
                <option key={s} value={s}>
                  {s}
                </option>
              ))}
            </select>
          )
        } else if (type === 'action') {
          control = <span className="cell-static">—</span>
        } else if (type === 'image') {
          const str = typeof value === 'string' ? value : ''
          const def = isImagePreset(str) ? presetImage(str.slice('preset:'.length)) : undefined
          control = (
            <div className="img-cell">
              {def ? (
                <span className="img-cell-tile" style={{ background: def.bg, color: def.fg }}>
                  {def.symbol}
                </span>
              ) : str ? (
                <span className="img-cell-tile img-cell-tile-thumb">
                  <img src={str} alt="" />
                </span>
              ) : (
                <span className="img-cell-tile">图</span>
              )}
              <button
                type="button"
                className="btn btn-sm"
                onClick={() => onOpenImage(col.id)}
                title="选择图片"
              >
                选图
              </button>
            </div>
          )
        } else {
          control = (
            <input
              className="input input-cell"
              type="text"
              value={typeof value === 'string' ? value : ''}
              onChange={(e) => onSet(col.id, e.target.value)}
            />
          )
        }
        return (
          <div key={col.id} className="cell">
            {control}
          </div>
        )
      })}
      <div className="cell">
        <button type="button" className="btn btn-sm" onClick={onDuplicateRow} title="快速复制该行">
          复制
        </button>
      </div>
      <div className="cell">
        <button type="button" className="btn btn-sm btn-danger" onClick={onRemoveRow}>
          删
        </button>
      </div>
    </>
  )
}
