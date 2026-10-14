import type { ObservationAdapter, ObservationEvent, ObservationMatch } from './types'

export function createObservationFixture(gameId = 'demo'): ObservationMatch {
  const players: ObservationMatch['players'] = [
    { seatNo: 1, name: 'AI-白狐', avatar: 'fox', controller: 'AI', alive: true, disclosedRole: 'SEER' },
    { seatNo: 2, name: 'AI-夜鸦', avatar: 'owl', controller: 'AI', alive: true, disclosedRole: 'WITCH' },
    { seatNo: 3, name: 'AI-石楠', avatar: 'blue', controller: 'AI', alive: true, disclosedRole: 'HUNTER' },
    { seatNo: 4, name: 'AI-灰狼', avatar: 'wolf', controller: 'AI', alive: true, disclosedRole: 'WEREWOLF' },
    { seatNo: 5, name: 'AI-麦穗', avatar: 'green', controller: 'AI', alive: true, disclosedRole: 'VILLAGER' },
    { seatNo: 6, name: 'AI-松叶', avatar: 'green', controller: 'AI', alive: true, disclosedRole: 'VILLAGER' },
    { seatNo: 7, name: 'AI-赤焰', avatar: 'red', controller: 'AI', alive: true, disclosedRole: 'WEREWOLF' },
    { seatNo: 8, name: 'AI-晨露', avatar: 'blue', controller: 'AI', alive: true, disclosedRole: 'VILLAGER' },
    { seatNo: 9, name: 'AI-星尘', avatar: 'fox', controller: 'AI', alive: true, disclosedRole: 'WEREWOLF' },
  ]
  const entries: Omit<ObservationEvent, 'id' | 'version'>[] = [
    { at: 0, type: 'GAME_STARTED', title: '游戏开始', content: '九位 AI 围坐营火，经典九人局开始。', scope: 'PUBLIC', change: { phase: '狼人行动', round: 1 } },
    { at: 22, type: 'SEER_INSPECTED', title: '预言家查验', content: '1 号查验 7 号，记录结果为狼人阵营。', scope: 'PRIVATE', recipient: 1, seatNo: 1 },
    { at: 35, type: 'WOLF_TARGET_SELECTED', title: '狼人选择目标', content: '狼人阵营选择袭击 8 号。', scope: 'WOLF', seatNo: 4 },
    { at: 48, type: 'WITCH_ACTED', title: '女巫使用解药', content: '2 号使用解药救助 8 号。', scope: 'PRIVATE', recipient: 2, seatNo: 2 },
    { at: 70, type: 'PHASE_CHANGED', title: '第 1 天 · 白天开始', content: '昨夜平安，没有玩家出局。', scope: 'PUBLIC', change: { phase: '白天讨论', round: 1 } },
    { at: 95, type: 'PLAYER_SPOKE', title: '1 号 AI-白狐 发言', content: '我先报查验：7 号是狼人。请大家结合他的发言来判断，今天我会投给 7 号。', scope: 'PUBLIC', seatNo: 1, rationale: '示例决策摘要：公开本轮查验，给出明确投票方向。' },
    { at: 150, type: 'PLAYER_SPOKE', title: '7 号 AI-赤焰 发言', content: '我不认可这个查验。1 号还需要解释为什么第一晚选择我，大家先听完后面的发言。', scope: 'PUBLIC', seatNo: 7, rationale: '示例决策摘要：质疑查验来源，争取继续讨论。' },
    { at: 220, type: 'PLAYER_SPOKE', title: '5 号 AI-麦穗 发言', content: '目前 1 号给出的信息更明确。我会听取 2 号的判断，再决定今天的票。', scope: 'PUBLIC', seatNo: 5 },
    { at: 340, type: 'PHASE_CHANGED', title: '讨论结束 · 开始投票', content: '玩家结束本轮讨论，进入放逐投票。', scope: 'PUBLIC', change: { phase: '放逐投票' } },
    { at: 370, type: 'PLAYER_EXILED', title: '投票结果：7 号出局', content: '7 号获得最多票，被放逐出局。', scope: 'PUBLIC', change: { eliminated: 7 } },
    { at: 405, type: 'PHASE_CHANGED', title: '第 2 夜开始', content: '村庄入夜，存活玩家进入夜间行动。', scope: 'PUBLIC', change: { phase: '狼人行动', round: 2 } },
    { at: 445, type: 'WOLF_TARGET_SELECTED', title: '狼人选择目标', content: '狼人阵营选择袭击 6 号。', scope: 'WOLF', seatNo: 4 },
    { at: 470, type: 'SEER_INSPECTED', title: '预言家查验', content: '1 号查验 4 号，记录结果为狼人阵营。', scope: 'PRIVATE', recipient: 1, seatNo: 1 },
    { at: 515, type: 'PLAYER_KILLED', title: '6 号离开游戏', content: '天亮后公布：6 号在昨夜出局。', scope: 'PUBLIC', change: { eliminated: 6 } },
    { at: 545, type: 'PHASE_CHANGED', title: '第 2 天 · 白天开始', content: '营火再度点燃，请依次发表判断。', scope: 'PUBLIC', change: { phase: '白天讨论', round: 2 } },
    { at: 600, type: 'PLAYER_SPOKE', title: '2 号 AI-夜鸦 发言', content: '我认为第一天的投票方向可以延续。4 号昨天跟票很快，但没有给出自己的理由。', scope: 'PUBLIC', seatNo: 2, rationale: '示例决策摘要：结合前一天投票与发言寻找信息缺口。' },
    { at: 735, type: 'PLAYER_SPOKE', title: '1 号 AI-白狐 发言', content: '昨晚我查验了 4 号，结果是狼人。7 号昨天在关键问题上始终回避，4 号也没有解释自己的投票。今天我的票会投给 4 号，请大家核对前一天的发言记录。', scope: 'PUBLIC', seatNo: 1, rationale: '示例决策摘要：结合查验与公开投票记录，提出可核对的放逐目标。' },
    { at: 820, type: 'PLAYER_SPOKE', title: '4 号 AI-灰狼 发言', content: '我的投票有自己的依据。我更想知道 9 号为什么一直没有明确表态。', scope: 'PUBLIC', seatNo: 4 },
    { at: 950, type: 'PHASE_CHANGED', title: '第 2 天 · 放逐投票', content: '进入第二轮放逐投票。', scope: 'PUBLIC', change: { phase: '放逐投票' } },
    { at: 995, type: 'PLAYER_EXILED', title: '投票结果：4 号出局', content: '4 号被放逐出局。', scope: 'PUBLIC', change: { eliminated: 4 } },
    { at: 1060, type: 'PHASE_CHANGED', title: '第 3 夜开始', content: '存活玩家进入第三夜。', scope: 'PUBLIC', change: { phase: '狼人行动', round: 3 } },
    { at: 1150, type: 'PLAYER_KILLED', title: '8 号离开游戏', content: '天亮后公布：8 号在昨夜出局。', scope: 'PUBLIC', change: { eliminated: 8 } },
    { at: 1190, type: 'PHASE_CHANGED', title: '第 3 天 · 白天开始', content: '五位玩家继续讨论。', scope: 'PUBLIC', change: { phase: '白天讨论', round: 3 } },
    { at: 1270, type: 'PLAYER_SPOKE', title: '3 号 AI-石楠 发言', content: '前两轮的发言和票型已经很清楚了，今天重点听 9 号解释自己的选择。', scope: 'PUBLIC', seatNo: 3 },
    { at: 1500, type: 'PHASE_CHANGED', title: '第 3 天 · 放逐投票', content: '存活玩家开始最终一轮放逐投票。', scope: 'PUBLIC', change: { phase: '放逐投票' } },
    { at: 1650, type: 'PLAYER_EXILED', title: '投票结果：9 号出局', content: '9 号被放逐出局。', scope: 'PUBLIC', change: { eliminated: 9 } },
    { at: 1694, type: 'GAME_FINISHED', title: '村民阵营胜利', content: '记录中的三位狼人均已出局，对局结束。', scope: 'PUBLIC', change: { phase: '游戏结束', winner: '村民阵营胜利' } },
  ]
  return { gameId, roomName: '雾隐村 · AI 演练局', startedAt: '2026-10-10 20:15', duration: 1694, initialPosition: 756, players, events: entries.map((event, i) => ({ ...event, id: `demo-event-${i + 1}`, version: i + 1 })) }
}

export const observationAdapter: ObservationAdapter = {
  async get(gameId) {
    if (!/^demo(?:-[a-z0-9-]+)?$/i.test(gameId)) throw new Error('该对局尚未接入。请打开本地 AI 观战或回放示例。')
    return createObservationFixture(gameId)
  },
}
