import type { GameAdapter, GameCommand, GamePresentation, GameRole, ActionPermission } from './types'
import { gameScenarios } from './types'
import { demoRules } from './rules'

const storageKey = 'wolfmind.demo.game.v1'
const clone = <T>(value: T): T => JSON.parse(JSON.stringify(value))
const names = ['小林', 'Moon', 'AI-白狐', 'AI-寒鸦', 'AI-001', '推理猫', '阿泽', '北巷', 'AI-夜枭']
const avatars = ['green', 'blue', 'fox', 'wolf', 'wolf', 'fox', 'red', 'green', 'owl'] as const
const speech = '我昨晚没有观察到异常信息。现在 7 号的发言更像在试探立场，而不是给出判断。我倾向先观察 7 号和 9 号的发言。如果 7 号坚持这个逻辑，我认为他的判断还需要更多证据。'

function fixture(gameId: string, scenario: string): GamePresentation {
  const now = Date.now()
  const view: GamePresentation = {
    rules: { ...demoRules },
    gameId, version: 1, scenario, roomName: '雾隐村 · 狼人游戏', phase: 'DAY_DISCUSSION', round: 2, atmosphere: 'day', deadline: now + 90000, windowMs: 90000, serverNow: now,
    players: names.map((name, i) => ({ seatNo: i + 1, name, avatar: avatars[i]!, controller: name.startsWith('AI-') ? 'AI' : 'HUMAN', alive: i !== 5 })),
    me: { seatNo: 7, role: 'SEER', alive: true }, speakerSeatNo: 3, turnOrder: [1, 2, 3, 4, 5, 7, 8, 9], completedSpeakers: [1, 2],
    messages: [{ id: 'speech-1', seatNo: 1, round: 2, order: 1, scope: 'PUBLIC', content: '昨夜平安。今天先听完所有人的发言，再决定投票。' }, { id: 'speech-2', seatNo: 2, round: 2, order: 2, scope: 'PUBLIC', content: '我想听听 7 号对昨天投票的解释。' }],
    preview: { speechId: 'speech-3', seatNo: 3, content: '' }, permissions: [],
    records: [{ id: 'day', round: 2, order: 0, scope: 'PUBLIC', text: '第 2 天开始。昨夜平安，没有玩家出局。' }, { id: 'speech', round: 2, order: 3, scope: 'PUBLIC', text: '01 小林与 02 Moon 已完成发言。' }],
    privateRecords: [{ id: 'inspect-1', scope: 'PRIVATE', text: '第 1 夜：02 Moon · 非狼人阵营。' }], wolfTeam: [], wolfMessages: [],
    aliveCount: 8, eliminatedCount: 1, votedCount: null, submitted: null, result: null, privateResult: null,
  }
  const targets = view.players.filter(p => p.alive && p.seatNo !== 7).map(p => p.seatNo)
  const permission = (type: ActionPermission['type'], label: string, available = targets, allowEmpty = false, remaining?: number): ActionPermission => ({ type, label, targets: available, allowEmpty, remaining })
  if (scenario !== 'discussion') { view.preview = null; view.speakerSeatNo = null }
  if (scenario === 'my-turn') {
    view.speakerSeatNo = 7; view.completedSpeakers = [1, 2, 3, 4, 5]; view.permissions = [permission('SPEAK', '提交发言', [])]
  } else if (scenario === 'voting') {
    view.phase = 'DAY_VOTING'; view.permissions = [permission('VOTE', '确认投票', targets, true)]; view.votedCount = 3
  } else if (scenario === 'daybreak') {
    view.phase = 'DAYBREAK'; view.result = { title: '昨夜平安', content: '昨夜没有玩家出局。晨光照进雾隐村，新的一天开始了。' }; view.deadline = null
  } else if (scenario === 'vote-result') {
    view.phase = 'VOTE_RESULT'; view.result = { title: '投票结束', content: '04 AI-寒鸦被放逐。等待下一阶段。', tally: [{ seatNo: 4, count: 4 }, { seatNo: 9, count: 2 }, { seatNo: 2, count: 1 }] }; view.players[3]!.alive = false; view.aliveCount = 7; view.eliminatedCount = 2; view.deadline = null
  } else if (scenario === 'game-over') {
    view.phase = 'GAME_OVER'; view.deadline = null; view.result = { title: '好人阵营获胜', content: '雾隐村重归安宁。本地示例对局已结束，身份现已公开。' }
    const roles: GameRole[] = ['VILLAGER', 'WEREWOLF', 'VILLAGER', 'WEREWOLF', 'HUNTER', 'VILLAGER', 'SEER', 'WITCH', 'WEREWOLF']
    view.players.forEach((p, i) => { p.disclosedRole = roles[i] })
  } else if (!['discussion', 'my-turn', 'voting', 'daybreak', 'vote-result'].includes(scenario)) {
    view.atmosphere = 'night'; view.phase = 'NIGHT_ACTION'; view.privateRecords = []; view.records = [{ id: 'night', round: 2, order: 10, scope: 'PUBLIC', text: '第 2 夜：天黑了，请闭眼。' }]
    if (scenario === 'seer') { view.permissions = [permission('SEER_INSPECT', '确认查验')]; view.privateRecords = [{ id: 'inspect-1', scope: 'PRIVATE', text: '第 1 夜：02 Moon · 非狼人阵营。' }] }
    if (scenario === 'wolf') {
      view.me.role = 'WEREWOLF'; view.wolfTeam = [2, 7, 9]; view.permissions = [permission('WOLF_KILL', '确认袭击', targets.filter(n => !view.wolfTeam.includes(n)))];
      view.players.filter(p => view.wolfTeam.includes(p.seatNo)).forEach(p => { p.disclosedRole = 'WEREWOLF' })
      view.wolfMessages = [{ id: 'wolf-1', seatNo: 9, round: 2, scope: 'WOLF', content: '我建议先讨论一下 1 号，他白天的判断比较准确。' }]; view.permissions.push(permission('WOLF_CHAT', '发送密谈', []))
    }
    if (scenario === 'witch') { view.me.role = 'WITCH'; view.permissions = [permission('WITCH_SAVE', '使用解药', [8], false, 1), permission('WITCH_POISON', '确认使用毒药', targets, false, 1), permission('WITCH_PASS', '今晚不用药', [], true)] }
    if (scenario === 'guard') { view.me.role = 'GUARD'; view.permissions = [permission('GUARD_PREVIEW', '确认守护', targets.filter(n => n !== 2))]; view.privateRecords = [{ id: 'guard-1', scope: 'PRIVATE', text: '扩展预览：上一夜守护 02 Moon，本场景不可连续守护同一目标。' }] }
    if (scenario === 'hunter') { view.me.role = 'HUNTER'; view.me.alive = false; view.players[6]!.alive = false; view.aliveCount = 7; view.eliminatedCount = 2; view.phase = 'ROLE_REACTION'; view.permissions = [permission('HUNTER_SHOOT', '确认开枪', targets, true)] }
    if (['villager', 'hunter-wait', 'transition', 'resolving'].includes(scenario)) {
      view.me.role = scenario === 'hunter-wait' ? 'HUNTER' : 'VILLAGER'; view.phase = scenario === 'transition' ? 'NIGHT_TRANSITION' : scenario === 'resolving' ? 'NIGHT_RESOLVING' : 'NIGHT_WAITING'; view.deadline = null
    }
  }
  return view
}

export function createMockGameAdapter(storage?: Pick<Storage, 'getItem' | 'setItem'>): GameAdapter {
  const views = new Map<string, GamePresentation>()
  const accepted = new Map<string, { fingerprint: string; view: GamePresentation }>()
  const listeners = new Map<string, Set<(view: GamePresentation) => void>>()
  const streams = new Map<string, ReturnType<typeof setInterval>>()
  function persist() { try { storage?.setItem(storageKey, JSON.stringify([...views])) } catch { /* The demo remains available in memory when storage is disabled. */ } }
  try {
    const saved: unknown = JSON.parse(storage?.getItem(storageKey) || '[]')
    if (Array.isArray(saved)) for (const entry of saved) {
      if (Array.isArray(entry) && typeof entry[0] === 'string' && entry[1]?.gameId === entry[0] && entry[1]?.players?.length === 9 && gameScenarios.some(([id]) => id === entry[1]?.scenario) && typeof entry[1]?.version === 'number' && Array.isArray(entry[1]?.permissions) && Array.isArray(entry[1]?.records)) {
        // Restore only the known fixture and its explicitly supported local edits.
        const base = fixture(entry[0], entry[1].scenario)
        if (entry[1].deadline === null || (typeof entry[1].deadline === 'number' && Number.isFinite(entry[1].deadline))) base.deadline = entry[1].deadline
        if (base.scenario === 'discussion' && Array.isArray(entry[1].messages) && entry[1].messages.some((m: Record<string, unknown>) => m.id === 'speech-3')) {
          base.preview = null; base.completedSpeakers.push(3); base.messages.push({ id: 'speech-3', seatNo: 3, round: 2, scope: 'PUBLIC', content: speech })
        }
        if (entry[1].submitted && typeof entry[1].submitted === 'string') {
          base.submitted = entry[1].submitted; base.permissions = []; base.preview = null; base.deadline = null
          if (Array.isArray(entry[1].messages)) base.messages = entry[1].messages.filter((m: Record<string, unknown>) => m.scope === 'PUBLIC' && typeof m.content === 'string' && typeof m.seatNo === 'number').slice(-30)
          if (base.me.role === 'SEER' && entry[1].privateResult?.title === '真相浮现') { base.privateResult = entry[1].privateResult; base.privateRecords.push({ id: 'inspect-restored', scope: 'PRIVATE', text: base.privateResult!.content }) }
          if (base.scenario === 'my-turn') base.completedSpeakers.push(7)
          if (base.scenario === 'voting') base.votedCount = 4
        }
        if (Array.isArray(entry[1].privateRecords) && !['VILLAGER'].includes(base.me.role)) base.privateRecords = entry[1].privateRecords.filter((r: Record<string, unknown>) => typeof r.id === 'string' && typeof r.text === 'string' && (r.scope === 'PRIVATE' || (base.me.role === 'WEREWOLF' && r.scope === 'WOLF'))).slice(-30)
        if (Array.isArray(entry[1].records)) base.records = entry[1].records.filter((r: Record<string, unknown>) => r.scope === 'PUBLIC' && typeof r.id === 'string' && typeof r.text === 'string').slice(-30)
        if (base.me.role === 'WEREWOLF' && Array.isArray(entry[1].wolfMessages)) base.wolfMessages = entry[1].wolfMessages.filter((m: Record<string, unknown>) => m.scope === 'WOLF' && base.wolfTeam.includes(Number(m.seatNo)) && typeof m.id === 'string' && typeof m.content === 'string').slice(-30)
        base.version = entry[1].version; views.set(entry[0], base)
      }
    }
  } catch { /* Invalid saved demos start from a fresh fixture. */ }
  function ensure(gameId: string) {
    if (!/^demo(?:-[a-z0-9]+)?$/.test(gameId)) throw new Error('该对局尚未接入在线服务。可以打开本地示例对局。')
    if (!views.has(gameId)) views.set(gameId, fixture(gameId, 'discussion'))
    return views.get(gameId)!
  }
  function publish(view: GamePresentation) { views.set(view.gameId, view); persist(); listeners.get(view.gameId)?.forEach(fn => fn(clone(view))) }
  function stop(gameId: string) { clearInterval(streams.get(gameId)); streams.delete(gameId) }
  function start(view: GamePresentation) {
    stop(view.gameId)
    if (!view.preview || !listeners.get(view.gameId)?.size) return
    const speechId = view.preview.speechId
    const timer = setInterval(() => {
      const current = views.get(view.gameId)
      if (!current?.preview || current.preview.speechId !== speechId) { stop(view.gameId); return }
      current.preview.content = speech.slice(0, current.preview.content.length + 8)
      if (current.preview.content.length === speech.length) {
        current.messages.push({ id: speechId, seatNo: 3, round: 2, scope: 'PUBLIC', content: speech }); current.preview = null; current.completedSpeakers.push(3); current.version++
        current.records.push({ id: speechId, scope: 'PUBLIC', text: '03 AI-白狐已完成公开发言。' })
        stop(view.gameId)
      }
      publish(current)
    }, 240)
    streams.set(view.gameId, timer)
  }
  return {
    async get(gameId) { const view = ensure(gameId); return clone({ ...view, serverNow: Date.now() }) },
    async scenario(gameId, scenario) {
      const old = ensure(gameId)
      if (!gameScenarios.some(([id]) => id === scenario)) throw new Error('未知的演示场景。')
      const view = fixture(gameId, scenario); view.version = old.version + 1; publish(view); start(view); return clone(view)
    },
    async submit(gameId: string, command: GameCommand) {
      const view = ensure(gameId)
      const key = `${gameId}:${command.commandId}`, fingerprint = JSON.stringify(command)
      const prior = accepted.get(key)
      if (prior) { if (prior.fingerprint !== fingerprint) throw new Error('同一命令编号不能重复用于其他操作。'); return clone(prior.view) }
      if (view.version !== command.expectedVersion) throw new Error('阶段状态已更新，请重新同步后操作。')
      if (view.deadline !== null && Date.now() >= view.deadline) throw new Error('行动时间已结束，等待阶段更新。')
      const grant = view.permissions.find(p => p.type === command.actionType)
      if (!grant) throw new Error('当前阶段不能执行此操作。')
      const content = command.params.content?.trim()
      if (['SPEAK', 'WOLF_CHAT'].includes(command.actionType)) {
        if (!content || content.length > 500) throw new Error('发言需为 1 至 500 个字符。')
      } else if (command.params.abstain || command.params.giveUp) {
        if (!grant.allowEmpty) throw new Error('当前行动不能放弃。')
      } else if (grant.targets.length && !grant.targets.includes(command.params.targetSeatNo!)) throw new Error('该目标不可选择，请重新选择。')
      const next = clone(view); next.version++
      if (command.actionType === 'WOLF_CHAT') {
        next.wolfMessages.push({ id: command.commandId, seatNo: 7, round: 2, scope: 'WOLF', content: content! })
      } else {
        next.permissions = []; next.submitted = command.actionType === 'SPEAK' ? '发言已提交。' : command.actionType === 'VOTE' ? '投票已提交，等待结算。' : '行动已提交，等待阶段更新。'; next.deadline = null
        if (command.actionType === 'SPEAK') {
          next.messages.push({ id: command.commandId, seatNo: 7, round: 2, scope: 'PUBLIC', content: content! }); next.completedSpeakers.push(7)
          next.records.push({ id: command.commandId, scope: 'PUBLIC', text: '07 阿泽已完成公开发言。' })
        }
        if (command.actionType === 'VOTE') next.votedCount = 4
        if (command.actionType === 'SEER_INSPECT') {
          // This result is a fixed authorized fixture; it is never inferred from another player's identity.
          next.privateResult = { title: '真相浮现', content: `${command.params.targetSeatNo} 号 · 非狼人阵营（示例结果）` }
          next.privateRecords.push({ id: command.commandId, scope: 'PRIVATE', text: `第 2 夜：${next.privateResult.content}` })
        } else if (command.actionType !== 'SPEAK' && command.actionType !== 'VOTE') next.privateRecords.push({ id: command.commandId, scope: next.me.role === 'WEREWOLF' ? 'WOLF' : 'PRIVATE', text: `${grant.label}已提交${command.params.targetSeatNo ? ` · ${command.params.targetSeatNo} 号` : ''}。` })
      }
      accepted.set(key, { fingerprint, view: clone(next) }); publish(next); return clone(next)
    },
    subscribe(gameId, receive) {
      const view = ensure(gameId); const set = listeners.get(gameId) || new Set(); set.add(receive); listeners.set(gameId, set); start(view)
      return () => { set.delete(receive); if (!set.size) { listeners.delete(gameId); stop(gameId) } }
    },
  }
}
let storage: Storage | undefined
try { storage = globalThis.sessionStorage } catch { /* Browser storage is optional for the demo. */ }
export const gameAdapter = createMockGameAdapter(storage)
