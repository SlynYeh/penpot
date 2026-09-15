/**
 * 构建收尾：把 manifest.json 复制到 dist/，并校验产物完整性。
 */
import { copyFileSync, existsSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const dist = resolve(root, 'dist')

for (const f of ['ui.html', 'plugin.js']) {
  if (!existsSync(resolve(dist, f))) {
    console.error(`[finalize] 缺少 dist/${f}，构建产物不完整`)
    process.exit(1)
  }
}

copyFileSync(resolve(root, 'manifest.json'), resolve(dist, 'manifest.json'))
console.log('[finalize] dist/manifest.json 已就绪')
console.log('[finalize] 产物：dist/manifest.json, dist/plugin.js, dist/ui.html, dist/assets/')
