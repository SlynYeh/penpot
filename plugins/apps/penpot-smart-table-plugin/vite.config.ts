import { resolve } from 'node:path'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// UI 页面构建：ui.html → dist/ui.html + assets
export default defineConfig({
  plugins: [react()],
  base: './',
  build: {
    outDir: 'dist',
    // 目录清理交由 scripts/clean.mjs 处理（watch 构建不得清空 dist，避免抹掉 manifest/plugin）
    emptyOutDir: false,
    rollupOptions: {
      input: {
        ui: resolve(__dirname, 'ui.html'),
      },
    },
  },
})
