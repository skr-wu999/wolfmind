import { expect, test, type APIRequestContext, type Browser } from '@playwright/test'

const API = process.env.WOLFMIND_E2E_API ?? 'http://127.0.0.1:18080'

async function post(request: APIRequestContext, path: string, token: string, data: object = {}) {
  const response = await request.post(`${API}/api/v1${path}`, {
    headers: { Authorization: `Bearer ${token}`, 'Idempotency-Key': `exit_${crypto.randomUUID()}` }, data,
  })
  expect(response.ok(), await response.text()).toBeTruthy()
  return response.json()
}

async function account(request: APIRequestContext, suffix: string) {
  const username = `exit_${suffix}`, password = 'correct-horse-battery'
  await post(request, '/auth/register', '', { username, password })
  return post(request, '/auth/login', '', { username, password })
}

async function playerPage(browser: Browser, token: string, baseURL: string, gameId: string) {
  const context = await browser.newContext({ baseURL, locale: 'zh-CN' })
  await context.addInitScript(value => localStorage.setItem('wolfmind.sessionToken', value), token)
  const page = await context.newPage()
  const errors: string[] = []
  page.on('pageerror', error => errors.push(error.message))
  await page.goto(`/games/${gameId}`)
  await expect(page.locator('#phase-title')).toBeVisible()
  await expect(page.locator('.game-connection')).toHaveCount(0, { timeout: 15_000 })
  return { context, page, errors }
}

test('两个真实账号退出：先 AI 接管，再关闭房间并保留中止记录', async ({ browser, request, baseURL }, info) => {
  const suffix = Date.now().toString(36)
  const owner = await account(request, `o_${suffix}`), alice = await account(request, `a_${suffix}`)
  const room = await post(request, '/rooms', owner.sessionToken, { name: `退出验收 ${suffix}`, config: { playerCount: 9 } })
  await post(request, `/rooms/${room.roomId}/join`, alice.sessionToken)
  await post(request, `/rooms/${room.roomId}/ready`, alice.sessionToken)
  await post(request, `/rooms/${room.roomId}/ready`, owner.sessionToken)
  const game = await post(request, `/rooms/${room.roomId}/games`, owner.sessionToken, { autoFillAi: true })
  const first = await playerPage(browser, owner.sessionToken, baseURL!, game.gameId)
  const second = await playerPage(browser, alice.sessionToken, baseURL!, game.gameId)
  try {
    await first.page.getByRole('button', { name: '退出房间并返回大厅', exact: true }).click()
    await expect(first.page.getByRole('dialog')).toContainText('AI')
    await first.page.keyboard.press('Escape')
    await expect(first.page.getByRole('dialog')).toHaveCount(0)
    await expect(first.page.getByRole('button', { name: '退出房间并返回大厅', exact: true })).toBeFocused()
    await expect(first.page).toHaveURL(new RegExp(`/games/${game.gameId}$`))
    await first.page.getByRole('button', { name: '退出房间并返回大厅', exact: true }).click()
    await first.page.screenshot({ path: info.outputPath('exit-confirm-desktop.png'), fullPage: true })
    await first.page.getByRole('button', { name: '确认退出房间', exact: true }).click()
    await expect(first.page).toHaveURL(/\/lobby$/)
    const row = first.page.locator('.room-row').filter({ hasText: room.name })
    await expect(row).toContainText('真人 1 · AI 8')
    const afterFirst = await (await request.get(`${API}/api/v1/rooms/${room.roomId}`)).json()
    expect(afterFirst.ownerId).toBe(alice.userId)
    expect(afterFirst.status).toBe('RUNNING')
    expect(afterFirst.seats[0].controller.type).toBe('AI')
    for (const width of [320, 375, 768, 1280]) {
      await first.page.setViewportSize({ width, height: 812 })
      expect(await first.page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy()
    }
    await first.page.setViewportSize({ width: 375, height: 812 })
    await first.page.screenshot({ path: info.outputPath('lobby-counts-mobile.png'), fullPage: true })
    expect(await first.page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy()
    await second.page.setViewportSize({ width: 375, height: 812 })
    await second.page.getByRole('button', { name: '退出房间并返回大厅', exact: true }).click()
    await second.page.screenshot({ path: info.outputPath('exit-confirm-mobile.png'), fullPage: true })
    expect(await second.page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy()
    await second.page.getByRole('button', { name: '确认退出房间', exact: true }).focus()
    await second.page.keyboard.press('Enter')
    await expect(second.page).toHaveURL(/\/lobby$/)
    await expect(second.page.locator('.room-row').filter({ hasText: room.name })).toHaveCount(0)
    await expect(row).toHaveCount(0, { timeout: 20_000 })
    const response = await request.get(`${API}/api/v1/games/${game.gameId}`, {
      headers: { Authorization: `Bearer ${alice.sessionToken}` },
    })
    const state = await response.json()
    expect(state.status).toBe('ABORTED')
    expect(state.finishReason).toBe('NO_HUMANS')
    expect(state.winner).toBeNull()
    await second.page.goto(`/games/${game.gameId}`)
    await expect(second.page.locator('#phase-title')).toContainText('游戏结束')
    await expect(second.page.locator('.game-connection')).toHaveCount(0, { timeout: 15_000 })
    await expect(second.page.getByRole('link', { name: '本局回放', exact: true })).toBeVisible()
    await second.page.screenshot({ path: info.outputPath('aborted-result-mobile.png'), fullPage: true })
    expect(first.errors).toEqual([])
    expect(second.errors).toEqual([])
  } finally {
    await first.context.close()
    await second.context.close()
  }
})
