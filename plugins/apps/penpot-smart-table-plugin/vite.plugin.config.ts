import { resolve } from 'node:path'
import { defineConfig } from 'vite'

// 宿主脚本构建：src/plugin.ts → dist/plugin.js（IIFE 经典脚本，供 Penpot 直接加载）
export default defineConfig({
  build: {
    outDir: 'dist',
    emptyOutDir: false,
    target: 'es2020',
    sourcemap: true,
    lib: {
      entry: resolve(__dirname, 'src/plugin.ts'),
      formats: ['iife'],
      name: 'SmartTablePlugin',
      fileName: () => 'plugin.js',
    },
  },
})
