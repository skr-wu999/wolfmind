import assert from 'node:assert/strict'
import { createServer } from 'vite'
import { chromium } from '@playwright/test'
import { networkInterfaces } from 'node:os'

const port = Number(process.env.WOLFMIND_HTTP_CHECK_PORT ?? 5199)
const host = process.env.WOLFMIND_HTTP_CHECK_HOST ?? Object.values(networkInterfaces()).flat()
  .find(address => address?.family === 'IPv4' && !address.internal
    && /^(192\.168\.|10\.|172\.(1[6-9]|2\d|3[01])\.)/.test(address.address))?.address
assert.ok(host, 'Set WOLFMIND_HTTP_CHECK_HOST to a local LAN IPv4 address')
const html = `<!doctype html><html><body><script type="module">
import { createPinia } from 'pinia'
import { useGameStore } from '/src/stores/game.ts'
import { createMockGameAdapter } from '/src/features/game/mockGameAdapter.ts'
import { newIdempotencyKey } from '/src/api/apiClient.ts'
try {
  const ids = Array.from({ length: 100 }, () => newIdempotencyKey('action'))
  const actions = []
  for (const [scenario, type] of [['my-turn', 'SPEAK'], ['voting', 'VOTE'], ['seer', 'SEER_INSPECT']]) {
    const adapter = createMockGameAdapter()
    await adapter.scenario('demo', scenario)
    const source = { ...adapter, async submit(id, command) {
      actions.push({ type: command.actionType, commandId: command.commandId })
      return adapter.submit(id, command)
    } }
    const store = useGameStore(createPinia())
    await store.load('demo', source)
    const params = type === 'SPEAK' ? { content: '我想听听大家的判断。' }
      : { targetSeatNo: store.view.permissions.find(p => p.type === type).targets[0] }
    if (!await store.submit(type, params)) throw new Error(store.error)
    store.dispose()
  }
  window.httpCheck = { secure: window.isSecureContext, nativeUuid: typeof crypto.randomUUID, ids, actions }
} catch (error) { window.httpCheckError = String(error) }
</script></body></html>`

let browser
const server = await createServer({
  server: { host, port, strictPort: true },
  plugins: [{
    name: 'http-uuid-check',
    configureServer(vite) {
      vite.middlewares.use(async (req, res, next) => {
        if (req.url !== '/__http_uuid_check') return next()
        try {
          res.setHeader('Content-Type', 'text/html; charset=utf-8')
          res.end(await vite.transformIndexHtml(req.url, html))
        } catch (error) { next(error) }
      })
    },
  }],
})
try {
  await server.listen()
  browser = await chromium.launch({ args: ['--no-proxy-server'] })
  const page = await browser.newPage()
  page.on('pageerror', error => console.error('Browser error:', error.message))
  page.on('console', message => { if (message.type() === 'error') console.error('Browser console:', message.text()) })
  page.on('requestfailed', request => console.error('Request failed:', request.url(), request.failure()?.errorText))
  const response = await page.goto(`http://${host}:${port}/__http_uuid_check`)
  assert.equal(response.status(), 200)
  await page.waitForFunction(() => window.httpCheck || window.httpCheckError)
  const result = await page.evaluate(() => ({ result: window.httpCheck, error: window.httpCheckError }))
  assert.equal(result.error, undefined)
  assert.equal(result.result.secure, false)
  assert.equal(result.result.nativeUuid, 'undefined')
  assert.equal(new Set(result.result.ids).size, 100)
  const uuid = /^action_[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/
  result.result.ids.forEach(id => assert.match(id, uuid))
  assert.deepEqual(result.result.actions.map(a => a.type), ['SPEAK', 'VOTE', 'SEER_INSPECT'])
  result.result.actions.forEach(a => assert.match(a.commandId, uuid))
  console.log('PASS: insecure HTTP, randomUUID unavailable, 100 unique UUIDs, speech/vote/skill submitted')
} finally {
  await browser?.close()
  await server.close()
}
