import { chromium } from '@playwright/test'
import fs from 'node:fs'

const browser = await chromium.launch()
const page = await browser.newPage({ locale: 'zh-CN' })
await page.addInitScript(() => localStorage.setItem('wolfmind.sessionToken', 'local-ui-check'))
const errors = []
page.on('pageerror', error => errors.push(error.message))
fs.mkdirSync('test-artifacts', { recursive: true })
const results = []
try {
  for (const screen of ['game', 'room']) {
    await page.goto(`http://127.0.0.1:5192/${screen === 'game' ? 'games/demo' : 'rooms/101001'}`)
    const button = page.getByRole('button', { name: screen === 'game' ? '查看规则' : /进阶选项/ })
    await button.click()
    const dialog = page.getByRole('dialog')
    await dialog.waitFor()
    const text = await dialog.innerText()
    if (!text.includes('平民全部出局或神职全部出局') || !text.includes('每瓶整局只能使用一次'))
      throw new Error(`${screen}: rule explanation missing`)
    for (const width of [1280, 375, 320]) {
      await page.setViewportSize({ width, height: 900 })
      if (await page.evaluate(() => document.documentElement.scrollWidth > innerWidth))
        throw new Error(`${screen}: overflow at ${width}`)
      const bounds = await dialog.boundingBox()
      if (!bounds || bounds.y < 0 || bounds.y + bounds.height > 901) throw new Error(`${screen}: dialog escapes viewport at ${width}`)
      await page.screenshot({ path: `test-artifacts/witch-rules-${screen}-${width}.png`, fullPage: true })
    }
    await page.keyboard.press('Escape')
    if (await dialog.count()) throw new Error(`${screen}: Escape did not close the dialog`)
    results.push({ screen, rules: 'SIDE_KILL', widths: [1280, 375, 320], keyboardClose: true })
  }
  if (errors.length) throw new Error(errors.join('\n'))
  const report = { results, pageErrors: errors }
  fs.writeFileSync('test-artifacts/witch-rules-browser.json', JSON.stringify(report, null, 2))
  console.log(JSON.stringify(report))
} finally { await browser.close() }
