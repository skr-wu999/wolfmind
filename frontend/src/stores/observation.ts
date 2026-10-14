import { computed, ref, watch } from 'vue'
import { defineStore } from 'pinia'
import { observationAdapter as selectedObservationAdapter } from '@/api/adapters'
import { eventInPerspective, projectObservation } from '@/features/observation/projection'
import { isNightPhase } from '@/features/observation/types'
import type { EventScope, ObservationAdapter, ObservationBoard, ObservationMatch, ObservationConnection } from '@/features/observation/types'

/**
 * 拖动时间轴时合并 `atVersion` 请求的窗口。
 *
 * 一次拖动会触发几十次 `seek`，每个位置都发一次请求既浪费，也会让棋盘与时间轴错位；
 * 120ms 与适配器的同步窗口一致。
 */
const boardDebounceMs = 120

export const useObservationStore = defineStore('observation', () => {
  const match = ref<ObservationMatch | null>(null), loading = ref(false), error = ref('')
  const live = ref(false), connection = ref<ObservationConnection>('closed'), syncError = ref('')
  const position = ref(0), playing = ref(false), speed = ref(1), perspective = ref('all'), scope = ref<EventScope | 'all'>('all')
  const board = ref<ObservationBoard | null>(null)
  let generation = 0, boardSequence = 0
  let unsubscribe: (() => void) | undefined, controller: AbortController | undefined
  let boardTimer: ReturnType<typeof setTimeout> | undefined, boardController: AbortController | undefined
  let boardPending: number | null = null
  /** 已确认取不到棋盘的版本（服务端没有该版本或请求失败），换局时清空。 */
  let boardUnavailable = new Set<number>()

  /**
   * 时间轴位置对应的领域版本：**可见事件**里版本最高的那一条。
   *
   * 返回 `null` 表示这个位置没有可请求的历史（没有对局、位置不是有限数、没有任何已知版本）。
   * 合法值一律钳在 `[1, snapshot.version]`（没有快照时退到已知的最高事件版本）：把 0、NaN 或
   * 超出流尾的版本号发给服务端，最好的结果是 400，最坏的结果是拿回一个与时间轴无关的棋盘。
   */
  function versionAt(position: number): number | null {
    const current = match.value
    if (!current || !Number.isFinite(position) || position < 0) return null
    let known = 0, version = 0
    for (const event of current.events) {
      if (event.version > known) known = event.version
      if (event.at <= position && event.version > version) version = event.version
    }
    const limit = current.snapshot?.version ?? known
    if (limit < 1) return null
    return Math.min(Math.max(version, 1), limit)
  }
  /**
   * 取权威棋盘：合并请求 + 丢弃迟到响应 + 换局即取消。
   *
   * 三道防线都要有。只防"重复点击"挡不住乱序返回——先发的请求可能后到，那时它承载的是**过期**
   * 的版本，覆盖上去就等于把棋盘退回几步之前。
   */
  function scheduleBoard() {
    clearTimeout(boardTimer); boardTimer = undefined
    const adapter = selectedObservationAdapter()
    const request = adapter.boardAt?.bind(adapter)
    if (live.value || !request || !match.value) { board.value = null; return }
    const version = versionAt(position.value)
    /*
     * 拿到过、正在取、或已经确认取不到的版本都不再请求。
     *
     * 播放时位置每 250ms 变一次，而版本只在事件边界上变；没有这三条，同一个版本会被反复重发，
     * 在途请求被反复取消，一个慢响应就永远回不来。确认取不到（null 或报错）是确定的结果，
     * 反复重试只会把失败变成一个稳定的请求风暴。
     */
    if (version === null || board.value?.version === version || boardPending === version || boardUnavailable.has(version)) return
    boardTimer = setTimeout(() => { boardTimer = undefined; void readBoard(request, version) }, boardDebounceMs)
  }
  async function readBoard(request: NonNullable<ObservationAdapter['boardAt']>, version: number) {
    const sequence = ++boardSequence, request0 = generation, gameId = match.value?.gameId
    if (!gameId) return
    boardPending = version
    boardController?.abort(); boardController = new AbortController()
    const controller = boardController
    try {
      const result = await request(gameId, version, controller.signal)
      if (sequence !== boardSequence || request0 !== generation) return
      board.value = result
      if (!result) boardUnavailable.add(version)
    } catch (cause) {
      if (sequence !== boardSequence || request0 !== generation) return
      if (cause instanceof DOMException && cause.name === 'AbortError') return
      // 取不到历史快照时保留本地投影：时间轴仍然可用，比整页报错更接近事实。
      board.value = null
      boardUnavailable.add(version)
    } finally {
      if (sequence === boardSequence && boardPending === version) boardPending = null
    }
  }
  const view = computed(() => {
    if (!match.value) return null
    const current = match.value, snapshot = current.snapshot
    if (live.value && snapshot) return { ...snapshot, players: current.players, events: current.events, atmosphere: isNightPhase(snapshot.phase) ? 'night' as const : 'day' as const }
    const projected = projectObservation(current, position.value)
    const authoritative = board.value
    // 只有在途请求与版本都对得上时才算数；否则先渲染本地投影，界面绝不留空。
    if (!authoritative || authoritative.version !== versionAt(position.value)) return projected
    return {
      ...projected,
      /*
       * 角色披露是**整局**级别的授权结果：历史快照在早期版本按"当时未结束"脱敏，因此这里用
       * 已加载的对局视图补上同一份披露——既不会在拖动时闪现身份，也不会把赛后已公开的角色藏起来。
       */
      players: authoritative.players.map(player => ({ ...player, disclosedRole: player.disclosedRole ?? current.players.find(entry => entry.seatNo === player.seatNo)?.disclosedRole })),
      phase: authoritative.phase, round: authoritative.round,
      /*
       * 服务端的 `currentTurn` 是"现在轮到谁"，没有回合窗口的阶段（投票、结算）为 null；
       * 那时沿用投影里的最后一位发言者，否则回放刚播完一句发言就显示"暂无已提交发言"。
       */
      speaker: authoritative.speaker ?? projected.speaker,
      winner: authoritative.winner,
      atmosphere: isNightPhase(authoritative.phase) ? 'night' as const : 'day' as const,
    }
  })
  const visibleEvents = computed(() => match.value?.events.filter(event => eventInPerspective(event, match.value!, perspective.value) && (scope.value === 'all' || scope.value === event.scope)) || [])
  const speeches = computed(() => visibleEvents.value.filter(event => event.type === 'PLAYER_SPOKE' && event.at <= position.value).reverse())
  const currentEvent = computed(() => [...visibleEvents.value].reverse().find(event => event.at <= position.value) || null)
  const chapters = computed(() => match.value?.events.filter(event => event.type === 'PHASE_CHANGED' || event.type === 'GAME_STARTED' || event.type === 'GAME_FINISHED') || [])
  watch(position, () => scheduleBoard())
  async function load(gameId: string, realtime = false) {
    const request = ++generation
    unsubscribe?.(); unsubscribe = undefined; controller?.abort(); controller = new AbortController()
    clearTimeout(boardTimer); boardTimer = undefined; boardController?.abort(); boardController = undefined
    boardSequence++; board.value = null; boardPending = null; boardUnavailable = new Set()
    live.value = realtime; connection.value = 'closed'; syncError.value = ''
    loading.value = true; error.value = ''; playing.value = false; match.value = null
    position.value = 0; speed.value = 1; perspective.value = 'all'; scope.value = 'all'
    try {
      const adapter = selectedObservationAdapter()
      const result = await adapter.get(gameId, controller.signal)
      if (request !== generation) return
      match.value = { ...result, events: [...result.events].sort((a, b) => a.version - b.version) }
      position.value = result.initialPosition
      // 位置没变（例如开局就停在 0）时 watcher 不会触发，这里显式取一次。
      scheduleBoard()
      if (realtime && result.snapshot?.finished) connection.value = 'finished'
      else if (realtime && adapter.subscribe) unsubscribe = adapter.subscribe(gameId, result.snapshot?.version ?? 0, {
        onMatch(result) { if (request === generation) { match.value = result; position.value = result.initialPosition } },
        onState(state) { if (request === generation) connection.value = state },
        onError(message) { if (request === generation) syncError.value = message },
      })
    } catch (cause) {
      if (request === generation) error.value = cause instanceof Error ? cause.message : '加载失败，请重试。'
    } finally { if (request === generation) loading.value = false }
  }
  function seek(value: number) {
    if (live.value || !match.value || !Number.isFinite(value)) return
    playing.value = false
    position.value = Math.max(0, Math.min(match.value.duration, value))
  }
  function step(direction: -1 | 1) {
    const ordered = visibleEvents.value
    const next = direction === 1 ? ordered.find(event => event.at > position.value) : [...ordered].reverse().find(event => event.at < position.value)
    if (next) seek(next.at)
  }
  function toggle() {
    if (live.value || !match.value?.events.length) return
    if (position.value >= match.value.duration) position.value = 0
    playing.value = !playing.value
  }
  function tick(seconds: number) {
    if (!playing.value || !match.value || seconds <= 0 || !Number.isFinite(seconds)) return
    position.value = Math.min(match.value.duration, position.value + seconds * speed.value)
    if (position.value >= match.value.duration) playing.value = false
  }
  function dispose() {
    generation++; boardSequence++
    unsubscribe?.(); unsubscribe = undefined; controller?.abort()
    clearTimeout(boardTimer); boardTimer = undefined; boardController?.abort(); boardController = undefined
    board.value = null; boardPending = null; boardUnavailable = new Set()
    playing.value = false; loading.value = false; match.value = null; connection.value = 'closed'
  }
  return { match, loading, error, live, connection, syncError, position, playing, speed, perspective, scope, board, view, visibleEvents, speeches, currentEvent, chapters, load, seek, step, toggle, tick, dispose }
})
