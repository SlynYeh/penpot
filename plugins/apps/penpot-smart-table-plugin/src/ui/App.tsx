/**
 * 插件 UI：
 * - 标题栏右侧「模板」按钮（与 TAB 分离）
 * - TAB 栏列出全部表格，可切换编辑
 * - 未选中表格且未在新建时显示空状态（提示新建或选中表格）
 * - 表单模式编辑（无 JSON 编辑器）
 */

import { useCallback, useEffect, useRef, useState } from 'react'
import { isHostMessage } from '../shared/messaging'
import type { HostToUiMessage, TableInfoMsg, UiToHostMessage } from '../shared/messaging'
import {
  addHistory,
  clearHistory,
  loadHistory,
  removeHistory,
  togglePinHistory,
  type HistoryEntry,
} from '../shared/history'
import { thumbSvg, TABLE_TEMPLATES, type TableTemplate } from '../shared/templates'
import type { TableDefinition } from '../shared/types'
import TableForm, { blankTable } from './TableForm'

export default function App() {
  const [tables, setTables] = useState<TableInfoMsg[]>([])
  const [activeId, setActiveId] = useState<string | null>(null)
  /** 当前激活表格的 TableDefinition（来自宿主下发的 JSON）。 */
  const [activeTable, setActiveTable] = useState<TableDefinition | null>(null)
  /** 用户本地「新建」意图：选中表格时选模板 / 点新建后进入新建模式。 */
  const [editingNew, setEditingNew] = useState(false)
  /** 选中的模板/历史草稿（新建模式下的初始内容）。 */
  const [templateDraft, setTemplateDraft] = useState<TableDefinition | null>(null)
  const [showTemplates, setShowTemplates] = useState(false)
  /** 本地历史面板开关（与模板面板互斥）。 */
  const [showHistory, setShowHistory] = useState(false)
  const [errors, setErrors] = useState<string[]>([])
  /** 渲染进行中（宿主未回执前按钮置灰）。 */
  const [saving, setSaving] = useState(false)
  /** 宿主下发的自动编号默认表格名（新建时填入输入框，可修改）。 */
  const [pendingName, setPendingName] = useState('')

  const activeIdRef = useRef<string | null>(null)

  const handleHostMessage = useCallback((msg: HostToUiMessage) => {
    setSaving(false)
    if (msg.type === 'render:error') {
      setErrors(msg.errors)
      return
    }
    if (msg.type === 'next-name') {
      setPendingName(msg.name)
      return
    }
    // msg.type === 'state'
    setTables(msg.tables)
    setErrors([])
    activeIdRef.current = msg.activeId
    setActiveId(msg.activeId)
    if (msg.activeId !== null) setEditingNew(false)
    if (msg.json) {
      try {
        setActiveTable(JSON.parse(msg.json) as TableDefinition)
      } catch {
        setActiveTable(null)
      }
    } else {
      setActiveTable(null)
    }
  }, [])

  useEffect(() => {
    const onMessage = (e: MessageEvent) => {
      if (!isHostMessage(e.data)) return
      handleHostMessage(e.data)
    }
    window.addEventListener('message', onMessage)
    const req: UiToHostMessage = { type: 'request-state' }
    window.parent.postMessage(req, '*')
    return () => window.removeEventListener('message', onMessage)
  }, [handleHostMessage])

  function send(msg: UiToHostMessage) {
    window.parent.postMessage(msg, '*')
  }

  function handleNewTable() {
    setEditingNew(true)
    setTemplateDraft(null)
    setShowTemplates(false)
    setShowHistory(false)
    setErrors([])
    setPendingName('')
    send({ type: 'new-table' })
  }

  function handlePickTemplate(tpl: TableTemplate) {
    setEditingNew(true)
    setTemplateDraft(tpl.table)
    setShowTemplates(false)
    setShowHistory(false)
    setErrors([])
  }

  /** 复用历史表格：载入表单进入新建模式（类似模板）。 */
  function handlePickHistory(entry: HistoryEntry) {
    setEditingNew(true)
    setTemplateDraft(entry.table)
    setShowTemplates(false)
    setShowHistory(false)
    setErrors([])
  }

  /** 编辑中表格判定：有激活表格且用户未处于新建意图。 */
  const isEdit = !editingNew && activeId !== null && activeTable !== null
  // 新建模式：模板自带名称则保留，否则填入宿主下发的自动编号默认名（可修改）
  const baseInitial: TableDefinition = isEdit ? (activeTable as TableDefinition) : templateDraft ?? blankTable()
  const formInitial: TableDefinition = isEdit ? baseInitial : { ...baseInitial, name: baseInitial.name || pendingName || undefined }
  const formKey = isEdit
    ? `edit-${activeId}`
    : templateDraft
      ? `tpl-${templateDraft.name}-${pendingName}`
      : `new-${pendingName}`

  function handleSaveOrImport(table: TableDefinition) {
    setSaving(true)
    setErrors([])
    // 记录本地历史（同名覆盖置顶），便于后续从「历史」复用
    addHistory(table)
    if (isEdit && activeId) {
      send({ type: 'render-update', id: activeId, json: JSON.stringify(table) })
    } else {
      send({ type: 'render-new', json: JSON.stringify(table) })
    }
  }

  return (
    <div className="app">
      <p className="app-notice" role="note">
        ⚠️ 请勿在 Penpot 画布上解锁表格，所有修改请通过本插件编辑器完成并「保存并重渲」，否则可能导致表格结构或数据异常。
      </p>

      <header className="app-header">
        <h1>原型表格</h1>
        <p className="app-sub">多表格管理 · 表单化编辑 · 模板快速录入</p>
        <button type="button" className="btn btn-sm btn-nav header-new" onClick={handleNewTable}>
          新建
        </button>
        <button
          type="button"
          className={`btn btn-sm btn-nav header-tpl${showTemplates ? ' active' : ''}`}
          onClick={() => {
            setShowHistory(false)
            setShowTemplates((v) => !v)
          }}
        >
          模板
        </button>
        <button
          type="button"
          className={`btn btn-sm btn-nav header-tpl${showHistory ? ' active' : ''}`}
          onClick={() => {
            setShowTemplates(false)
            setShowHistory((v) => !v)
          }}
        >
          历史
        </button>
      </header>

      <div className="tabs" role="tablist" aria-label="表格列表">
        <span className="tabs-label">已存在表格：</span>
        {tables.map((t) => (
          <button
            key={t.id}
            type="button"
            className={`tab ${t.id === activeId ? 'tab-active' : ''}`}
            onClick={() => send({ type: 'select-table', id: t.id })}
            title={`${t.name}（${t.rows} 行 × ${t.cols} 列）`}
          >
            {t.name}
          </button>
        ))}
        {tables.length === 0 && <span className="tabs-empty">（暂无，可新建）</span>}
      </div>

      <ErrorList errors={errors} />

      {showTemplates ? (
        <TemplateGallery onPick={handlePickTemplate} onClose={() => setShowTemplates(false)} />
      ) : showHistory ? (
        <HistoryGallery onPick={handlePickHistory} onClose={() => setShowHistory(false)} />
      ) : isEdit || editingNew ? (
        <TableForm key={formKey} initial={formInitial} mode={isEdit ? 'edit' : 'new'} onSave={handleSaveOrImport} busy={saving} />
      ) : (
        <EmptyState onNew={handleNewTable} />
      )}
    </div>
  )
}

/** 空状态：未选中表格且未在新建。 */
function EmptyState({ onNew }: { onNew: () => void }) {
  return (
    <div className="empty-state">
      <div className="empty-title">还没有正在编辑的表格</div>
      <p className="empty-desc">你可以新建一个表格，或在画布上选中某个已渲染的表格，面板会自动切到它。</p>
      <div className="row">
        <button type="button" className="btn btn-primary" onClick={onNew}>
          ＋ 新建表格
        </button>
      </div>
      <p className="hint">提示：也可以点击「模板」从场景模板开始。</p>
    </div>
  )
}

function TemplateGallery({ onPick, onClose }: { onPick: (t: TableTemplate) => void; onClose: () => void }) {
  return (
    <div className="template-view">
      <div className="row">
        <h3 className="section-title">选择模板</h3>
        <button type="button" className="btn btn-sm" onClick={onClose}>
          返回
        </button>
      </div>
      <div className="template-grid">
        {TABLE_TEMPLATES.map((t) => (
          <button key={t.id} type="button" className="template-card" onClick={() => onPick(t)}>
            <img
              className="template-thumb"
              src={`data:image/svg+xml;utf8,${encodeURIComponent(
                thumbSvg(t.accent, t.table.columns.length, Math.min(t.table.rows, 3)),
              )}`}
              alt={t.name}
            />
            <div className="template-name">{t.name}</div>
            <div className="template-desc">{t.desc}</div>
          </button>
        ))}
      </div>
      <p className="hint">点击模板载入表单，可继续编辑后导入。</p>
    </div>
  )
}

function ErrorList({ errors }: { errors: string[] }) {
  if (errors.length === 0) return null
  return (
    <div className="error-box" role="alert">
      {errors.map((e, i) => (
        <div className="error-line" key={i}>
          {e}
        </div>
      ))}
    </div>
  )
}

/** 本地历史：点击卡片载入表单复用（类似模板），可单条删除或清空。 */
function HistoryGallery({ onPick, onClose }: { onPick: (e: HistoryEntry) => void; onClose: () => void }) {
  const [entries, setEntries] = useState<HistoryEntry[]>(() => loadHistory())

  return (
    <div className="template-view">
      <div className="row">
        <h3 className="section-title">历史表格（本地缓存）</h3>
        <button type="button" className="btn btn-sm" onClick={onClose}>
          返回
        </button>
        {entries.length > 0 && (
          <button
            type="button"
            className="btn btn-sm btn-danger"
            onClick={() => setEntries(clearHistory())}
          >
            清空历史
          </button>
        )}
      </div>
      {entries.length === 0 ? (
        <div className="empty-state">
          <div className="empty-title">暂无历史</div>
          <p className="empty-desc">保存（新建/更新）表格后会自动记录到这里，下次可快速复用。</p>
        </div>
      ) : (
        <div className="template-grid">
          {entries.map((e) => (
            <div
              key={e.id}
              className={`template-card history-card${e.pinned ? ' pinned' : ''}`}
              onClick={() => onPick(e)}
              title={`复用「${e.name}」`}
            >
              <img
                className="template-thumb"
                src={`data:image/svg+xml;utf8,${encodeURIComponent(
                  thumbSvg(e.accent, e.cols, Math.min(e.rows, 3)),
                )}`}
                alt={e.name}
              />
              <button
                type="button"
                className="btn btn-sm history-pin"
                onClick={(ev) => {
                  ev.stopPropagation()
                  setEntries(togglePinHistory(e.id))
                }}
                title={e.pinned ? '取消置顶' : '置顶，始终排在最前'}
              >
                {e.pinned ? '📌 取消置顶' : '置顶'}
              </button>
              <button
                type="button"
                className="btn btn-sm btn-danger history-delete"
                onClick={(ev) => {
                  ev.stopPropagation()
                  setEntries(removeHistory(e.id))
                }}
              >
                删除
              </button>
              <div className="template-name">{e.name}</div>
              <div className="template-desc">
                {e.rows} 行 × {e.cols} 列 · {formatSavedAt(e.savedAt)}
                {e.pinned ? ' · 已置顶' : ''}
              </div>
            </div>
          ))}
        </div>
      )}
      <p className="hint">点击卡片载入表单，可继续编辑后导入；历史仅保存在本机。</p>
    </div>
  )
}

/** 格式化保存时间：今天显示 HH:MM，近 7 天显示 周X，更早显示 M月D日。 */
function formatSavedAt(savedAt: number): string {
  const d = new Date(savedAt)
  const now = new Date()
  const sameDay = d.toDateString() === now.toDateString()
  if (sameDay) return `今天 ${d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })}`
  const days = Math.floor((now.getTime() - d.getTime()) / 86400000)
  if (days < 7) return ['周日', '周一', '周二', '周三', '周四', '周五', '周六'][d.getDay()]
  return `${d.getMonth() + 1}月${d.getDate()}日`
}
