/**
 * 清理构建产物目录 dist。
 */
import { rmSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const dist = resolve(dirname(fileURLToPath(import.meta.url)), '..', 'dist')
rmSync(dist, { recursive: true, force: true })
console.log('[clean] dist 已清理')
