/**
 * 消息协议：UI ↔ 宿主 postMessage 桥的信令定义。
 *
 * 依据 Penpot 官方机制（@penpot/plugin-types 与 starter 模板）：
 * - UI → 宿主：`window.parent.postMessage(message, '*')`，Penpot 桥直接透传给
 *   `penpot.ui.onMessage`，无需额外的 pluginMessage 包装。
 * - 宿主 → UI：`penpot.ui.sendMessage(msg)`，UI 侧在 `window.message` 事件中
 *   以 `event.data` 直接收到该消息。
 *
 * 多表格模型：宿主每次通过扫描页面上的本插件表格生成「状态快照」下发，
 * UI 只负责渲染快照（TAB 栏 + 当前编辑表格的 JSON）。
 */

/** UI 可展示的表格摘要（不含 JSON 正文，正文随 activeId 单独下发）。 */
export interface TableInfoMsg {
  id: string
  name: string
  rows: number
  cols: number
}

/** UI → 宿主 */
export type UiToHostMessage =
  | { type: 'request-state' }
  | { type: 'render-new'; json: string }
  | { type: 'render-update'; id: string; json: string }
  | { type: 'select-table'; id: string }
  | { type: 'new-table' }
  /** 请求下一个自动编号的表格名（宿主回复 next-name）。 */
  | { type: 'request-next-name' }

/** 宿主 → UI */
export type HostToUiMessage =
  | {
      type: 'state'
      tables: TableInfoMsg[]
      /** 当前正在编辑的表格 id；null 表示新建表格模式 */
      activeId: string | null
      /** activeId 对应表格的 JSON；null 表示新建模式 */
      json: string | null
    }
  | { type: 'render:error'; errors: string[] }
  /** 自动编号的默认表格名（新建时下发，UI 填入输入框，可修改）。 */
  | { type: 'next-name'; name: string }

/** 判断消息是否为宿主发出的本协议消息。 */
export function isHostMessage(data: unknown): data is HostToUiMessage {
  if (!data || typeof data !== 'object') return false
  const t = (data as { type?: unknown }).type
  return t === 'state' || t === 'render:error' || t === 'next-name'
}
