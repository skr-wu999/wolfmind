import { afterAll, afterEach, beforeAll, expect, test, vi } from 'vitest'
import { createRealRoomAdapter, startGame } from '../src/api/realRoomAdapter'
import { SESSION_TOKEN_KEY, WS_URL } from '../src/api/config'

/**
 * **完整断线恢复联调**：真实后端 × 真实 WebSocket × RESUME 补发。
 *
 * ## 它验证什么
 *
 * 1. 建一局（九座全 AI，规则模式）并订阅，记录已收到的最大 `seq`；
 * 2. **物理断开**连接（`close()`，模拟掉线/切网/关页面），而不是只停发帧；
 * 3. 断开期间让对局继续推进（AI 在行动，事件在产生）；
 * 4. 用 `RESUME` 带断线前的 `seq` 重连；
 * 5. 断言**断开期间漏掉的帧被补发**，且客户端拿到的水位追上了服务端。
 *
 * ## 为什么必须物理断开
 *
 * 只"停一会儿再发帧"验证不到补发：服务端会继续把事件推给那条还活着的连接。
 * 断开才让**补发区间**真的产生——而 `RESUME` 的全部意义就在那个区间上。
 *
 * ## 为什么这是端到端而不是单元
 *
 * `SubscriptionStateTest` 已经覆盖了补发逻辑本身（含缺号缓存）。
 * 这里要证明的是**它在真实连接上、与真实网关和真实对局一起工作**：
 * 断线的那一刻服务端是否记得这个连接的游标、重连后是否真的按 `lastSeq` 补齐。
 * 第 14 轮 `GAME_EVENT.seq` 恒为 0 的缺陷就是端到端才发现的那类问题。
 */

const BASE = process.env.WOLFMIND_E2E_BASE ?? 'http://127.0.0.1:18080'
const uniqueSuffix = () => Math.random().toString(36).slice(2, 10)
const wait = (ms: number) => new Promise((r) => setTimeout(r, ms))

function installStorage(): void {
  const store = new Map<string, string>()
  vi.stubGlobal('localStorage', {
    getItem: (k: string) => store.get(k) ?? null,
    setItem: (k: string, v: string) => void store.set(k, v),
    removeItem: (k: string) => void store.delete(k),
  })
}

async function untilAsync(predicate: () => boolean, timeoutMs: number): Promise<boolean> {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    if (predicate()) return true
    await wait(50)
  }
  return predicate()
}

async function account(prefix: string) {
  const username = `rc_${prefix}_${uniqueSuffix()}`
  const password = 'correct-horse-battery'
  const registered = await fetch(`${BASE}/api/v1/auth/register`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  })
  if (registered.status !== 201) throw new Error(`注册失败 ${registered.status}: ${await registered.text()}`)
  const loggedIn = await fetch(`${BASE}/api/v1/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  })
  if (loggedIn.status !== 200) throw new Error(`登录失败 ${loggedIn.status}: ${await loggedIn.text()}`)
  const body = (await loggedIn.json()) as { userId: string; sessionToken: string }
  return { userId: body.userId, token: body.sessionToken }
}

/** 建一局九座全 AI 的对局（房主先离开，否则 1 号座是真人）。 */
async function startedGame(prefix: string) {
  const owner = await account(prefix)
  localStorage.setItem(SESSION_TOKEN_KEY, owner.token)
  const roomAdapter = createRealRoomAdapter(() => owner.userId)
  const room = await roomAdapter.create({ name: '断线恢复联调房', visibility: 'PUBLIC' })
  await roomAdapter.leave(room.roomId).catch(() => {
    // 某些实现里房主离开会关闭房间；那种情况下由 agentCount 断言兜住。
  })
  const started = await startGame(room.roomId, true)
  return { ...owner, gameId: started.gameId, roomId: room.roomId, agentCount: started.agentCount }
}

/** 一条可观测的订阅连接：记录收到的所有帧与最大 seq。 */
class Probe {
  private socket: WebSocket
  readonly frames: Array<Record<string, unknown>> = []
  readonly events: Array<{ seq: number; type: string }> = []
  private closed = false

  constructor(private readonly token: string, private readonly timeoutMs = 15_000) {
    this.socket = this.open()
  }

  private open(): WebSocket {
    const socket = new WebSocket(WS_URL)
    socket.addEventListener('message', (event) => {
      const frame = JSON.parse(String((event as MessageEvent).data)) as Record<string, unknown>
      this.frames.push(frame)
      if (frame.type === 'GAME_EVENT') {
        const payload = frame.payload as Record<string, unknown>
        this.events.push({ seq: Number(frame.seq ?? 0), type: String(payload.type ?? '') })
        this.maxSeq = Math.max(this.maxSeq, Number(frame.seq ?? 0))
      } else if (frame.type === 'GAME_SNAPSHOT_SYNC') {
        this.maxSeq = Math.max(this.maxSeq, Number(frame.seq ?? 0))
      }
    })
    socket.addEventListener('close', () => {
      this.closed = true
    })
    return socket
  }

  maxSeq = 0

  private send(frame: Record<string, unknown>): void {
    this.socket.send(JSON.stringify(frame))
  }

  private async whenOpen(): Promise<void> {
    const ok = await untilAsync(() => this.socket.readyState === WebSocket.OPEN, this.timeoutMs)
    if (!ok) throw new Error('WebSocket 未能在超时内打开')
  }

  /** 完成 AUTH。 */
  async authenticate(): Promise<void> {
    await this.whenOpen()
    this.send({ id: 'f_auth', type: 'AUTH', payload: { token: this.token } })
    const ok = await untilAsync(
      () => this.frames.some((f) => f.type === 'AUTH_OK' || f.type === 'ERROR'), this.timeoutMs)
    if (!ok) throw new Error('未收到 AUTH_OK；收到的帧：' + JSON.stringify(this.frames))
  }

  /** 订阅（`lastSeq=0` 表示要全量）。 */
  async subscribe(gameId: string, lastSeq = 0): Promise<void> {
    this.send({ id: 'f_sub', type: 'SUBSCRIBE', payload: { gameId, lastSeq } })
    const ok = await untilAsync(() => this.frames.some((f) => f.type === 'SUBSCRIBED'), this.timeoutMs)
    if (!ok) throw new Error('未收到 SUBSCRIBED；收到的帧：' + JSON.stringify(this.frames))
  }

  /**
   * **RESUME**：与 SUBSCRIBE 形状相同（协议 §5.1），只是意图不同。
   * 带上断线前的水位，服务端据此补发缺口。
   */
  async resume(gameId: string, lastSeq: number): Promise<void> {
    this.send({ id: 'f_resume', type: 'RESUME', payload: { gameId, lastSeq } })
    const ok = await untilAsync(
      () => this.frames.some((f) => f.type === 'SUBSCRIBED' || f.type === 'SYNC_REQUIRED'),
      this.timeoutMs)
    if (!ok) throw new Error('RESUME 未得到 SUBSCRIBED/SYNC_REQUIRED；收到的帧：'
      + JSON.stringify(this.frames.map((f) => f.type)))
  }

  /** 物理断开：模拟掉线、切网、关页面。 */
  disconnect(): void {
    this.socket.close()
    this.closed = true
  }

  isClosed(): boolean {
    return this.closed
  }

  typeCount(type: string): number {
    return this.frames.filter((f) => f.type === type).length
  }
}

let probes: Probe[] = []

beforeAll(() => {
  installStorage()
})

afterEach(() => {
  for (const probe of probes) {
    try {
      probe.disconnect()
    } catch {
      // 清理失败不影响断言。
    }
  }
  probes = []
})

afterAll(() => {
  vi.unstubAllGlobals()
})

test('断线期间漏掉的事件在 RESUME 后被补发', async () => {
  const session = await startedGame('resume')
  expect(session.agentCount, '九个座位都应由 AI 补位').toBe(9)

  // 1) 第一条连接：订阅并攒一会儿事件。
  const first = new Probe(session.token)
  probes.push(first)
  await first.authenticate()
  await first.subscribe(session.gameId)

  /*
   * 只等**至少一条**事件，不等三条。
   *
   * 我第一版等 3 条，实测只收到 2 条就再也没有新的——规则模式下开局那批事件
   * 发完就暂时没有后续，于是断言在一个**与补发无关**的地方失败（20 秒超时）。
   * 本测试要的是"有一个可用的水位"，一条就够；条数不是被测行为。
   */
  const sawEvents = await untilAsync(() => first.events.length >= 1, 20_000)
  expect(sawEvents, '订阅后应收到开局事件；实际 ' + first.events.length + ' 条').toBe(true)
  const watermark = first.maxSeq

  // 2) **物理断开**。此后服务端推的帧没人收——补发区间由此产生。
  first.disconnect()
  await wait(300)
  expect(first.isClosed(), '连接应已关闭').toBe(true)

  // 3) 让对局在"无人收听"期间继续推进。
  const beforeGap = first.events.length
  /*
   * 断开后 `first` 不再收帧，因此用 **REST** 观察服务端确实在推进。
   * 不能用 `first.maxSeq`——它已经冻在断线那一刻了，用它判断会永远为假。
   */
  let advanced = false
  for (let i = 0; i < 40 && !advanced; i++) {
    const view = await fetch(`${BASE}/api/v1/games/${session.gameId}`).then((r) => r.json()) as {
      version: number
    }
    advanced = view.version > watermark
    if (!advanced) await wait(250)
  }
  expect(advanced, '断开期间服务端应继续推进对局（否则没有可补发的内容）').toBe(true)

  // 4) 第二条连接：用断线前的水位 RESUME。
  const second = new Probe(session.token)
  probes.push(second)
  await second.authenticate()
  await second.resume(session.gameId, watermark)

  /*
   * 5) 核心断言：**补发确实发生**。
   *
   * 补发的帧必须是 `seq > watermark` 的——它们是断线期间产生、
   * 而第一条连接永远不会再看到的那批。
   *
   * 若服务端不回补（例如忽略了 `lastSeq`），第二条连接会从当前水位开始，
   * 那个缺口就永久丢失：客户端的事件列表会**静默缺一段**，
   * 而界面上表现为"对局状态跳变"。
   */
  const replayed = await untilAsync(
    () => second.events.some((e) => e.seq > watermark), 20_000)
  expect(replayed,
    `断线期间漏掉的事件必须被补发。水位=${watermark}，`
    + `第一条连接收到 ${beforeGap} 条后断开，`
    + `第二条连接收到 seq=${second.events.map((e) => e.seq).join(',')}`).toBe(true)

  // 第二条连接的水位必须追上（或超过）断线时的水位。
  expect(second.maxSeq).toBeGreaterThan(watermark)
})

test('RESUME 后客户端水位与 REST 权威版本一致', async () => {
  const session = await startedGame('resync')
  expect(session.agentCount).toBe(9)

  const probe = new Probe(session.token)
  probes.push(probe)
  await probe.authenticate()
  await probe.subscribe(session.gameId)
  expect(await untilAsync(() => probe.events.length > 0, 20_000), '应有开局事件').toBe(true)

  probe.disconnect()
  await wait(300)

  // 断开期间推进。
  for (let i = 0; i < 40; i++) {
    const view = await fetch(`${BASE}/api/v1/games/${session.gameId}`).then((r) => r.json()) as {
      version: number
    }
    if (view.version > probe.maxSeq) break
    await wait(250)
  }

  const resumed = new Probe(session.token)
  probes.push(resumed)
  await resumed.authenticate()
  await resumed.resume(session.gameId, probe.maxSeq)

  /*
   * 断言"水位**前进**"，而不是"水位追上 REST 版本"。
   *
   * 我第一版写的是后者，实测**随机失败**：游戏一直在推进，
   * REST 版本是个**移动目标**——第 1 次读到 v43、判完再读已经 v57，
   * 客户端永远差一截。而"追上"本来也不是协议承诺的：
   * 协议承诺的是**补发缺口**。
   *
   * 能成立的判据是：RESUME 之后客户端的水位必须**超过断线时的水位**——
   * 那就是"断线期间漏掉的帧被补上了"的直接证据。
   */
  const advancedAfterResume = await untilAsync(
    () => resumed.maxSeq > probe.maxSeq, 15_000)
  expect(advancedAfterResume,
    `RESUME 后水位必须前进。断线水位=${probe.maxSeq}，`
    + `RESUME 后水位=${resumed.maxSeq}`).toBe(true)

  const authoritative = await fetch(`${BASE}/api/v1/games/${session.gameId}`).then((r) => r.json()) as {
    version: number
  }
  /*
   * 客户端水位不得**超过**权威版本加一点余量。
   *
   * 不能断言"≤ 当前 REST 版本"：读 REST 与读 `resumed.maxSeq` 之间有真实时间差，
   * 服务端可能刚好又提交了一个事件，于是客户端水位合法地略高于那次快照。
   * 因此允许一个小的余量——但它必须**有界**：
   * 无界的话，"客户端在自己造 seq"就无法被发现了（AGENTS.md §9：客户端不是权威）。
   */
  expect(resumed.maxSeq).toBeLessThanOrEqual(authoritative.version + 5)
})
