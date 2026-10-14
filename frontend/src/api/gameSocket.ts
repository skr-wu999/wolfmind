import { WS_URL, expireSession, sessionToken } from './config'

/** 服务端帧（websocket-protocol.md §4.1）。 */
export interface ServerFrame {
  v: number
  id: string
  type: string
  ts: number
  seq: number
  payload: Record<string, unknown>
}

/** 连接状态；界面据此显示"已连接 / 重连中 / 已断开"。 */
export type ConnectionState = 'idle' | 'connecting' | 'authenticating' | 'ready' | 'reconnecting' | 'closed'

interface RealtimeSocketOptions {
  /** 状态变化；用于界面提示。 */
  onState?: (state: ConnectionState) => void
  /** 需要重新登录（4401）；界面应清会话并跳登录页。 */
  onUnauthenticated?: () => void
}

export interface GameSocketOptions extends RealtimeSocketOptions {
  /** 每个对局一个订阅；连接建立后自动 SUBSCRIBE 并带上次的 lastSeq。 */
  gameId: string
  /** 断线前的进度；重连时用它做 RESUME。0 表示要全量。 */
  lastSeq: () => number
  /** 收到状态推进帧（GAME_EVENT / GAME_SNAPSHOT_SYNC）。 */
  onFrame: (frame: ServerFrame) => void
  /** 收到展示更新帧（AGENT_SPEECH_DELTA）。与 onFrame 分开：它不推进 seq。 */
  onSpeechDelta?: (frame: ServerFrame) => void
  /** 无法补齐缺口，需要全量同步（SYNC_REQUIRED）。 */
  onSyncRequired?: (reason: string) => void
}

/** 心跳与重连参数；与服务端下发的值一致（§3.3）。 */
export const HEARTBEAT_INTERVAL_MS = 15_000
export const IDLE_TIMEOUT_MS = 45_000

/** 被限速后的退避起点（websocket-protocol.md 表 116：4429 的退避起点改为 15s）。 */
export const RATE_LIMITED_BACKOFF_MS = 15_000

/** 重连退避（§10）：1s、2s、4s、8s、15s 封顶。4401 不重连。 */
export function backoffDelay(attempt: number): number {
  if (attempt <= 0) return 1_000
  return Math.min(1_000 * 2 ** (attempt - 1), 15_000)
}

/**
 * 实时通道的传输部分：建连、AUTH、心跳、重连退避、4401 / 4429 处置。
 *
 * 订阅什么、收到业务帧怎么办由子类决定（对局订阅见 {@link GameSocket}，等待房订阅见 {@link RoomSocket}）。
 */
abstract class RealtimeSocket {
  private socket: WebSocket | null = null
  private state: ConnectionState = 'idle'
  private heartbeat: ReturnType<typeof setInterval> | null = null
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null
  private reconnectAttempt = 0
  /** 曾被限速：退避起点提到 15 秒（§3.6）。 */
  private rateLimited = false
  private closedByUs = false
  private frameSeq = 0
  private authToken: string | null = null

  protected constructor(private readonly transport: RealtimeSocketOptions) {}

  get currentState(): ConnectionState {
    return this.state
  }

  /** 建立连接。重复调用是幂等的（已连接时不做事）。 */
  connect(): void {
    if (this.socket) return
    this.closedByUs = false
    this.setState('connecting')

    let socket: WebSocket
    try {
      socket = new WebSocket(WS_URL)
    } catch {
      // 构造失败（地址非法）：不重试，否则会无限打日志。
      this.setState('closed')
      return
    }
    this.socket = socket

    socket.onopen = () => {
      this.setState('authenticating')
      const token = sessionToken()
      this.authToken = token
      if (!token) {
        // 没有令牌就不发 AUTH：服务端会在 3 秒后按超时关闭，而我们主动断开更快也更明确。
        this.onUnauthenticated()
        return
      }
      this.send({ type: 'AUTH', payload: { token } })
    }

    socket.onmessage = (event) => this.handleMessage(event.data)

    socket.onclose = (event) => {
      this.clearHeartbeat()
      this.socket = null
      if (this.closedByUs) {
        this.setState('closed')
        return
      }
      // §3.6：4401 表示未认证——不重连（重连也会被同样拒绝），改为让界面重新登录。
      if (event.code === 4401) {
        this.setState('closed')
        this.onUnauthenticated()
        return
      }
      // §3.6：4429 是频率超限，**退避起点改为 15 秒**。立刻重连会被同样拒绝，
      // 而重连本身又会带来一批帧。
      if (event.code === 4429) this.rateLimited = true
      this.scheduleReconnect()
    }

    socket.onerror = () => {
      // onerror 之后浏览器保证会触发 onclose，因此这里不重复处理，避免双重重连。
    }
  }

  /** 主动断开；不再重连。 */
  close(): void {
    this.closedByUs = true
    this.clearHeartbeat()
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer)
      this.reconnectTimer = null
    }
    this.socket?.close()
    this.socket = null
    this.setState('closed')
  }

  /** 认证成功（含每次重连后的认证）；子类在这里发订阅帧。 */
  protected abstract onReady(): void

  /** 传输帧（AUTH_OK / PONG / ERROR）之外的帧。 */
  protected abstract onServerFrame(frame: ServerFrame): void

  /** 服务端 ERROR 帧；传输层已处理 AUTH_REQUIRED 与 RATE_LIMITED。 */
  protected onServerError(_code: string, _frame: ServerFrame): void {}

  protected send(frame: { type: string; payload: Record<string, unknown> }): void {
    const socket = this.socket
    if (!socket || socket.readyState !== WebSocket.OPEN) return
    this.frameSeq += 1
    // 客户端帧只有 id/type/payload 三个字段（§4.2）：不 v、不 ts、不 seq——
    // seq 是状态推进序号，只能由服务端从事件版本推导。
    socket.send(JSON.stringify({ id: `cli_${this.frameSeq}`, type: frame.type, payload: frame.payload }))
  }

  private handleMessage(raw: unknown): void {
    if (typeof raw !== 'string') return
    let frame: ServerFrame
    try {
      frame = JSON.parse(raw) as ServerFrame
    } catch {
      // 坏帧不当成致命错误：丢一帧比断开好，后续帧可能仍然可用。
      return
    }
    if (!frame || typeof frame.type !== 'string') return

    switch (frame.type) {
      case 'AUTH_OK':
        this.reconnectAttempt = 0
        // 认证成功说明限速已解除，退回正常退避。
        this.rateLimited = false
        this.setState('ready')
        this.startHeartbeat()
        this.onReady()
        return
      case 'PONG':
        return
      case 'ERROR': {
        const code = String(frame.payload.code ?? '')
        if (code === 'AUTH_REQUIRED') this.onUnauthenticated()
        // §3.6 / BP-3：收到 RATE_LIMITED 时**提高退避起点**并降低帧频率，
        // 而不是立即重发。不处理它的话客户端会继续按原节奏发帧，
        // 于是从"偶发超限警告"一路升到 4429 被关闭。
        if (code === 'RATE_LIMITED') this.rateLimited = true
        this.onServerError(code, frame)
        return
      }
      default:
        this.onServerFrame(frame)
    }
  }

  private startHeartbeat(): void {
    this.clearHeartbeat()
    this.heartbeat = setInterval(() => this.send({ type: 'PING', payload: {} }), HEARTBEAT_INTERVAL_MS)
  }

  private clearHeartbeat(): void {
    if (this.heartbeat) {
      clearInterval(this.heartbeat)
      this.heartbeat = null
    }
  }

  private scheduleReconnect(): void {
    this.setState('reconnecting')
    // §3.6：被限速过则退避起点提到 15 秒。
    const delay = this.rateLimited ? RATE_LIMITED_BACKOFF_MS : backoffDelay(this.reconnectAttempt)
    this.reconnectAttempt += 1
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null
      this.connect()
    }, delay)
  }

  private setState(state: ConnectionState): void {
    if (this.state === state) return
    this.state = state
    this.transport.onState?.(state)
  }

  private onUnauthenticated(): void {
    this.closedByUs = true
    this.clearHeartbeat()
    this.socket?.close(4401, 'unauthenticated')
    this.socket = null
    this.setState('closed')
    expireSession(this.authToken)
    this.transport.onUnauthenticated?.()
  }
}

/**
 * 对局实时通道客户端。
 *
 * **它只做传输**：建连、AUTH、订阅、心跳、重连与 RESUME 游标管理。
 * 帧语义（seq 规则、缺号、补发区间）由服务端负责，客户端在这里**只记录已收到的最大 seq**
 * 并把它用于下次 RESUME——不自己判断缺口、不自己补洞。客户端不是权威（AGENTS.md §9）。
 */
export class GameSocket extends RealtimeSocket {
  constructor(private readonly options: GameSocketOptions) {
    super(options)
  }

  /** 确认已处理到某个 seq；只影响服务端积压，不影响领域状态。 */
  ack(seq: number): void {
    this.send({ type: 'ACK', payload: { seq } })
  }

  /** 取消订阅（离开对局页时调用）。 */
  unsubscribe(): void {
    this.send({ type: 'UNSUBSCRIBE', payload: { gameId: this.options.gameId } })
  }

  resume(): void {
    this.send({ type: 'RESUME', payload: { gameId: this.options.gameId, lastSeq: this.options.lastSeq() } })
  }

  /**
   * 订阅或补齐。
   *
   * 首次订阅发 `SUBSCRIBE`（lastSeq=0 要全量），重连时发 `RESUME` 并带上已收到的最大 seq。
   * 两者都用 `lastSeq` 表达进度——服务端按同一套规则决定补发区间（§8.2）。
   */
  protected onReady(): void {
    const lastSeq = this.options.lastSeq()
    const type = lastSeq > 0 ? 'RESUME' : 'SUBSCRIBE'
    this.send({ type, payload: { gameId: this.options.gameId, lastSeq } })
  }

  protected onServerFrame(frame: ServerFrame): void {
    switch (frame.type) {
      case 'SUBSCRIBED':
      case 'GAME_EVENT':
      case 'GAME_SNAPSHOT_SYNC':
        this.options.onFrame(frame)
        return
      case 'AGENT_SPEECH_DELTA':
        this.options.onSpeechDelta?.(frame)
        return
      case 'SYNC_REQUIRED':
        this.options.onSyncRequired?.(String(frame.payload.reason ?? 'UNKNOWN'))
        return
      default:
        // 目录外的帧类型（§12 的兼容规则要求客户端忽略未知类型而不是报错）。
        return
    }
  }
}

export interface RoomSocketOptions extends RealtimeSocketOptions {
  roomId: string
  /** 比已应用版本新的房间快照（websocket-protocol.md §5.2 RS-1）；`room` 与 `GET /rooms/{roomId}` 同形。 */
  onSnapshot: (room: Record<string, unknown>, version: number) => void
  /** 房间不存在（`ROOM_NOT_FOUND`）。 */
  onRoomNotFound?: () => void
}

/**
 * 等待房实时通道：`ROOM_SUBSCRIBE` 后接收 `ROOM_SNAPSHOT`。
 *
 * 快照是完整状态，断线期间的变化不补发：重连认证后重新订阅，应答的快照即最新状态（RS-4）。
 */
export class RoomSocket extends RealtimeSocket {
  private appliedVersion = 0

  constructor(private readonly options: RoomSocketOptions) {
    super(options)
  }

  /** 发 `ROOM_UNSUBSCRIBE` 后断开；离开等待页时调用。 */
  override close(): void {
    this.send({ type: 'ROOM_UNSUBSCRIBE', payload: { roomId: this.options.roomId } })
    super.close()
  }

  protected onReady(): void {
    this.send({ type: 'ROOM_SUBSCRIBE', payload: { roomId: this.options.roomId } })
  }

  protected onServerFrame(frame: ServerFrame): void {
    if (frame.type !== 'ROOM_SNAPSHOT' || frame.payload.roomId !== this.options.roomId) return
    const version = Number(frame.payload.version)
    const room = frame.payload.room
    if (!Number.isFinite(version) || version <= this.appliedVersion || !room || typeof room !== 'object') return
    this.appliedVersion = version
    this.options.onSnapshot(room as Record<string, unknown>, version)
  }

  protected override onServerError(code: string): void {
    if (code === 'ROOM_NOT_FOUND') this.options.onRoomNotFound?.()
  }
}
