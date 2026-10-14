import { afterEach, expect, test, vi } from 'vitest'
import { createRealLabAdapter } from '../src/features/lab/realLabAdapter'
import { defaultPrompt } from '../src/features/lab/types'
import { SESSION_TOKEN_KEY } from '../src/api/config'
import type { AgentDraft, BatchOptions } from '../src/features/lab/types'

/**
 * 实验室 × 真实后端：目录 → 批次详情 → 页面用的进度。
 *
 * ## 为什么要真的起后端
 *
 * QA 报告 P1：离开实验室再进入（或刷新整页）后出现 `NaN%`、`已结束 undefined 局`、
 * `undefined / 1 局`。根因是配置目录里的 `batches[]` 是 `LabBatch` 记录，其 `completed()`
 * 是**方法**、不参与序列化——把它当完整进度响应就会算出 NaN。
 *
 * 这条用例覆盖修复后的真实路径：`catalog()` 先拿 `latestBatchId`，再按详情接口取一次，
 * 并按 `completed/total` 的形状校验后才交给界面。用 stub 无法证明"服务端两个接口的形状确实不同"，
 * 所以这里跑真实 REST；规则基线模式下不调用模型。
 */
const BASE = process.env.WOLFMIND_E2E_BASE ?? 'http://127.0.0.1:18080'
const wait = (ms: number) => new Promise(resolve => setTimeout(resolve, ms))

afterEach(() => { vi.unstubAllGlobals() })

async function post(path: string, body: unknown) {
  const response = await fetch(`${BASE}/api/v1${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  const result = await response.json()
  if (!response.ok) throw new Error(`${path}: ${response.status} ${JSON.stringify(result)}`)
  return result
}

test('重新进入实验室拿到的是真实进度，不是 NaN 或 undefined', async () => {
  const storage = new Map<string, string>()
  vi.stubGlobal('localStorage', {
    getItem: (key: string) => storage.get(key) ?? null,
    setItem: (key: string, value: string) => void storage.set(key, value),
    removeItem: (key: string) => void storage.delete(key),
  })
  const credentials = { username: `lab_${Math.random().toString(36).slice(2, 10)}`, password: 'correct-horse-battery' }
  await post('/auth/register', credentials)
  const account = await post('/auth/login', credentials) as { sessionToken: string }
  localStorage.setItem(SESSION_TOKEN_KEY, account.sessionToken)

  const adapter = createRealLabAdapter()
  const draft: AgentDraft = {
    name: '实时基线', role: 'WEREWOLF', personality: '理性分析', strategy: '证据优先',
    modelId: 'qa-live-baseline', temperature: 0.7, promptMode: 'default', prompt: defaultPrompt,
    memory: 'short', tools: { analysis: true, history: true, summary: true },
  }
  const config = await adapter.save(draft)
  const options: BatchOptions = { total: 1, seedStart: 1, seedEnd: 1, mode: 'standard', speed: 1, concurrency: 1 }
  const started = await adapter.start([config.id], options)

  const deadline = Date.now() + 120_000
  let batch = started
  while (batch.status === 'RUNNING' && Date.now() < deadline) {
    await wait(1000)
    batch = await adapter.refresh(started.id)
  }
  expect(batch.status, '规则基线批次应能在时限内跑完').toBe('COMPLETED')
  expect(batch.completed).toBe(1)
  expect(batch.total).toBe(1)

  // 新适配器实例 + 重新拉目录 = "离开实验室再进入/整页刷新"。
  const reloaded = await createRealLabAdapter().catalog()
  expect(reloaded.batch, '重载后必须能拿回最近批次').not.toBeNull()
  const completed = reloaded.batch!.completed, total = reloaded.batch!.total
  expect(Number.isInteger(completed), `completed 必须是整数，实际 ${completed}`).toBe(true)
  expect(Number.isInteger(total), `total 必须是整数，实际 ${total}`).toBe(true)
  expect(completed).toBeGreaterThanOrEqual(0)
  expect(completed).toBeLessThanOrEqual(total)
  expect(Number.isFinite(completed / total * 100), '进度百分比不能是 NaN').toBe(true)
  expect(JSON.stringify(reloaded.batch), '界面数据里不能出现 undefined 之外的演示字样').not.toContain('demo')

  const game = reloaded.batch!.games.find(entry => entry.gameId)
  expect(game?.gameId, '批次必须给出真实对局 id').toMatch(/^g_/)
  expect(reloaded.batch!.games.every(entry => !entry.gameId || !entry.gameId.includes('demo'))).toBe(true)
}, 180_000)
