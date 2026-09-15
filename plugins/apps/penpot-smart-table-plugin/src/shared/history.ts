/**
 * 本地历史：用户在插件中保存（新建/更新渲染）的表格自动记录到 localStorage，
 * 可在「历史」页快速复用（类似模板）。纯函数 + 可注入存储，便于测试。
 */

import type { TableDefinition } from './types'

export interface HistoryEntry {
  /** 历史条目的本地唯一 id（宿主表格 id 在新建时不可知，故用本地生成）。 */
  id: string
  /** 表格名（保存时快照，后续改名不影响历史）。 */
  name: string
  rows: number
  cols: number
  /** 保存时间（epoch ms）。 */
  savedAt: number
  /** 缩略图主色（由主题推导）。 */
  accent: string
  /** 完整的表格定义快照，用于复用载入表单。 */
  table: TableDefinition
  /** 是否置顶：置顶条目始终排在历史最前，且不会被超出上限的淘汰挤掉。 */
  pinned: boolean
}

const KEY = 'penpot-smart-table-plugin:history'
const MAX_ENTRIES = 20

/** 可注入的存储接口（浏览器 localStorage 的最小切面）。 */
export type HistoryStorage = Pick<Storage, 'getItem' | 'setItem'>

function resolveStorage(storage?: HistoryStorage | null): HistoryStorage | null {
  if (storage !== undefined) return storage
  return typeof localStorage !== 'undefined' ? localStorage : null
}

function isHistoryEntry(v: unknown): v is HistoryEntry {
  if (typeof v !== 'object' || v === null) return false
  const o = v as Record<string, unknown>
  return (
    typeof o.id === 'string' &&
    typeof o.name === 'string' &&
    typeof o.rows === 'number' &&
    typeof o.cols === 'number' &&
    typeof o.savedAt === 'number' &&
    typeof o.accent === 'string' &&
    typeof o.table === 'object' &&
    o.table !== null
  )
}

/** 兼容旧数据：pinned 字段缺省视为未置顶。 */
function normalize(e: HistoryEntry): HistoryEntry {
  return { ...e, pinned: e.pinned === true }
}

/** 置顶条目永远排在最前，其余按保存时间倒序。 */
function sortEntries(entries: HistoryEntry[]): HistoryEntry[] {
  return [...entries].sort(
    (a, b) => (b.pinned ? 1 : 0) - (a.pinned ? 1 : 0) || b.savedAt - a.savedAt,
  )
}

/** 读取历史列表（置顶在前，其余最新在前）。存储不可用或数据损坏时返回空数组。 */
export function loadHistory(storage?: HistoryStorage | null): HistoryEntry[] {
  const s = resolveStorage(storage)
  if (!s) return []
  try {
    const raw = s.getItem(KEY)
    if (!raw) return []
    const parsed: unknown = JSON.parse(raw)
    if (!Array.isArray(parsed)) return []
    return sortEntries(parsed.filter(isHistoryEntry).map(normalize))
  } catch {
    return []
  }
}

function write(entries: HistoryEntry[], storage?: HistoryStorage | null): HistoryEntry[] {
  const s = resolveStorage(storage)
  if (s) {
    try {
      s.setItem(KEY, JSON.stringify(entries))
    } catch {
      /* 存储满/不可写时静默忽略，历史仍可用 */
    }
  }
  return entries
}

/** 由主题推导缩略图主色（与模板视觉一致）。 */
export function accentForTheme(theme?: string): string {
  return theme === 'dark' ? '#2f3542' : '#4377d9'
}

/**
 * 记录一次保存：同名表格视为同一历史（覆盖并置顶，保留原置顶状态），
 * 最多保留 MAX_ENTRIES 条（置顶条目优先保留，不被淘汰）。
 * 返回写入后的完整列表（置顶在前，其余最新在前）。
 */
export function addHistory(table: TableDefinition, storage?: HistoryStorage | null): HistoryEntry[] {
  const name = table.name && table.name.trim() !== '' ? table.name.trim() : '未命名表格'
  const prev = loadHistory(storage)
  const wasPinned = prev.find((e) => e.name === name)?.pinned === true
  const entry: HistoryEntry = {
    id: `h${Date.now().toString(36)}${Math.random().toString(36).slice(2, 7)}`,
    name,
    rows: table.rows,
    cols: table.columns.length,
    savedAt: Date.now(),
    accent: accentForTheme(table.theme),
    table,
    pinned: wasPinned,
  }
  const rest = prev.filter((e) => e.name !== name)
  return write(sortEntries([entry, ...rest]).slice(0, MAX_ENTRIES), storage)
}

/** 切换指定历史的置顶状态，返回排序后的完整列表。 */
export function togglePinHistory(id: string, storage?: HistoryStorage | null): HistoryEntry[] {
  const next = loadHistory(storage).map((e) => (e.id === id ? { ...e, pinned: !e.pinned } : e))
  return write(sortEntries(next), storage)
}

/** 删除指定历史条目，返回删除后的列表。 */
export function removeHistory(id: string, storage?: HistoryStorage | null): HistoryEntry[] {
  return write(loadHistory(storage).filter((e) => e.id !== id), storage)
}

/** 清空全部历史，返回空列表。 */
export function clearHistory(storage?: HistoryStorage | null): HistoryEntry[] {
  return write([], storage)
}
