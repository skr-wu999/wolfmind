import { defineConfig, devices } from '@playwright/test'

/**
 * 跨浏览器冒烟：真实后端 + 本配置自起的 Vite（真实适配器）。
 *
 * 后端不由本配置启动：它需要 Java 类路径、存储模式与 Redis，这些属于后端的运行约定。
 * 先按 README 启动后端，再 `npm run e2e:browsers`；后端地址用 `WOLFMIND_E2E_API` 覆盖。
 */
const api = process.env.WOLFMIND_E2E_API ?? 'http://127.0.0.1:18080'
const port = Number(process.env.WOLFMIND_E2E_PORT ?? 5180)

export default defineConfig({
  testDir: './e2e',
  timeout: 60_000,
  fullyParallel: false,
  workers: 1,
  reporter: [['list']],
  use: {
    baseURL: `http://127.0.0.1:${port}`,
    locale: 'zh-CN',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
    { name: 'firefox', use: { ...devices['Desktop Firefox'] } },
    { name: 'webkit', use: { ...devices['Desktop Safari'] } },
  ],
  webServer: {
    command: `npx vite --host 127.0.0.1 --port ${port} --strictPort`,
    url: `http://127.0.0.1:${port}`,
    reuseExistingServer: false,
    timeout: 60_000,
    env: {
      VITE_USE_REAL_API: 'true',
      VITE_API_BASE_URL: '',
      VITE_DEV_API_TARGET: api,
    },
  },
})
