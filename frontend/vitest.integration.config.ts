import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

/**
 * 集成测试专用配置：`npm run e2e:adapters`。
 *
 * 它需要一个**真实后端**在运行（默认 `http://127.0.0.1:18080`，可用
 * `WOLFMIND_E2E_BASE` 覆盖）。
 *
 * 为什么单独一个配置文件而不是靠环境变量开关：**npm 在 Windows 上走 cmd**，
 * `VAR=x command` 那种写法会直接报错，而依赖用户手工 `export` 等于把"可复现"交给运气。
 * 用"哪个配置文件"表达"跑哪一组测试"是跨平台且不会忘的。
 */
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },
  /*
   * 后端地址在**构建时**注入 `import.meta.env`，而不是运行时用 `vi.stubEnv`。
   *
   * 原因：`config.ts` 里的 `import.meta.env.VITE_API_BASE_URL` 在**模块求值那一刻**就被读走，
   * 而 `vi.stubEnv` 是在测试体里执行的——那时 `apiClient` 已经导入完毕，读到的是默认值 8080。
   * 我在这上面踩了一次，症状是 `ECONNREFUSED 127.0.0.1:8080`，
   * 看起来像"后端没起来"，实际是地址没传进去。
   */
  define: {
    'import.meta.env.VITE_API_BASE_URL': JSON.stringify(
      process.env.WOLFMIND_E2E_BASE ?? 'http://127.0.0.1:18080',
    ),
  },
  test: {
    include: ['tests/**/*.integration.test.{js,ts}'],
    environment: 'node',
    // 集成测试要打真实网络，串行以避免相互干扰（它们各自建房、开局）。
    fileParallelism: false,
    /*
     * 超时给足：AI 推进一局要等 9 个 Agent 走完夜间行动。
     *
     * 规则模式是秒级的，但上界必须能容纳"慢"——否则测试会以超时的方式失败，
     * 而那种失败信息分不清"卡住了"与"只是慢"，排查方向会完全错。
     */
    testTimeout: 180_000,
  },
})
