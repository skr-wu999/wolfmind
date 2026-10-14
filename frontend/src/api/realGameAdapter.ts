import { ApiError, request, newIdempotencyKey } from './apiClient'
import { sessionToken } from './config'
import { toGamePresentation } from './mapGame'
import { GameSocket, type ServerFrame, type ConnectionState } from './gameSocket'
import { applyView, emptyState, markResyncRequired, reduceFrame, type GameStreamState, type AppliedEvent } from './gameStream'
import type { GameView } from './restTypes'
import type { GameAdapter, GameActionType, GameCommand, GamePresentation, GameConnection } from '@/features/game/types'

/**
 * 真实对局适配器：`GameAdapter` 的 REST + WebSocket 实现。
 *
 * 三条数据通路各司其职，**不互相替代**：
 * - `get()` / 刷新走 **REST**，拿到的是权威视图（脱敏已由服务端完成）；
 * - `subscribe()` 走 **WebSocket**，只负责"什么时候该刷新"；
 * - `submit()` 走 **REST**，因为写操作需要幂等键与明确的错误码，而 WS 通道没有这两样。
 *
 * **为什么事件不直接变成视图**：见 `gameStream.ts` 的说明——前端重建对局状态等于把规则判定
 * 搬到客户端。这里让 `gameStream` 只回答"该不该刷新"，视图永远来自 REST。
 */

/** 收到事件后合并刷新的等待时间（毫秒）。 */
export const REFRESH_DEBOUNCE_MS = 120

/** `GAME_BUSY` 的退避起点与最多重试次数（api-spec.md §1：建议 200 ms，最多 3 次）。 */
export const GAME_BUSY_BACKOFF_MS = 200
export const GAME_BUSY_RETRIES = 3

export interface RealGameAdapterOptions {
  /** 我的座位号（观战者返回 null）。用于算 `me`。 */
  mySeatNo?: () => number | null
  /** 连接状态变化；界面据此显示"重连中"。 */
  onConnectionState?: (state: ConnectionState) => void
  /** 需要重新登录（4401）。 */
  onUnauthenticated?: () => void
  /** 测试可注入；默认用全局 fetch。 */
  refreshDebounceMs?: number
}

/** 创建真实对局适配器。 */
export function createRealGameAdapter(options: RealGameAdapterOptions = {}): GameAdapter {
  const mySeatNo = options.mySeatNo ?? (() => null)
  const debounceMs = options.refreshDebounceMs ?? REFRESH_DEBOUNCE_MS

  /** 每个 gameId 的流状态；同一适配器可能服务对局页与观战页。 */
  const streams = new Map<string, GameStreamState>()
  /** 每个 gameId 的 socket 与订阅者。 */
  const sockets = new Map<string, GameSocket>()
  const listeners = new Map<string, Set<(view: GamePresentation) => void>>()
  /** 合并刷新的定时器；同一 gameId 上最多一个。 */
  const refreshTimers = new Map<string, ReturnType<typeof setTimeout>>()
  const connectionListeners = new Map<string, Set<(state: GameConnection) => void>>()
  const subscribed = new Set<string>()
  const syncTargets = new Map<string, number>()
  const history = new Map<string, { version: number; events: AppliedEvent[] }>()
  const clockOffsets = new Map<string, number>()
  const contexts = new Map<string, { token: string | null }>()

  function contextFor(gameId: string) {
    const token = sessionToken()
    const previous = contexts.get(gameId)
    if (previous && previous.token === token) return previous
    const context = { token }
    contexts.set(gameId, context)
    streams.delete(gameId)
    views.delete(gameId)
    history.delete(gameId)
    speechBuffers.delete(gameId)
    clockOffsets.delete(gameId)
    sockets.get(gameId)?.close()
    sockets.delete(gameId)
    subscribed.delete(gameId)
    syncTargets.delete(gameId)
    return context
  }

  function isCurrent(gameId: string, context: { token: string | null }) {
    return contexts.get(gameId) === context && sessionToken() === context.token
  }

  function notifyConnection(gameId: string, state: GameConnection): void {
    for (const listener of connectionListeners.get(gameId) ?? []) listener(state)
  }

  async function presentationEvents(gameId: string, version: number, assertCurrent: () => void): Promise<AppliedEvent[]> {
    const previous = history.get(gameId) ?? { version: 0, events: [] }
    if (previous.version >= version) return previous.events
    const events = [...previous.events]
    for (let from = previous.version + 1; from <= version; from += 500) {
      const to = Math.min(from + 499, version)
      const replay = await request<{ events: Array<{ version: number; type: string; phase: string; round: number; occurredAt: number; actorId: string; payload: Record<string, unknown> }> }>(
        `/api/v1/games/${encodeURIComponent(gameId)}/replay?from=${from}&to=${to}`)
      assertCurrent()
      for (const event of replay.events ?? []) events.push({ ...event, seq: event.version })
    }
    if ((history.get(gameId)?.version ?? 0) <= version) history.set(gameId, { version, events })
    return events
  }

  /**
   * 每个 gameId 的**当前展示视图**。
   *
   * 存在的理由：发言增量要在"现有视图"上叠加 `preview`，而它**不该触发一次 REST 刷新**
   * （发言文本已经在帧里）。因此需要记住上一次推出去的视图作为基线。
   */
  const views = new Map<string, GamePresentation>()

  const streamOf = (gameId: string): GameStreamState =>
    streams.get(gameId) ?? emptyState()

  /** 拉取权威视图并推给订阅者。 */
  async function refresh(gameId: string): Promise<GamePresentation> {
    const context = contextFor(gameId)
    const assertCurrent = () => {
      if (!isCurrent(gameId, context)) throw new Error('会话或订阅已变更，请重新读取对局。')
    }
    const view = await request<GameView>(`/api/v1/games/${encodeURIComponent(gameId)}`)
    assertCurrent()
    const events = await presentationEvents(gameId, view.version, assertCurrent)
    assertCurrent()
    const newer = views.get(gameId)
    if (newer && newer.version > view.version) return newer
    // applyView 只清脏标记并替换视图；**不抬水位**（见 gameStream 的说明）。
    streams.set(gameId, applyView(streamOf(gameId), view))
    /*
     * `preview` 由发言增量维护，而 REST 视图不含它（后端没有"正在说的那句话"这个投影）。
     * 因此刷新时**保留**已有的 preview：不保留会让"正在说的那句话"每收到一个事件就消失一次，
     * 表现为发言一卡一卡地闪现。
     */
    const presentation: GamePresentation = {
      ...toGamePresentation(view, mySeatNo(), events, Date.now() + (clockOffsets.get(gameId) ?? 0)),
      preview: views.get(gameId)?.preview ?? null,
    }
    views.set(gameId, presentation)
    for (const listener of listeners.get(gameId) ?? []) {
      listener(presentation)
    }
    if (subscribed.has(gameId) && sockets.get(gameId)?.currentState === 'ready') {
      if (presentation.version >= (syncTargets.get(gameId) ?? 0)) notifyConnection(gameId, 'local')
      else scheduleRefresh(gameId)
    }
    return presentation
  }

  /**
   * 合并多次刷新请求。
   *
   * 一个阶段切换会产生多条事件（`PHASE_CHANGED` + 若干结算事件），逐条刷新会在
   * 几百毫秒内打五六次 REST。而刷新是幂等的——它只取当前状态，
   * 因此合并的代价为零，收益是请求量降一个数量级。
   */
  function scheduleRefresh(gameId: string): void {
    if (refreshTimers.has(gameId)) return
    const timer = setTimeout(() => {
      refreshTimers.delete(gameId)
      void refresh(gameId).catch(() => {
        // 刷新失败不覆盖已有视图：把界面清空比显示旧数据更糟（用户会以为对局没了）。
        // 下一次事件或轮询会再试。
      })
    }, debounceMs)
    refreshTimers.set(gameId, timer)
  }

  /** 处理一帧：归约 → 按需刷新。 */
  function onFrame(gameId: string, frame: ServerFrame): void {
    if (frame.ts > 0) clockOffsets.set(gameId, frame.ts - Date.now())
    if (frame.type === 'SUBSCRIBED') {
      subscribed.add(gameId)
      syncTargets.set(gameId, Number(frame.payload.to ?? 0))
      scheduleRefresh(gameId)
    }
    if (frame.type === 'GAME_SNAPSHOT_SYNC') {
      syncTargets.set(gameId, frame.seq)
      history.delete(gameId)
      speechBuffers.delete(gameId)
    }
    const before = streamOf(gameId)
    const after = reduceFrame(before, frame)
    if (after !== before) streams.set(gameId, after)

    /*
     * 发言已提交 → 清掉 `preview`，并**立刻推一次**给订阅者。
     *
     * 不清的话"正在说的那句话"会一直留在界面上：REST 刷新刻意保留 `preview`
     * （不保留会让它每收到一个事件就闪一下），因此它只能靠语义信号清除。
     * 而"推送"不能等 `scheduleRefresh`——那个刷新有 120ms 合并延迟，
     * 且只在 `dirty` 时才发生，而发言提交不一定同时改变视图。
     */
    if (after.previewSuperseded && views.get(gameId)?.preview) {
      const cleared: GamePresentation = { ...views.get(gameId)!, preview: null }
      views.set(gameId, cleared)
      for (const listener of listeners.get(gameId) ?? []) {
        listener(cleared)
      }
    }

    // 只有归约层认为"视图可能变了"时才拉 REST；否则夜间每条狼人行动都会打一次接口。
    if (after.dirty) scheduleRefresh(gameId)
  }

  /** 每个 gameId 的发言累积状态：同一条发言的多段按 speechId 归属。 */
  const speechBuffers = new Map<string, { speechId: string; text: string; seatNo: number; nextIndex: number; broken: boolean }>()

  /**
   * 处理一条发言增量：更新 `preview` 并推给订阅者。
   *
   * **它不进 `gameStream` 的归约**，也不推进任何水位——发言增量是展示更新，
   * 不是领域事件（AGENTS.md §9）。把它喂给归约层会让客户端的水位跳过真正的事件版本，
   * 从而在下一版误判缺号。
   *
   * 模型驱动的 AI 发言按小句流式到达（`index` 递增，最后一段 `final=true`）；
   * 规则基线等没有流式来源的发言只有一条 `index=0, final=true`。两种都按 `speechId` 归属、
   * `index` 递增则拼接，收到新的 `speechId` 就替换掉上一条的缓冲。
   *
   * `index` 缺号（含中途加入时首段不是 0）按 websocket-protocol.md D-2 处理：丢弃已累积的预览，
   * 本条发言余下的增量都不再显示，不补发、不重试，等 `PLAYER_SPOKE` 给出权威内容。重复的 `index` 忽略。
   */
  function onSpeechDelta(gameId: string, frame: ServerFrame): void {
    const payload = frame.payload
    const speechId = String(payload.speechId ?? '')
    if (!speechId) return
    if (streamOf(gameId).events.some(event => event.type === 'PLAYER_SPOKE' && event.payload.speechId === speechId)
      || views.get(gameId)?.messages.some(message => message.id === speechId)) return
    const delta = String(payload.delta ?? '')
    const seatNo = Number(payload.seatNo ?? 0)
    const index = Number(payload.index ?? 0)
    const last = payload.final === true

    const previous = speechBuffers.get(gameId)
    const continuing = previous?.speechId === speechId
    if (continuing && index < previous.nextIndex) return
    const broken = continuing ? previous.broken || index !== previous.nextIndex : index !== 0
    if (broken) {
      if (last) speechBuffers.delete(gameId)
      else speechBuffers.set(gameId, { speechId, text: '', seatNo, nextIndex: index + 1, broken: true })
      if (views.get(gameId)?.preview?.speechId === speechId) publishPreview(gameId, null)
      return
    }
    const text = continuing ? previous.text + delta : delta
    if (last) {
      // 最后一段到达：清掉缓冲，避免下一段同 id 的发言被误接上。
      speechBuffers.delete(gameId)
    } else {
      speechBuffers.set(gameId, { speechId, text, seatNo, nextIndex: index + 1, broken: false })
    }

    // 推给订阅者：`preview` 是"正在说的那句话"。
    // 这里**不重新拉 REST**——发言文本已经在帧里，为显示它而多打一次接口没有必要。
    publishPreview(gameId, { speechId, seatNo, content: text })
  }

  function publishPreview(gameId: string, preview: GamePresentation['preview']): void {
    const current = views.get(gameId)
    if (!current) return
    const next: GamePresentation = { ...current, preview }
    views.set(gameId, next)
    for (const listener of listeners.get(gameId) ?? []) {
      listener(next)
    }
  }

  function ensureSocket(gameId: string): GameSocket {
    const context = contextFor(gameId)
    const existing = sockets.get(gameId)
    if (existing) return existing

    const socket = new GameSocket({
      gameId,
      // 重连时带已归约到的水位：带 0 会让服务端重放整局历史。
      lastSeq: () => streamOf(gameId).seq,
      onFrame: (frame) => { if (isCurrent(gameId, context)) onFrame(gameId, frame) },
      /*
       * 发言增量：它是**展示更新**，不进 `gameStream` 的归约（因此不推进 seq），
       * 而是直接更新 `preview` 并推给订阅者。
       *
       * 模型发言是逐小句的真增量，规则基线发言是一条完整帧；
       * 累积逻辑对两者相同：`index` 递增则拼接，`final` 到达即认为这段发言结束。
       */
      onSpeechDelta: (frame) => { if (isCurrent(gameId, context)) onSpeechDelta(gameId, frame) },
      onState: state => {
        if (!isCurrent(gameId, context)) return
        options.onConnectionState?.(state)
        if (state !== 'ready') subscribed.delete(gameId)
        notifyConnection(gameId, state === 'closed' ? 'disconnected' : 'syncing')
      },
      onUnauthenticated: options.onUnauthenticated,
      onSyncRequired: () => {
        if (!isCurrent(gameId, context)) return
        // 服务端说缺口补不上：标记重建并立刻全量刷新。这是**唯一**该全量重建的信号，
        // 不能把 seq 不连续也当成它（服务端按可见性过滤后本就跳过版本）。
        streams.set(gameId, markResyncRequired(streamOf(gameId)))
        history.delete(gameId)
        speechBuffers.delete(gameId)
        subscribed.delete(gameId)
        notifyConnection(gameId, 'syncing')
        socket.resume()
        scheduleRefresh(gameId)
      },
    })
    sockets.set(gameId, socket)
    return socket
  }

  return {
    /**
     * 读取视图。
     *
     * 不做缓存：对局状态变化频繁，而缓存命中率低、失效逻辑复杂。真正的性能问题
     * 是"事件驱动的高频刷新"，那由 `scheduleRefresh` 的合并处理。
     */
    async get(gameId: string): Promise<GamePresentation> {
      return refresh(gameId)
    },

    /**
     * 本地剧本切换。
     *
     * 真实后端**没有这个概念**（剧本是 mock 适配器的演示能力）。这里明确抛错而不是
     * 静默忽略：静默会让界面上的按钮点了没反应，而错误信息能让人立刻明白该功能不可用。
     */
    async scenario(_gameId: string, scenario: string): Promise<GamePresentation> {
      throw new Error(`真实后端不支持切换剧本（请求：${scenario}）；请使用 VITE_USE_REAL_API=false 的演示模式`)
    },

    /** 提交行动。 */
    async submit(gameId: string, command: GameCommand): Promise<GamePresentation> {
      const idempotencyKey = command.commandId || newIdempotencyKey('action')
      for (let attempt = 0; ; attempt += 1) {
        try {
          await request(`/api/v1/games/${encodeURIComponent(gameId)}/actions`, {
            method: 'POST',
            body: {
              actionType: command.actionType,
              params: command.params,
              // expectedVersion 来自视图的 version（api-spec.md §5.3）：服务端据此拒绝
              // "基于过期局面"的行动，因此必须来自最近一次成功读取的视图，不能用本地猜测。
              expectedVersion: command.expectedVersion,
            },
            idempotencyKey,
          })
          break
        } catch (error) {
          // api-spec.md §1：GAME_BUSY 只表示单写者锁被占用，同一幂等键退避重试不会重复提交。
          if (!(error instanceof ApiError) || error.code !== 'GAME_BUSY' || attempt >= GAME_BUSY_RETRIES) throw error
          await new Promise(resolve => setTimeout(resolve, GAME_BUSY_BACKOFF_MS * 2 ** attempt))
        }
      }
      // 提交成功后立刻读一次：不等 WS 事件，因为用户刚点了按钮，
      // 界面必须马上反映"已在等待其他人"而不是停在上一个阶段。
      return refresh(gameId)
    },

    /**
     * 订阅视图变化。
     *
     * 返回取消函数；取消会关掉 socket（没有订阅者时继续连着只是浪费连接与心跳）。
     */
    subscribe(gameId: string, receive: (view: GamePresentation) => void, connection?: (state: GameConnection) => void): () => void {
      let set = listeners.get(gameId)
      if (!set) {
        set = new Set()
        listeners.set(gameId, set)
      }
      set.add(receive)
      if (connection) {
        const callbacks = connectionListeners.get(gameId) ?? new Set()
        callbacks.add(connection)
        connectionListeners.set(gameId, callbacks)
        connection('syncing')
      }

      const socket = ensureSocket(gameId)
      socket.connect()

      // 订阅即先给一次当前视图：调用方不该为了拿到初始状态再调一次 get()。
      void refresh(gameId).catch(() => {
        // 首次拉取失败由调用方的加载态处理，这里不额外上报。
      })

      return () => {
        if (connection) connectionListeners.get(gameId)?.delete(connection)
        set!.delete(receive)
        if (set!.size === 0) {
          contexts.delete(gameId)
          listeners.delete(gameId)
          sockets.get(gameId)?.close()
          sockets.delete(gameId)
          streams.delete(gameId)
          views.delete(gameId)
          speechBuffers.delete(gameId)
          connectionListeners.delete(gameId)
          subscribed.delete(gameId)
          syncTargets.delete(gameId)
          history.delete(gameId)
          clockOffsets.delete(gameId)
          const timer = refreshTimers.get(gameId)
          if (timer) {
            clearTimeout(timer)
            refreshTimers.delete(gameId)
          }
        }
      }
    },
  }
}

/** 可提交动作的集合；用于界面过滤掉仅预览的类型。 */
export const SUBMITTABLE_ACTIONS: ReadonlySet<GameActionType> = new Set<GameActionType>([
  'SPEAK',
  'VOTE',
  'WOLF_KILL',
  'SEER_INSPECT',
  'WITCH_SAVE',
  'WITCH_POISON',
  'WITCH_PASS',
  'HUNTER_SHOOT',
  'SKIP',
  'WOLF_CHAT',
])
