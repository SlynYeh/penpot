/**
 * 开发服务器：先完成一次全量构建，随后开启 UI / 宿主双 watch 构建，
 * 并用 Node 静态服务 dist/。在 Penpot「开发插件」中加载：
 *   http://localhost:5173/manifest.json
 * 端口可用 PORT 环境变量覆盖。
 */
import { spawn, spawnSync } from 'node:child_process'
import { existsSync, readFileSync, statSync } from 'node:fs'
import { createServer } from 'node:http'
import { dirname, extname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const dist = join(root, 'dist')
const viteBin = join(root, 'node_modules', 'vite', 'bin', 'vite.js')
const PORT = Number(process.env.PORT ?? 5173)

function run(args) {
  return spawnSync(process.execPath, args, { cwd: root, stdio: 'inherit' })
}

if (!existsSync(viteBin)) {
  console.error('[dev] 未找到 vite，请先执行 npm install')
  process.exit(1)
}

// 初始全量构建
console.log('[dev] 首次构建…')
let r = run([join(root, 'scripts', 'clean.mjs')])
if (r.status !== 0) process.exit(1)
r = run([viteBin, 'build'])
if (r.status !== 0) process.exit(1)
r = run([viteBin, 'build', '--config', 'vite.plugin.config.ts'])
if (r.status !== 0) process.exit(1)
r = run([join(root, 'scripts', 'finalize.mjs')])
if (r.status !== 0) process.exit(1)

// watch 重建（源文件变更时自动重新打包，Penpot 需手动重载插件面板；
// 注意：manifest.json 变更后需重启 npm run dev 以重新复制）
const watchers = [
  spawn(process.execPath, [viteBin, 'build', '--watch'], { cwd: root, stdio: 'inherit' }),
  spawn(process.execPath, [viteBin, 'build', '--config', 'vite.plugin.config.ts', '--watch'], {
    cwd: root,
    stdio: 'inherit',
  }),
]

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.map': 'application/json',
}

const server = createServer((req, res) => {
  const urlPath = decodeURIComponent((req.url || '/').split('?')[0])
  let file = join(dist, urlPath === '/' ? '/ui.html' : urlPath)
  try {
    const st = statSync(file)
    if (st.isDirectory()) file = join(file, 'index.html')
    const body = readFileSync(file)
    res.writeHead(200, {
      'Content-Type': MIME[extname(file).toLowerCase()] ?? 'application/octet-stream',
      'Cache-Control': 'no-store',
      'Access-Control-Allow-Origin': '*',
    })
    res.end(body)
  } catch {
    res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' })
    res.end('Not Found')
  }
})

server.on('error', (err) => {
  if (err && err.code === 'EADDRINUSE') {
    console.error(`[dev] 端口 ${PORT} 已被占用。可能有一个残留的 dev server 在运行。`)
    console.error('[dev] 请结束占用该端口的进程后重试（Windows: netstat -ano | findstr :5173，再 taskkill /PID <pid> /F）。')
  } else {
    console.error('[dev] 服务器错误：', err)
  }
  process.exit(1)
})

server.listen(PORT, () => {
  console.log(`[dev] 静态服务已启动： http://localhost:${PORT}/`)
  console.log(`[dev] 在 Penpot「插件 → 开发插件」中加载： http://localhost:${PORT}/manifest.json`)
  console.log('[dev] 按 Ctrl+C 停止。')
})

function shutdown() {
  for (const w of watchers) w.kill()
  server.close(() => process.exit(0))
}
process.on('SIGINT', shutdown)
process.on('SIGTERM', shutdown)
