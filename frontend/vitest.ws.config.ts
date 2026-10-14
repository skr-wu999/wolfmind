import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

/**
 * 实时通道集成测试配置：`npm run e2e:ws`。
 *
 * 环境是 **node** 而不是 jsdom：`GameSocket` 直接用全局 `WebSocket`，
 * 而 Node 22+ 自带它；jsdom 的 WebSocket 是另一套实现，反而会引入差异。
 * 需要 `localStorage` 的地方由测试自己装替身。
 *
 * 后端地址在**构建时**注入 `import.meta.env`（`VITE_API_BASE_URL` 用于 REST 建房、
 * `VITE_WS_URL` 用于实时连接）。为什么不运行时 `vi.stubEnv`：`config.ts` 在模块求值
 * 那一刻就把值读走了——第 15 轮踩过，症状是 `ECONNREFUSED 127.0.0.1:8080`，
 * 看起来像后端没起来，实际是地址没传进去。
 */
const base = process.env.WOLFMIND_E2E_BASE ?? 'http://127.0.0.1:18080'
const wsUrl = process.env.WOLFMIND_E2E_WS ?? base.replace(/^http/, 'ws') + '/ws'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },
  define: {
    'import.meta.env.VITE_API_BASE_URL': JSON.stringify(base),
    'import.meta.env.VITE_WS_URL': JSON.stringify(wsUrl),
  },
  test: {
    include: ['tests/**/*.ws.test.{js,ts}'],
    environment: 'node',
    fileParallelism: false,
    // 实时通道有等待帧的步骤，默认 5 秒偏紧。
    testTimeout: 20_000,
  },
})
