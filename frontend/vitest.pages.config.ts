import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

/**
 * 页面级集成测试配置：`npm run e2e:pages`。
 *
 * 与 `vitest.integration.config.ts`（适配器层）分开的理由：这一组需要 **jsdom**
 * （要挂载 Vue 组件），而适配器层那组是纯 node。混在一个配置里只能二选一。
 *
 * 两个 `import.meta.env` 变量在**构建时**注入：
 * - `VITE_API_BASE_URL`：后端地址（`WOLFMIND_E2E_BASE` 覆盖，默认 18080）。
 * - `VITE_USE_REAL_API=true`：让注册表返回真实适配器。**这一条是页面级测试的意义所在**——
 *   它顺带证明了"页面确实通过注册表取适配器"，而不是各自 import mock。
 *
 * 为什么不在测试体里用 `vi.stubEnv`：`config.ts` 在模块求值那一刻就把值读走了，
 * 而测试体执行时 `apiClient` 早已导入完毕。这个坑在第 15 轮踩过一次，
 * 症状是 `ECONNREFUSED 127.0.0.1:8080`——看起来像后端没起来，实际是地址没传进去。
 */
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },
  define: {
    'import.meta.env.VITE_API_BASE_URL': JSON.stringify(
      process.env.WOLFMIND_E2E_BASE ?? 'http://127.0.0.1:18080',
    ),
    'import.meta.env.VITE_USE_REAL_API': JSON.stringify('true'),
  },
  test: {
    include: ['tests/**/*.pages.test.{js,ts}'],
    environment: 'jsdom',
    fileParallelism: false,
    // 页面要等 REST 视图 + 实时连接建立，默认 5 秒偏紧。
    testTimeout: 20_000,
  },
})
