/**
 * 图片选择弹层：9 张内置样图（成功/上传失败/占位/信息/警告/锁定/下载/星标/时钟）+ URL 自定义。
 * 图片列默认图配置与单元格数据录入共用；当前值为内置图时高亮对应样图。
 */

import { useState } from 'react'
import { PRESET_IMAGES } from '../shared/constants'
import { isImagePreset } from '../shared/types'

interface ImagePickerProps {
  /** 弹层标题。 */
  title?: string
  /** 当前单元格值（preset:xxx 或 URL），用于高亮/填充输入框。 */
  value?: string
  /** 选中图片（preset:xxx 或 URL）时回调。 */
  onPick: (value: string) => void
  /** 关闭弹层。 */
  onClose: () => void
}

export default function ImagePicker({ title, value, onPick, onClose }: ImagePickerProps) {
  const [url, setUrl] = useState(
    typeof value === 'string' && !isImagePreset(value) ? value : '',
  )
  const currentPreset = typeof value === 'string' && isImagePreset(value) ? value.slice('preset:'.length) : ''

  return (
    <div className="preview-overlay" onClick={onClose}>
      <div className="preview-panel image-picker-panel" onClick={(e) => e.stopPropagation()}>
        <div className="preview-bar">
          <span className="field-label">{title ?? '选择图片'}</span>
          <button type="button" className="btn btn-sm" onClick={onClose}>
            返回
          </button>
        </div>
        <div className="image-picker">
          <div className="image-picker-grid">
            {PRESET_IMAGES.map((p) => (
              <button
                key={p.id}
                type="button"
                className={`image-picker-tile${p.id === currentPreset ? ' selected' : ''}`}
                onClick={() => onPick(`preset:${p.id}`)}
              >
                <span className="image-picker-preview" style={{ background: p.bg, color: p.fg }}>
                  {p.symbol}
                </span>
                <span className="image-picker-label">{p.label}</span>
              </button>
            ))}
          </div>
          <div className="image-picker-url">
            <input
              className="input input-grow"
              value={url}
              onChange={(e) => setUrl(e.target.value)}
              placeholder="或输入图片 URL（https://…）"
              onKeyDown={(e) => {
                if (e.key === 'Enter' && url.trim()) onPick(url.trim())
              }}
            />
            <button
              type="button"
              className="btn"
              disabled={!url.trim()}
              onClick={() => onPick(url.trim())}
            >
              使用该 URL
            </button>
            {value && (
              <button
                type="button"
                className="btn btn-sm btn-danger"
                onClick={() => onPick('')}
                title="清除当前图片"
              >
                清除
              </button>
            )}
          </div>
          <p className="hint">点击样图即可选中；URL 图会在渲染时异步加载。</p>
        </div>
      </div>
    </div>
  )
}
