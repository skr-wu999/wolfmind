import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },
  test: {
    /*
     * `npm test` 跑单元测试；集成测试**不在这个 glob 里**。
     *
     * 两组集成测试各自需要外部条件，因此单独用脚本运行：
     * - `npm run e2e:adapters` → `*.integration.test.ts`（需要真实后端）
     * - `npm run e2e:pages`     → `*.pages.test.ts`（需要真实后端 + jsdom）
     *
     * 用配置表达"哪些文件属于单元测试"而不是 `skipIf(!env)`：环境变量的跨平台写法
     * 本身就是个坑（npm 在 Windows 上走 cmd，`VAR=x command` 会直接报错），
     * 而"哪些文件跑在哪一组"是结构问题。
     */
    include: ['tests/**/*.test.{js,ts}'],
    exclude: ['tests/**/*.integration.test.{js,ts}', 'tests/**/*.pages.test.{js,ts}', 'tests/**/*.ws.test.{js,ts}', '**/node_modules/**'],
    environment: 'node',
  },
})
