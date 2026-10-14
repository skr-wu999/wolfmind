import { expect, test, type APIRequestContext } from '@playwright/test'

const API = process.env.WOLFMIND_E2E_API ?? 'http://127.0.0.1:18080'

async function post(request: APIRequestContext, path: string, token: string, key: string, data: object) {
  const response = await request.post(`${API}/api/v1${path}`, {
    headers: { Authorization: `Bearer ${token}`, 'Idempotency-Key': key },
    data,
  })
  expect(response.ok(), `${path} → ${response.status()} ${await response.text()}`).toBeTruthy()
  return response.json()
}

test('注册登录 → 大厅 → 开局后从大厅回到对局', async ({ page, request }, testInfo) => {
  const suffix = `${testInfo.project.name.slice(0, 2)}${Date.now().toString(36)}`
  const username = `pw_${suffix}`
  const password = 'correct-horse-battery'

  await page.goto('/login')
  await expect(page.getByRole('heading', { name: '进入雾隐村' })).toBeVisible()
  await page.screenshot({ path: testInfo.outputPath('login.png'), fullPage: true })

  await page.getByRole('tab', { name: '注册' }).click()
  await page.locator('#login-username').fill(username)
  await page.locator('#login-password').fill(password)
  await page.locator('#login-confirm').fill(password)
  await page.locator('#login-submit').click()
  await expect(page).toHaveURL(/\/lobby$/)
  await expect(page.locator('.lobby-resume')).toHaveCount(0)

  const token = await page.evaluate(() => localStorage.getItem('wolfmind.sessionToken'))
  expect(token, '登录后令牌应写入 localStorage').toBeTruthy()

  const room = await post(request, '/rooms', token!, `pw_room_${suffix}`, { name: `跨浏览器 ${suffix}`, config: { playerCount: 9 } })
  await post(request, `/rooms/${room.roomId}/ready`, token!, `pw_ready_${suffix}`, {})
  const game = await post(request, `/rooms/${room.roomId}/games`, token!, `pw_start_${suffix}`, { autoFillAi: true })

  await page.reload()
  const resume = page.locator('.lobby-resume')
  await expect(resume).toContainText('你有一局正在进行。')
  await page.screenshot({ path: testInfo.outputPath('lobby-resume.png'), fullPage: true })

  await resume.getByRole('link', { name: /继续对局/ }).click()
  await expect(page).toHaveURL(new RegExp(`/games/${game.gameId}$`))
  await expect(page.locator('#phase-title')).toContainText('第 1')
  // 同步横幅消失说明 WebSocket 认证、订阅与快照基线都已完成。
  await expect(page.locator('.game-connection')).toHaveCount(0, { timeout: 15_000 })
  await page.screenshot({ path: testInfo.outputPath('game.png'), fullPage: true })
})
