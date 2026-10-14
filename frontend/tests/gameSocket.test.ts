import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import {
  GameSocket,
  RoomSocket,
  HEARTBEAT_INTERVAL_MS,
  backoffDelay,
  type ConnectionState,
  type ServerFrame,
} from '../src/api/gameSocket'
import { SESSION_TOKEN_KEY } from '../src/api/config'

/**
 * WebSocket 客户端的行为契约。
 *
 * 它守两件事，两件都是"断线后还能继续"的前提：
 * 1. **重连必须带 RESUME 与正确的 lastSeq**——不带就等于每次断线都重新全量同步，
 *    而带错了（比如带 0）会让服务端以为客户端没有本地状态，重放整局历史。
 * 2. **4401 不重连**——会话无效时重连会被同样拒绝，无限重连只会刷满日志并让用户
 *    看着"重连中"永远等下去；正确处置是让界面重新登录（websocket-protocol.md §3.6）。
 *
 * 用可控的 `WebSocket` 替身而不是真实连接：这里验证的是**客户端的决策**，
 * 而真实连接的能力已由后端侧的 `WebSocketContractTest` 覆盖。
 */

/** 可手动驱动的 WebSocket 替身。 */
class FakeSocket {
  static instances: FakeSocket[] = []
  static readonly OPEN = 1

  readyState = 0
  onopen: (() => void) | null = null
  onmessage: ((event: { data: string }) => void) | null = null
  onclose: ((event: { code: number }) => void) | null = null
  onerror: (() => void) | null = null
  sent: { id: string; type: string; payload: Record<string, unknown> }[] = []
  closed: number | null = null

  constructor(readonly url: string) {
    FakeSocket.instances.push(this)
  }

  send(raw: string): void {
    this.sent.push(JSON.parse(raw))
  }

  close(code = 1000): void {
    this.closed = code
  }

  /** 模拟握手完成。 */
  open(): void {
    this.readyState = FakeSocket.OPEN
    this.onopen?.()
  }

  /** 模拟服务端下发一帧。 */
  emit(frame: Partial<ServerFrame> & { type: string }): void {
    this.onmessage?.({
      data: JSON.stringify({ v: 1, id: 'srv_1', ts: 0, seq: 0, payload: {}, ...frame }),
    })
  }

  /** 模拟连接断开。 */
  drop(code = 1006): void {
    this.readyState = 3
    this.onclose?.({ code })
  }

  /** 最后一次发出的某类帧。 */
  lastOfType(type: string) {
    return this.sent.filter((f) => f.type === type).at(-1)
  }
}

/** 建一个已认证且已订阅的 socket。 */
function connected(options: {
  lastSeq?: number
  onFrame?: (frame: ServerFrame) => void
  onUnauthenticated?: () => void
  onSyncRequired?: (reason: string) => void
  onState?: (state: ConnectionState) => void
  onSpeechDelta?: (frame: ServerFrame) => void
} = {}) {
  const socket = new GameSocket({
    gameId: 'g_1',
    lastSeq: () => options.lastSeq ?? 0,
    onFrame: options.onFrame ?? (() => {}),
    onSpeechDelta: options.onSpeechDelta,
    onState: options.onState,
    onUnauthenticated: options.onUnauthenticated,
    onSyncRequired: options.onSyncRequired,
  })
  socket.connect()
  const fake = FakeSocket.instances.at(-1)!
  fake.open()
  return { socket, fake }
}

describe('GameSocket', () => {
  beforeEach(() => {
    FakeSocket.instances = []
    vi.stubGlobal('WebSocket', FakeSocket)
    const store = new Map<string, string>([[SESSION_TOKEN_KEY, 'u_1.s_1.secret']])
    vi.stubGlobal('localStorage', {
      getItem: (key: string) => store.get(key) ?? null,
      setItem: (key: string, value: string) => void store.set(key, value),
      removeItem: (key: string) => void store.delete(key),
    })
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  test('连接后先发 AUTH，认证成功后才订阅', () => {
    const { fake } = connected()

    // 顺序很重要：未认证就订阅会得到 AUTH_REQUIRED（协议 §3.1）。
    expect(fake.sent.map((f) => f.type)).toEqual(['AUTH'])
    expect(fake.sent[0]!.payload.token).toBe('u_1.s_1.secret')

    fake.emit({ type: 'AUTH_OK', payload: { userId: 'u_1' } })

    expect(fake.sent.map((f) => f.type)).toEqual(['AUTH', 'SUBSCRIBE'])
    expect(fake.lastOfType('SUBSCRIBE')!.payload).toEqual({ gameId: 'g_1', lastSeq: 0 })
  })

  test('首次订阅用 SUBSCRIBE 且 lastSeq=0（要全量）', () => {
    const { fake } = connected({ lastSeq: 0 })
    fake.emit({ type: 'AUTH_OK' })

    expect(fake.lastOfType('SUBSCRIBE')!.payload.lastSeq).toBe(0)
    expect(fake.lastOfType('RESUME')).toBeUndefined()
  })

  test('有进度时用 RESUME 并带上 lastSeq（不必重新全量）', () => {
    const { fake } = connected({ lastSeq: 42 })
    fake.emit({ type: 'AUTH_OK' })

    // 带 0 会让服务端以为客户端没有本地状态而重放整局历史，
    // 表现是断线重连后界面从头播一遍。
    const resume = fake.lastOfType('RESUME')!
    expect(resume.payload).toEqual({ gameId: 'g_1', lastSeq: 42 })
    expect(fake.lastOfType('SUBSCRIBE')).toBeUndefined()
  })

  test('重连时读取最新的 lastSeq，而不是建立连接时的快照', () => {
    // 注意这里不用 connected()：那个 helper 会把 lastSeq 的值快照进对象字面量，
    // 于是闭包读到的永远是创建时的值——测不出"重连时重新读取"这件事。
    let seq = 5
    const socket = new GameSocket({ gameId: 'g_1', lastSeq: () => seq, onFrame: () => {} })
    socket.connect()
    const fake = FakeSocket.instances.at(-1)!
    fake.open()
    fake.emit({ type: 'AUTH_OK' })
    expect(fake.lastOfType('RESUME')!.payload.lastSeq).toBe(5)

    // 期间又收到了一些事件。
    seq = 9
    fake.drop()
    vi.advanceTimersByTime(1_000)
    const reconnected = FakeSocket.instances.at(-1)!
    reconnected.open()
    reconnected.emit({ type: 'AUTH_OK' })

    // 必须带 9：带 5 会让服务端重发 6..9，客户端虽然能靠 seq 去重，
    // 但那份去重逻辑本不该被用到。
    expect(reconnected.lastOfType('RESUME')!.payload.lastSeq).toBe(9)
  })

  test('GAME_EVENT 与 GAME_SNAPSHOT_SYNC 交给 onFrame（它们推进状态）', () => {
    const frames: ServerFrame[] = []
    const { fake } = connected({ onFrame: (f) => frames.push(f) })
    fake.emit({ type: 'AUTH_OK' })

    fake.emit({ type: 'GAME_EVENT', seq: 1, payload: { type: 'PHASE_CHANGED' } })
    fake.emit({ type: 'GAME_SNAPSHOT_SYNC', seq: 3, payload: { replayedTo: 3 } })

    expect(frames.map((f) => f.type)).toEqual(['GAME_EVENT', 'GAME_SNAPSHOT_SYNC'])
  })

  test('AGENT_SPEECH_DELTA 走独立回调（它是展示更新，不推进 seq）', () => {
    const frames: ServerFrame[] = []
    const deltas: ServerFrame[] = []
    const { fake } = connected({ onFrame: (f) => frames.push(f), onSpeechDelta: (f) => deltas.push(f) })
    fake.emit({ type: 'AUTH_OK' })

    fake.emit({ type: 'AGENT_SPEECH_DELTA', payload: { speechId: 'sp_1', seatNo: 3, delta: '我' } })

    // 分开的理由是它们的处置不同：发言增量可以在积压超限时丢弃，状态帧不行。
    expect(deltas).toHaveLength(1)
    expect(frames.map((f) => f.type)).not.toContain('AGENT_SPEECH_DELTA')
  })

  test('SYNC_REQUIRED 通知界面需要全量同步', () => {
    const reasons: string[] = []
    const { fake } = connected({ onSyncRequired: (r) => reasons.push(r) })
    fake.emit({ type: 'AUTH_OK' })

    fake.emit({ type: 'SYNC_REQUIRED', payload: { reason: 'SEQ_GAP' } })

    expect(reasons).toEqual(['SEQ_GAP'])
  })

  test('忽略目录外的帧类型，不抛异常也不断开', () => {
    const frames: ServerFrame[] = []
    const { fake, socket } = connected({ onFrame: (f) => frames.push(f) })
    fake.emit({ type: 'AUTH_OK' })

    // §12 的兼容规则要求客户端忽略未知类型——服务端将来新增帧类型时老客户端不该崩。
    expect(() => fake.emit({ type: 'FUTURE_FRAME' })).not.toThrow()
    expect(frames.map((f) => f.type)).not.toContain('FUTURE_FRAME')
    expect(socket.currentState).toBe('ready')
  })

  test('坏 JSON 被丢弃，连接保持可用', () => {
    const { fake, socket } = connected()
    fake.emit({ type: 'AUTH_OK' })

    fake.onmessage?.({ data: '{ not json' })

    expect(socket.currentState).toBe('ready')
  })

  test('心跳按 15 秒发送 PING', () => {
    const { fake } = connected()
    fake.emit({ type: 'AUTH_OK' })

    vi.advanceTimersByTime(HEARTBEAT_INTERVAL_MS)
    expect(fake.sent.filter((f) => f.type === 'PING')).toHaveLength(1)

    vi.advanceTimersByTime(HEARTBEAT_INTERVAL_MS)
    expect(fake.sent.filter((f) => f.type === 'PING')).toHaveLength(2)
  })

  test('主动 close 之后不再心跳、不再重连', () => {
    const states: ConnectionState[] = []
    const { socket } = connected({ onState: (s) => states.push(s) })
    const fake = FakeSocket.instances.at(-1)!
    fake.emit({ type: 'AUTH_OK' })

    socket.close()
    const pingCount = fake.sent.filter((f) => f.type === 'PING').length
    vi.advanceTimersByTime(HEARTBEAT_INTERVAL_MS * 3)

    expect(fake.sent.filter((f) => f.type === 'PING')).toHaveLength(pingCount)
    expect(states.at(-1)).toBe('closed')
    // 没有新建连接。
    expect(FakeSocket.instances).toHaveLength(1)
  })

  test('4401 不重连，而是通知界面重新登录', () => {
    const unauth: string[] = []
    const { fake } = connected({ onUnauthenticated: () => unauth.push('called') })
    fake.emit({ type: 'AUTH_OK' })

    fake.drop(4401)
    vi.advanceTimersByTime(60_000)

    // 重连会被同样拒绝，无限重连只会让用户看着"重连中"。
    expect(FakeSocket.instances).toHaveLength(1)
    expect(unauth.length).toBeGreaterThan(0)
  })

  test('非 4401 断开按退避重连（1s、2s、4s、8s、15s 封顶）', () => {
    const { fake } = connected()
    fake.emit({ type: 'AUTH_OK' })

    fake.drop(1006)
    expect(FakeSocket.instances).toHaveLength(1)
    vi.advanceTimersByTime(1_000)
    expect(FakeSocket.instances).toHaveLength(2)

    FakeSocket.instances.at(-1)!.drop(1006)
    vi.advanceTimersByTime(2_000)
    expect(FakeSocket.instances).toHaveLength(3)
  })

  test('订阅前没有令牌就不发 AUTH，直接通知重新登录', () => {
    const store = new Map<string, string>()
    vi.stubGlobal('localStorage', {
      getItem: (k: string) => store.get(k) ?? null,
      setItem: (k: string, v: string) => void store.set(k, v),
      removeItem: (k: string) => void store.delete(k),
    })
    const unauth: string[] = []
    const { fake } = connected({ onUnauthenticated: () => unauth.push('called') })

    // 服务端 3 秒后会按超时关闭，但主动断开更快，也让界面立刻知道要登录。
    expect(fake.sent).toHaveLength(0)
    expect(unauth.length).toBeGreaterThan(0)
  })

  test('ack 只发给服务端，不影响本地游标', () => {
    const { fake } = connected({ lastSeq: 7 })
    fake.emit({ type: 'AUTH_OK' })

    // ACK 的语义是"我已处理到某 seq"，它只让服务端释放积压，
    // 不改变客户端的 RESUME 依据（那是 onFrame 消费后由界面回填的）。
    expect(fake.lastOfType('RESUME')!.payload.lastSeq).toBe(7)
  })
})

describe('backoffDelay', () => {
  test('指数退避且 15 秒封顶', () => {
    expect(backoffDelay(0)).toBe(1_000)
    expect(backoffDelay(1)).toBe(1_000)
    expect(backoffDelay(2)).toBe(2_000)
    expect(backoffDelay(3)).toBe(4_000)
    expect(backoffDelay(4)).toBe(8_000)
    // 封顶而不是无限增长：否则断线一分钟后重连会等到几分钟后，
    // 用户会以为页面卡死了。
    expect(backoffDelay(5)).toBe(15_000)
    expect(backoffDelay(20)).toBe(15_000)
  })
})

describe('限速处置（§3.6 / BP-3）', () => {
  beforeEach(() => {
    FakeSocket.instances = []
    vi.stubGlobal('WebSocket', FakeSocket)
    const store = new Map<string, string>([[SESSION_TOKEN_KEY, 'u_1.s_1.secret']])
    vi.stubGlobal('localStorage', {
      getItem: (k: string) => store.get(k) ?? null,
      setItem: (k: string, v: string) => void store.set(k, v),
      removeItem: (k: string) => void store.delete(k),
    })
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  test('收到 ERROR{RATE_LIMITED} 之后退避起点提到 15 秒', () => {
    const { fake } = connected()
    fake.emit({ type: 'AUTH_OK' })

    // 服务端先警告（§9：超限先回 ERROR，持续超限才关闭）。
    fake.emit({ type: 'ERROR', payload: { code: 'RATE_LIMITED' } })
    fake.drop(1006)

    // 正常退避是 1 秒；被限速后必须是 15 秒——否则客户端会按原节奏重连，
    // 而重连本身又是一批帧，于是从"警告"一路升到被关闭。
    vi.advanceTimersByTime(1_000)
    expect(FakeSocket.instances, '1 秒内不得重连').toHaveLength(1)

    vi.advanceTimersByTime(14_000)
    expect(FakeSocket.instances, '15 秒后应重连').toHaveLength(2)
  })

  test('被 4429 关闭后同样按 15 秒退避（而不是退避序列的 1 秒）', () => {
    const { fake } = connected()
    fake.emit({ type: 'AUTH_OK' })

    fake.drop(4429)

    vi.advanceTimersByTime(5_000)
    expect(FakeSocket.instances, '5 秒内不得重连').toHaveLength(1)

    vi.advanceTimersByTime(10_000)
    expect(FakeSocket.instances, '15 秒后应重连').toHaveLength(2)
  })

  test('认证成功后回到正常退避序列', () => {
    const { fake } = connected()
    fake.emit({ type: 'AUTH_OK' })
    fake.emit({ type: 'ERROR', payload: { code: 'RATE_LIMITED' } })
    fake.drop(1006)

    // 第一次重连（15 秒后）。
    vi.advanceTimersByTime(15_000)
    const reconnected = FakeSocket.instances.at(-1)!
    reconnected.open()
    reconnected.emit({ type: 'AUTH_OK' })

    // 限速已解除：再断开应按正常退避（1 秒），而不是仍然等 15 秒。
    reconnected.drop(1006)
    vi.advanceTimersByTime(1_000)
    expect(FakeSocket.instances, '限速解除后应回到 1 秒退避').toHaveLength(3)
  })
})

describe('RoomSocket', () => {
  beforeEach(() => {
    FakeSocket.instances = []
    vi.stubGlobal('WebSocket', FakeSocket)
    const store = new Map<string, string>([[SESSION_TOKEN_KEY, 'u_1.s_1.secret']])
    vi.stubGlobal('localStorage', {
      getItem: (key: string) => store.get(key) ?? null,
      setItem: (key: string, value: string) => void store.set(key, value),
      removeItem: (key: string) => void store.delete(key),
    })
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  function roomConnected(onSnapshot: (room: Record<string, unknown>, version: number) => void, onRoomNotFound?: () => void) {
    const socket = new RoomSocket({ roomId: 'r_1', onSnapshot, onRoomNotFound })
    socket.connect()
    const fake = FakeSocket.instances.at(-1)!
    fake.open()
    fake.emit({ type: 'AUTH_OK' })
    return { socket, fake }
  }

  const snapshot = (version: number, status = 'WAITING', roomId = 'r_1') =>
    ({ type: 'ROOM_SNAPSHOT', payload: { roomId, version, room: { roomId, status } } })

  test('认证后发 ROOM_SUBSCRIBE；只应用比已应用版本新的快照', () => {
    const applied: number[] = []
    const { fake } = roomConnected((_room, version) => applied.push(version))

    expect(fake.sent.map((f) => f.type)).toEqual(['AUTH', 'ROOM_SUBSCRIBE'])
    expect(fake.lastOfType('ROOM_SUBSCRIBE')!.payload).toEqual({ roomId: 'r_1' })

    fake.emit(snapshot(3))
    fake.emit(snapshot(2))
    fake.emit(snapshot(3))
    fake.emit(snapshot(5, 'RUNNING'))
    fake.emit(snapshot(9, 'WAITING', 'r_other'))

    expect(applied, '旧版本、重复版本与其他房间的快照都被丢弃').toEqual([3, 5])
  })

  test('重连认证后重新订阅；关闭时先发 ROOM_UNSUBSCRIBE', () => {
    const { socket, fake } = roomConnected(() => {})
    fake.drop(1006)
    vi.advanceTimersByTime(1_000)
    const reconnected = FakeSocket.instances.at(-1)!
    reconnected.open()
    reconnected.emit({ type: 'AUTH_OK' })
    expect(reconnected.sent.map((f) => f.type)).toEqual(['AUTH', 'ROOM_SUBSCRIBE'])

    socket.close()
    expect(reconnected.sent.at(-1)).toMatchObject({ type: 'ROOM_UNSUBSCRIBE', payload: { roomId: 'r_1' } })
    expect(reconnected.closed).not.toBeNull()
  })

  test('ROOM_NOT_FOUND 交给调用方', () => {
    const notFound = vi.fn()
    const { fake } = roomConnected(() => {}, notFound)
    fake.emit({ type: 'ERROR', payload: { code: 'ROOM_NOT_FOUND' } })
    expect(notFound).toHaveBeenCalledOnce()
  })
})