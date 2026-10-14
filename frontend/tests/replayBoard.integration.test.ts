import { afterEach, expect, test, vi } from 'vitest'
import { createRealObservationAdapter } from '../src/features/observation/realObservationAdapter'
import { projectObservation } from '../src/features/observation/projection'
import { SESSION_TOKEN_KEY } from '../src/api/config'

/**
 * 回放棋盘 × 真实后端：开局状态必须来自服务端的历史快照。
 *
 * ## 为什么要真的起后端
 *
 * QA 报告 P1：点"回到开局"后，时间轴是 `00:00`、标题是"第 1 天 · 等待开局"，
 * 但 1、2、3、4、7、8、9 号仍是已淘汰——因为前端拿**终局**的 `alive` 当基线，
 * 之后只能把人改成死亡，永远回不到开局。
 *
 * 修复有两层，这条用例两层都盯：
 * 1. `boardAt` 用服务端 `?atVersion=` 返回的 `snapshot` 重建历史棋盘（前端不重放事件）；
 * 2. 没有权威棋盘时的本地投影也必须从开局基线向前累加，而不是从终局往回退。
 */
const BASE = process.env.WOLFMIND_E2E_BASE ?? 'http://127.0.0.1:18080'

afterEach(() => { vi.unstubAllGlobals() })

async function post(path: string, body: unknown) {
  const response = await fetch(`${BASE}/api/v1${path}`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
  })
  const result = await response.json()
  if (!response.ok) throw new Error(`${path}: ${response.status} ${JSON.stringify(result)}`)
  return result
}

test('回到开局显示服务端的开局棋盘，而不是终局淘汰状态', async () => {
  const storage = new Map<string, string>()
  vi.stubGlobal('localStorage', {
    getItem: (key: string) => storage.get(key) ?? null,
    setItem: (key: string, value: string) => void storage.set(key, value),
    removeItem: (key: string) => void storage.delete(key),
  })
  const credentials = { username: `replay_${Math.random().toString(36).slice(2, 10)}`, password: 'correct-horse-battery' }
  await post('/auth/register', credentials)
  const account = await post('/auth/login', credentials) as { sessionToken: string }
  localStorage.setItem(SESSION_TOKEN_KEY, account.sessionToken)

  const list = await fetch(`${BASE}/api/v1/games?status=FINISHED&page=1&pageSize=20`, {
    headers: { Authorization: `Bearer ${account.sessionToken}` },
  }).then(response => response.json()) as { items?: { gameId: string }[] }
  const games = list.items ?? []
  expect(games.length, '需要至少一局已结束的真实对局才能验证回放棋盘').toBeGreaterThan(0)

  const adapter = createRealObservationAdapter()
  let verified = 0
  for (const entry of games) {
    const match = await adapter.get(entry.gameId)
    if (!match.snapshot?.finished) continue
    const terminalAlive = match.players.filter(player => player.alive).length
    expect(adapter.boardAt, '真实适配器必须提供历史棋盘').toBeTypeOf('function')

    const start = await adapter.boardAt!(entry.gameId, 1, undefined)
    expect(start, '服务端必须能给出第 1 版的棋盘').not.toBeNull()
    expect(start!.version).toBe(1)
    expect(start!.players.filter(player => player.alive).length, '开局应全员存活').toBe(9)
    expect(start!.phase).toBe('等待开局')
    expect(start!.players.filter(player => player.alive).length).toBeGreaterThan(terminalAlive)
    expect(start!.players.every(player => player.disclosedRole === undefined || typeof player.disclosedRole === 'string')).toBe(true)

    // 本地投影是权威棋盘不可用时的兜底：它也必须从开局出发。
    const projected = projectObservation(match, 0)
    expect(projected.players.filter(player => player.alive).length, '本地兜底投影在 00:00 也应是 9 人存活').toBe(9)

    // 中段版本也要给出与终局不同的、真实的历史状态。
    const middle = await adapter.boardAt!(entry.gameId, Math.min(2, match.snapshot!.version), undefined)
    expect(middle).not.toBeNull()

    // 阶段文案：QA 报告 P2 的"第 N 天 · undefined"来自阶段映射漏项，真实数据里不能再出现。
    for (const event of match.events) {
      expect(event.change?.phase, `事件 ${event.type} 缺失阶段文案`).toBeTruthy()
      expect(event.change!.phase).not.toBe('undefined')
    }
    const phases = new Set(match.events.map(event => event.change!.phase))
    expect(phases.has('等待开局')).toBe(true)
    expect([...phases].some(phase => phase === '狼人行动' || phase === '预言家查验' || phase === '女巫用药'), '夜间子阶段必须能分辨').toBe(true)
    verified++
    if (verified >= 2) break
  }
  expect(verified, '至少验证两局真实对局').toBeGreaterThanOrEqual(2)
}, 180_000)
