import { ApiError, request } from '@/api/apiClient'
import { sessionToken } from '@/api/config'
import { GameSocket } from '@/api/gameSocket'
import { avatarFor } from '@/api/mapRoom'
import { finishReasonNames, roleNames, type GameRole } from '@/features/game/types'
import type { GameView } from '@/api/restTypes'
import type { EventScope, ObservationAdapter, ObservationBoard, ObservationMatch, ObservationPhase } from './types'
import { unknownPhase } from './types'

interface ReplayEvent {
  eventId: string; version: number; type: string; occurredAt: number
  phase?: string; round?: number; visibility?: string; payload?: Record<string, unknown>
}
interface ReplayWire { events: ReplayEvent[]; snapshot?: GameView }
const titles: Record<string, string> = {
  GAME_STARTED: '游戏开始', ROLE_ASSIGNED: '角色分配', PHASE_CHANGED: '阶段推进', TURN_GRANTED: '轮到行动',
  PLAYER_SPOKE: '玩家发言', SPEECH_SKIPPED: '跳过发言', WOLF_KILL_NOMINATED: '狼人提名目标',
  WOLF_KILL_SELECTED: '狼人确定目标', WOLF_KILL_EMPTY: '狼人空刀', WOLF_CHAT_SENT: '狼人密谈', SEER_INSPECTED: '预言家查验',
  SEER_SKIPPED: '预言家未查验', WITCH_SAVED: '女巫使用解药', WITCH_POISONED: '女巫使用毒药',
  WITCH_PASSED: '女巫未用药', PLAYER_VOTED: '玩家投票', VOTE_TALLY_RESOLVED: '投票结算',
  PLAYER_EXILED: '玩家被放逐', PLAYER_KILLED: '死亡结算', DEATH_ANNOUNCED: '公布出局',
  HUNTER_SHOT: '猎人开枪', HUNTER_SKIPPED: '猎人未开枪', LAST_WORDS_GRANTED: '获得遗言',
  GAME_FINISHED: '对局结束', GAME_ABORTED: '对局中止', PLAYER_CONTROLLER_CHANGED: '控制者变更',
}
/**
 * 领域阶段 → 观战文案。取值必须覆盖 `GamePhase` 的**全部**枚举：`DAY_ANNOUNCE`、`VOTE_SETTLE`
 * 这类自动阶段同样会产生 `PHASE_CHANGED`，漏掉它们就是下拉框里的"第 N 天 · undefined"（QA P2）。
 *
 * 未知阶段返回显式占位而不是 `undefined`：后者会一路渲染成 `undefined`，
 * 而"服务端给了一个我们不认识的阶段"是需要被看见的事实。
 */
const phaseNames: Record<string, ObservationPhase> = {
  GAME_INIT: '等待开局', NIGHT_WOLF: '狼人行动', NIGHT_SEER: '预言家查验', NIGHT_WITCH: '女巫用药',
  NIGHT_SETTLE: '夜间结算', DAY_ANNOUNCE: '公布死讯', LAST_WORDS: '遗言', DAY_DISCUSS: '白天讨论',
  DAY_VOTE: '放逐投票', VOTE_SETTLE: '投票结算', HUNTER_SHOOT: '猎人开枪', GAME_OVER: '游戏结束',
}
export function phaseOf(phase?: string): ObservationPhase {
  return (phase !== undefined && phaseNames[phase]) || unknownPhase
}
/**
 * 事件所属的阶段名。
 *
 * `PHASE_CHANGED` 信封上的 `phase` 是**跳转前**的阶段（`Game.meta()` 在 emit 前取当前值），
 * 用它当章节名会把"离开的阶段"说成"进入的阶段"；目标阶段在载荷 `to` 里，且同样属于 PUBLIC。
 */
function declaredPhaseOf(event: ReplayEvent): string | undefined {
  const payload = event.payload ?? {}
  if (event.type === 'PHASE_CHANGED') return textAt(payload, 'to') ?? textAt(payload, 'toPhase') ?? event.phase
  return event.phase
}
function winnerOf(camp?: string | null): string | null {
  return camp === 'WOLF' ? '狼人阵营获胜' : camp === 'VILLAGER' || camp === 'VILLAGE' ? '好人阵营获胜' : null
}
function textAt(payload: Record<string, unknown>, key: string): string | undefined {
  const value = payload[key]
  return typeof value === 'string' && value ? value : undefined
}
function numberAt(payload: Record<string, unknown>, key: string): number | undefined {
  const value = payload[key]
  return typeof value === 'number' ? value : undefined
}
function contentOf(event: ReplayEvent, seatNo?: number): string {
  const p = event.payload ?? {}, seat = seatNo === undefined ? '玩家' : `${seatNo} 号`
  const target = numberAt(p, 'targetSeatNo')
  switch (event.type) {
    case 'PLAYER_SPOKE': return textAt(p, 'content') ? `${seat}：${p.content}` : `${seat}发言（内容未投影）`
    case 'SPEECH_SKIPPED': return `${seat}跳过发言`
    case 'PLAYER_VOTED': return p.abstain === true ? `${seat}弃权` : target === undefined ? `${seat}投票（目标未投影）` : `${seat}投给 ${target} 号`
    case 'PLAYER_EXILED': return seatNo === undefined ? '有玩家被放逐' : `${seat}被放逐出局`
    case 'PLAYER_KILLED': return seatNo === undefined ? '玩家出局结算' : `${seat}出局${Array.isArray(p.causes) && p.causes.includes('EXILE') ? '（放逐）' : Array.isArray(p.causes) && p.causes.includes('HUNTER_SHOT') ? '（猎人枪击）' : '（夜间结算）'}`
    case 'DEATH_ANNOUNCED': {
      const seats = Array.isArray(p.deaths) ? p.deaths.map(death => numberAt(death as Record<string, unknown>, 'seatNo')).filter(n => n !== undefined) : []
      return seats.length ? `公布：${seats.join('、')} 号出局` : seatNo === undefined ? '公布出局结果' : `公布：${seat}出局`
    }
    case 'VOTE_TALLY_RESOLVED': return numberAt(p, 'exiledSeatNo') === undefined ? '投票完成结算' : `投票结果：${p.exiledSeatNo} 号出局`
    case 'WOLF_KILL_SELECTED': return target === undefined ? '狼人确定目标' : `狼人阵营选择袭击 ${target} 号`
    case 'WOLF_KILL_EMPTY': return '狼人今夜空刀'
    case 'WOLF_CHAT_SENT': return textAt(p, 'content') ? `${seat}（狼队密谈）：${p.content}` : `${seat}发送了狼队密谈`
    case 'SEER_INSPECTED': return target === undefined ? '预言家完成查验' : `${seat}查验 ${target} 号${textAt(p, 'result') ? `，结果为${p.result === 'WOLF' ? '狼人' : '好人'}` : ''}`
    case 'WITCH_SAVED': return `${seat}使用解药`
    case 'WITCH_POISONED': return target === undefined ? `${seat}使用毒药` : `${seat}对 ${target} 号使用毒药`
    case 'WITCH_PASSED': return `${seat}未使用药水`
    case 'HUNTER_SHOT': return target === undefined ? `${seat}开枪` : `${seat}开枪带走 ${target} 号`
    case 'HUNTER_SKIPPED': return `${seat}未开枪`
    case 'GAME_FINISHED': return `${winnerOf(textAt(p, 'winnerCamp') ?? textAt(p, 'winner')) ?? '对局结束'}${textAt(p, 'finishReason') && finishReasonNames[String(p.finishReason)] ? `（${finishReasonNames[String(p.finishReason)]}）` : ''}`
    case 'PHASE_CHANGED': {
      const to = textAt(p, 'to') ?? textAt(p, 'toPhase')
      return to ? `进入${phaseOf(to)}` : '阶段推进'
    }
    case 'ROLE_ASSIGNED': return `${seat}的角色已分配${textAt(p, 'role') && p.role as string in roleNames ? `：${roleNames[p.role as GameRole]}` : ''}`
    default: return titles[event.type] ?? event.type
  }
}

/**
 * 座位映射的唯一实现：视图与历史快照的 `players[]` 形状相同，两处必须得到同一张脸、同一个名字，
 * 否则拖动时间轴会让座位信息跳动。
 */
function playersOf(players: GameView['players'], discloseRoles: boolean): ObservationMatch['players'] {
  return players.map(p => ({ seatNo: p.seatNo, name: p.playerId || `${p.seatNo} 号`, avatar: avatarFor(p.seatNo),
    controller: p.controller?.type === 'HUMAN' ? 'HUMAN' : 'AI', alive: p.alive,
    ...(discloseRoles && p.role && p.role in roleNames ? { disclosedRole: p.role as GameRole } : {}) }))
}
function finishedOf(status: string): boolean {
  return status === 'FINISHED' || status === 'ABORTED'
}

export function createRealObservationAdapter(): ObservationAdapter {
  async function get(gameId: string, signal?: AbortSignal): Promise<ObservationMatch> {
    const token = sessionToken(), path = `/api/v1/games/${encodeURIComponent(gameId)}`
    const assertContext = () => {
      if (token !== sessionToken()) throw new DOMException('会话已切换', 'AbortError')
    }
    const view = await request<GameView>(path, { signal })
    assertContext()
    const finished = finishedOf(view.status)
    if (view.me && !finished) throw new ApiError('PARTICIPANT_VIEW', '你正在参与这场对局，请返回对局页面。', 403)
    const raw: ReplayEvent[] = []
    // 按领域版本窗口读取；隐藏事件造成的版本缺口不能当成分页结束。
    for (let from = 1; from <= view.version; from += 500) {
      const to = Math.min(from + 499, view.version)
      const page = await request<ReplayWire>(`${path}/replay?from=${from}&to=${to}&limit=500`, { signal })
      assertContext()
      raw.push(...page.events)
    }
    // 对局可能在视图与历史请求之间结束；重读终局基线，避免将完整历史标成进行中。
    if (!finished && raw.some(event => event.visibility && event.visibility !== 'PUBLIC')) {
      const latest = await request<GameView>(path, { signal })
      assertContext()
      if (latest.status === 'FINISHED' || latest.status === 'ABORTED') return get(gameId, signal)
      throw new ApiError('VISIBILITY_MISMATCH', '观战记录与对局状态不一致，请刷新重试。', 503)
    }
    const ordered = [...new Map(raw.map(event => [event.eventId, event])).values()].sort((a, b) => a.version - b.version)
    const origin = ordered.find(event => event.type === 'GAME_STARTED')?.occurredAt ?? ordered[0]?.occurredAt ?? 0
    const events: ObservationMatch['events'] = ordered.map(event => {
      const p = event.payload ?? {}, seatNo = numberAt(p, 'seatNo') ?? numberAt(p, 'actorSeatNo') ?? numberAt(p, 'bySeatNo') ?? numberAt(p, 'seerSeatNo') ?? numberAt(p, 'witchSeatNo') ?? numberAt(p, 'wolfSeatNo') ?? numberAt(p, 'hunterSeatNo')
      const phase = phaseOf(declaredPhaseOf(event)), winner = winnerOf(textAt(p, 'winnerCamp') ?? textAt(p, 'winner'))
      const eliminated = numberAt(p, 'exiledSeatNo') ?? numberAt(p, 'seatNo')
      const scope: EventScope = ['WOLF', 'PRIVATE', 'JUDGE'].includes(event.visibility ?? '') ? event.visibility as EventScope : 'PUBLIC'
      return {
        id: event.eventId, version: event.version, at: Math.max(0, (event.occurredAt - origin) / 1000),
        type: event.type, title: titles[event.type] ?? event.type, content: contentOf(event, seatNo), scope,
        ...(seatNo !== undefined ? { seatNo } : {}),
        ...(scope === 'PRIVATE' && seatNo !== undefined ? { recipient: seatNo } : {}),
        change: { phase, ...(event.round !== undefined ? { round: event.round } : {}),
          ...(['PLAYER_EXILED', 'PLAYER_KILLED'].includes(event.type) && eliminated !== undefined ? { eliminated } : {}),
          ...(event.type === 'GAME_FINISHED' && winner ? { winner } : {}) },
      }
    })
    const duration = events.at(-1)?.at ?? 0
    return {
      gameId, roomName: view.roomId || '对局', startedAt: origin ? new Date(origin).toLocaleString('zh-CN') : '',
      duration, initialPosition: duration, events,
      players: playersOf(view.players, finished),
      snapshot: { version: view.version, phase: phaseOf(view.phase), round: view.round,
        speaker: view.currentTurn?.seatNo ?? null, winner: winnerOf(view.winner), finished },
    }
  }

  /**
   * 某个版本上的权威棋盘。
   *
   * 只请求那一个版本的窗口拿 `snapshot`：棋盘由服务端重建，前端不重放事件、不推断存活
   * （api-spec.md §6、AP-6）。因此这里不需要也不允许读 `events`。
   */
  async function boardAt(gameId: string, version: number, signal?: AbortSignal): Promise<ObservationBoard | null> {
    // 非法版本号一律不发：NaN / 0 / 负数只会换来 400，而调用方（时间轴）本来就能给出合规版本。
    if (!Number.isInteger(version) || version < 1) return null
    const token = sessionToken(), path = `/api/v1/games/${encodeURIComponent(gameId)}`
    const page = await request<ReplayWire>(`${path}/replay?from=${version}&to=${version}&atVersion=${version}`, { signal })
    if (token !== sessionToken()) throw new DOMException('会话已切换', 'AbortError')
    const snapshot = page.snapshot
    if (!snapshot) return null
    const finished = finishedOf(snapshot.status)
    return {
      version: snapshot.version, phase: phaseOf(snapshot.phase), round: snapshot.round,
      speaker: snapshot.currentTurn?.seatNo ?? null, winner: winnerOf(snapshot.winner), finished,
      players: playersOf(snapshot.players, finished),
    }
  }
  return {
    get,
    boardAt,
    subscribe(gameId, version, callbacks) {
      const token = sessionToken()
      if (!token) { callbacks.onState('login-required'); return () => {} }
      let active = true, finished = false, cursor = version, target = version, busy = false, pending = false, ready = false
      let timer: ReturnType<typeof setTimeout> | undefined
      const controller = new AbortController()
      const valid = () => active && token === sessionToken()
      const schedule = () => {
        if (!valid() || finished) return
        pending = true
        if (busy || timer) return
        timer = setTimeout(() => { timer = undefined; void refresh() }, 120)
      }
      async function refresh() {
        if (!valid() || finished) return
        busy = true; pending = false
        try {
          const match = await get(gameId, controller.signal)
          if (!valid()) return
          callbacks.onMatch(match); callbacks.onError('')
          if (match.snapshot?.finished) {
            finished = true; callbacks.onState('finished'); socket.unsubscribe(); socket.close()
          } else if (ready) callbacks.onState(match.snapshot!.version >= target ? 'live' : 'syncing')
        } catch (cause) {
          if (valid() && !(cause instanceof DOMException && cause.name === 'AbortError')) {
            callbacks.onError(cause instanceof Error ? cause.message : '同步失败，请刷新重试。')
            callbacks.onState('closed')
          }
        } finally { busy = false; if (pending) schedule() }
      }
      const socket = new GameSocket({ gameId, lastSeq: () => cursor,
        onFrame(frame) {
          if (!valid() || finished) return
          if (frame.type === 'SUBSCRIBED') target = Math.max(target, Number(frame.payload.to) || 0)
          else if (frame.type === 'GAME_SNAPSHOT_SYNC') { cursor = frame.seq; target = frame.seq; socket.ack(cursor) }
          else { cursor = Math.max(cursor, frame.seq); target = Math.max(target, cursor); socket.ack(cursor) }
          schedule()
        },
        onState(state) {
          if (!valid() || finished) return
          ready = state === 'ready'
          callbacks.onState(state === 'ready' ? 'syncing' : state === 'idle' ? 'connecting' : state === 'authenticating' ? 'connecting' : state)
        },
        onSyncRequired() { if (valid()) { cursor = 0; callbacks.onState('syncing'); socket.resume(); schedule() } },
        onUnauthenticated() { if (active) { callbacks.onState('login-required'); callbacks.onError('会话已过期，请重新登录。') } },
      })
      socket.connect()
      return () => { active = false; clearTimeout(timer); controller.abort(); socket.unsubscribe(); socket.close() }
    },
  }
}
export const realObservationAdapter = createRealObservationAdapter()
