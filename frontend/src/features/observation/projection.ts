import { isNightPhase } from './types'
import type { ObservationEvent, ObservationMatch, ObservationPhase } from './types'

export function projectObservation(match: ObservationMatch, position: number) {
  /*
   * 基线是**开局**，不是终局。
   *
   * `match.players` 带的是适配器最后一次读到的存活状态，真实回放里那就是终局；
   * 从它出发只能一路把人改成死亡，永远回不到"开局全员存活"（QA P1）。
   * 因此有出局事件的座位一律从存活开始，逐条向前应用淘汰；只有**整场都没有出局事件**的座位
   * 才沿用传入的标记——否则我们没有任何证据说明他开局是活的（例如事件流被截断时）。
   */
  const eliminatedSeats = new Set<number>()
  for (const event of match.events) if (event.change?.eliminated) eliminatedSeats.add(event.change.eliminated)
  const players = match.players.map(player => eliminatedSeats.has(player.seatNo) ? { ...player, alive: true } : { ...player })
  let phase: ObservationPhase = '等待开局', round = 1, speaker: number | null = null, winner: string | null = null
  const events = match.events.filter(event => event.at <= position).sort((a, b) => a.version - b.version)
  for (const event of events) {
    if (event.change?.phase) { phase = event.change.phase; speaker = null }
    if (event.change?.round) round = event.change.round
    if (event.change?.winner) winner = event.change.winner
    if (event.change?.eliminated) {
      const player = players.find(player => player.seatNo === event.change!.eliminated)
      if (player) player.alive = false
    }
    if (event.type === 'PLAYER_SPOKE') speaker = event.seatNo ?? null
  }
  return { players, phase, round, speaker, winner, events, atmosphere: isNightPhase(phase) ? 'night' as const : 'day' as const }
}

// Display filtering of an already authorized local fixture, never an authorization boundary.
export function eventInPerspective(event: ObservationEvent, match: ObservationMatch, perspective: string) {
  if (perspective === 'all') return true
  if (event.scope === 'PUBLIC') return true
  if (perspective === 'public') return false
  const seatNo = Number(perspective)
  return event.scope === 'PRIVATE' && event.recipient === seatNo || event.scope === 'WOLF' && match.players.some(player => player.seatNo === seatNo && player.disclosedRole === 'WEREWOLF')
}
