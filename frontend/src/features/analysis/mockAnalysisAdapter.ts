import { createObservationFixture } from '@/features/observation/mockObservationAdapter'
import type { GameRole } from '@/features/game/types'
import type { AgentAnalysis, AnalysisAdapter, AnalysisSummary, DecisionTrace } from './types'

const profiles: Record<GameRole, Pick<AgentAnalysis, 'style' | 'goal' | 'tactics' | 'constraints'>> = {
  WEREWOLF: { style: ['谨慎伪装', '话题引导', '制造争议', '风险控制'], goal: '隐藏身份，维持阵营存活，争取讨论主动权。', tactics: ['转移焦点', '模糊立场', '利用信息差'], constraints: ['公开发言不能暴露同伴身份。', '行动仍须经过游戏规则校验。'] },
  SEER: { style: ['信息核对', '明确表态', '查验追踪'], goal: '结合查验与公开发言，帮助村民找到狼人。', tactics: ['公开查验', '对比票型', '建立可信信息'], constraints: ['只使用本人查验与已公开的信息。', '查验结果不等同于自动决定放逐。'] },
  WITCH: { style: ['谨慎救助', '观察票型', '资源管理'], goal: '保留关键资源，保护村民阵营的存活机会。', tactics: ['衡量救助', '核对信息', '谨慎表态'], constraints: ['技能使用次数和目标合法性由服务端判定。', '不能把其他角色的私有信息当作已知事实。'] },
  HUNTER: { style: ['稳健观察', '证据优先', '明确质疑'], goal: '结合公开记录找出疑点，在合法时机使用技能。', tactics: ['追问理由', '比对发言', '保留判断'], constraints: ['开枪须有服务端授予的资格。', '对候选目标的判断不是身份事实。'] },
  VILLAGER: { style: ['倾听发言', '核对证据', '协同投票'], goal: '从公开发言与票型中找出狼人，支持可信判断。', tactics: ['比较立场', '验证矛盾', '跟进证据'], constraints: ['没有查验能力，身份判断只能是估计。', '不能读取角色私有记录。'] },
  GUARD: { style: ['保护关键席位'], goal: '观察公开信息。', tactics: ['风险评估'], constraints: ['本地九人样例不包含守卫。'] },
}
function validate(gameId: string) {
  if (!/^demo(?:-[a-z0-9-]+)?$/i.test(gameId)) throw new Error('该对局的分析接口尚未接入，请打开本地分析示例。')
}
export function createAnalysisSummary(gameId = 'demo'): AnalysisSummary {
  return { gameId, status: 'COMPLETED', defaultSeat: 4, initialPosition: 820, provenance: '手工编写的示例估计，不代表真实胜率或模型预测。', estimates: [
    { at: 0, wolf: 50, village: 50 }, { at: 370, wolf: 44, village: 56 }, { at: 735, wolf: 38, village: 62 },
    { at: 820, wolf: 42, village: 58 }, { at: 995, wolf: 24, village: 76 }, { at: 1650, wolf: 5, village: 95 },
  ] }
}
export function createAgentAnalysis(gameId: string, seatNo: number): AgentAnalysis {
  const match = createObservationFixture(gameId), player = match.players.find(p => p.seatNo === seatNo)
  if (!player) throw new Error('没有该座位的 Agent。')
  const wolf = player.disclosedRole === 'WEREWOLF', seer = player.disclosedRole === 'SEER'
  const traces: DecisionTrace[] = match.events.filter(event => event.type === 'PLAYER_SPOKE' && event.seatNo === seatNo).map(event => {
    const round = match.events.filter(e => e.version <= event.version && e.change?.round).at(-1)?.change?.round || 1
    const target = wolf ? seatNo === 7 ? 1 : 9 : seer ? round === 1 ? 7 : 4 : seatNo === 3 ? 9 : seatNo === 5 ? 2 : 4
    const candidates = wolf ? [
      { id: 'A', label: `转移讨论至 ${target} 号`, detail: '追问对方的发言与投票依据。', impact: 25 },
      { id: 'B', label: '正面回应查验', detail: '解释本人立场，承担被追问的风险。', impact: 8 },
      { id: 'C', label: '保持中立，继续观察', detail: '减少冲突，但无法补充新的依据。', impact: -5 },
    ] : [
      { id: 'A', label: seer ? `公开 ${target} 号查验` : seatNo === 5 ? `听取 ${target} 号判断` : `追问 ${target} 号的依据`, detail: '结合可核对的信息，明确本轮关注目标。', impact: 18 },
      { id: 'B', label: '回顾上一轮票型', detail: '比较立场变化，寻找公开记录中的矛盾。', impact: 10 },
      { id: 'C', label: '继续倾听，暂缓判断', detail: '等待更多公开信息再做选择。', impact: -3 },
    ]
    return {
      id: `demo-trace-${seatNo}-${event.version}`, eventId: event.id, at: event.at, round, phase: '白天讨论',
      observations: wolf ? ['1 号公开了查验结果，当前讨论集中在狼人嫌疑。', `${target} 号的立场还可进一步追问，发言记录可以核对。`] : seer ? [`本人记录中有 ${target} 号的查验信息。`, '公开发言和前一轮票型可以作为补充依据。'] : [`${target} 号的发言需要与公开记录交叉核对。`, '其他玩家的身份声明尚不能直接视为事实。'],
      belief: { seatNo: target, label: wolf ? '被质疑可能性' : '狼人嫌疑', before: 45, after: 65, summary: '示例判断上升，仍需结合后续发言验证。' },
      candidates, chosenId: 'A', result: event.content, survival: wolf ? 78 : 72,
      identity: wolf ? [{ label: '被质疑', value: 65 }, { label: '未被质疑', value: 35 }] : seer ? [{ label: '本人查验', value: 100 }, { label: '未知', value: 0 }] : [{ label: '狼人嫌疑', value: 65 }, { label: '其他', value: 35 }],
      latencyMs: null, tokens: null,
    }
  })
  // `disclosedRole` 现在可选（未结束的对局不披露角色）。这是 mock，
  // `createAnalysisSummary` 只在已有角色时被调用，但类型上仍需处理缺失——
  // 缺角色时退回一个中性档案，而不是让 `undefined` 去索引。
  const role = player.disclosedRole ?? 'VILLAGER'
  return { gameId, seatNo, role, ...profiles[role], traces }
}
export const analysisAdapter: AnalysisAdapter = {
  async summary(gameId) { validate(gameId); return createAnalysisSummary(gameId) },
  async agent(gameId, seatNo) { validate(gameId); return createAgentAnalysis(gameId, seatNo) },
}
