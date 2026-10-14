import { beforeEach, expect, test, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useObservationStore } from '../src/stores/observation'
import { createObservationFixture, observationAdapter } from '../src/features/observation/mockObservationAdapter'
import { eventInPerspective, projectObservation } from '../src/features/observation/projection'
import type { ObservationBoard } from '../src/features/observation/types'

/**
 * 只有真实回放适配器能给出**权威历史棋盘**；mock 适配器没有历史能力（它的 fixture 就是全部记录，
 * 没有"权威"可言），因此这里单独注入一个会记录请求的 `boardAt`。
 *
 * 关键点：转发 `get` 时必须在**调用时**从模块对象上取，测试里的 `vi.spyOn(observationAdapter, 'get')`
 * 才仍然生效。
 */
const fakeBoard = vi.hoisted(() => ({
  boardAt: undefined as undefined | ((gameId: string, version: number, signal?: AbortSignal) => Promise<ObservationBoard | null>),
}))
vi.mock('../src/api/adapters', async importOriginal => {
  const actual = await importOriginal<typeof import('../src/api/adapters')>()
  return {
    ...actual,
    observationAdapter: () => ({
      get: (gameId: string, signal?: AbortSignal) => observationAdapter.get(gameId, signal),
      ...(fakeBoard.boardAt ? { boardAt: fakeBoard.boardAt } : {}),
    }),
  }
})

/** 等合并窗口过去（`boardDebounceMs` = 120）。 */
function afterDebounce() { return new Promise(resolve => setTimeout(resolve, 200)) }
function boardOf(fixture: ReturnType<typeof createObservationFixture>, overrides: Partial<ObservationBoard> = {}): ObservationBoard {
  return { version: 1, phase: '等待开局', round: 1, speaker: null, winner: null, finished: true,
    players: fixture.players.map(player => ({ ...player })), ...overrides }
}

beforeEach(() => { setActivePinia(createPinia()); vi.restoreAllMocks(); fakeBoard.boardAt = undefined })
test('backward seek reconstructs alive state without mutating the original record', () => {
  const match = createObservationFixture()
  expect(projectObservation(match, 756).players.filter(p => !p.alive).map(p => p.seatNo)).toEqual([6, 7])
  expect(projectObservation(match, 0).players.every(p => p.alive)).toBe(true)
  expect(match.players.every(p => p.alive)).toBe(true)
  expect(projectObservation(match, match.duration).winner).toBe('村民阵营胜利')
})
test('projection uses version ordering, not source array ordering', () => {
  const match = createObservationFixture()
  const expected = projectObservation(match, 756)
  match.events.reverse()
  expect(projectObservation(match, 756)).toEqual(expected)
})
test('淘汰状态从开局基线向前累加：终局的 alive 不会污染开局', () => {
  const fixture = createObservationFixture()
  // 真实适配器（QA P1）把**终局**的 alive 放进 players：4、6、7、8、9 号已出局，只剩 1、2、3、5。
  const terminal = { ...fixture, players: fixture.players.map(player => ({ ...player, alive: [1, 2, 3, 5].includes(player.seatNo) })) }

  expect(projectObservation(terminal, 0).players.filter(player => !player.alive)).toEqual([])
  expect(projectObservation(terminal, 369).players.filter(player => !player.alive)).toEqual([])
  expect(projectObservation(terminal, 370).players.filter(player => !player.alive).map(player => player.seatNo)).toEqual([7])
  expect(projectObservation(terminal, terminal.duration).players.filter(player => !player.alive).map(player => player.seatNo)).toEqual([4, 6, 7, 8, 9])
})
test('整场没有出局事件的座位沿用传入的存活标记，不凭空说他还活着', () => {
  const fixture = createObservationFixture()
  // 事件流被截断：10 号（虚构座位）已出局，但记录里没有他的出局事件。
  const players = [...fixture.players, { seatNo: 10, name: 'p_10', avatar: 'green' as const, controller: 'AI' as const, alive: false }]
  const truncated = { ...fixture, players }
  expect(projectObservation(truncated, 0).players.find(player => player.seatNo === 10)?.alive).toBe(false)
  expect(projectObservation(truncated, 0).players.filter(player => !player.alive).map(player => player.seatNo)).toEqual([10])
})
test('fixture uses the classic nine AI roles, positive versions and ordered timestamps', () => {
  const match = createObservationFixture()
  expect(match.players).toHaveLength(9)
  expect(match.players.every(p => p.controller === 'AI')).toBe(true)
  expect(match.players.filter(p => p.disclosedRole === 'WEREWOLF')).toHaveLength(3)
  expect(match.events.map(e => e.version)).toEqual(match.events.map((_, i) => i + 1))
  expect(match.events.map(e => e.at)).toEqual(match.events.map(e => e.at).sort((a,b) => a-b))
})
test('player display perspectives include only public, own private and faction records', () => {
  const match = createObservationFixture()
  const seer = match.events.filter(e => eventInPerspective(e, match, '1'))
  expect(seer.some(e => e.scope === 'WOLF')).toBe(false)
  expect(seer.filter(e => e.scope === 'PRIVATE').every(e => e.recipient === 1)).toBe(true)
  expect(match.events.filter(e => eventInPerspective(e, match, '4')).some(e => e.scope === 'WOLF')).toBe(true)
  expect(match.events.filter(e => eventInPerspective(e, match, 'public')).every(e => e.scope === 'PUBLIC')).toBe(true)
})
test('speed, pause, seeking and completion obey playback boundaries', async () => {
  const store = useObservationStore()
  await store.load('demo')
  store.speed = 2; store.toggle(); store.tick(5)
  expect(store.position).toBe(766)
  store.toggle(); store.tick(5)
  expect(store.position).toBe(766)
  store.seek(Infinity); expect(store.position).toBe(766)
  store.seek(-10); expect(store.position).toBe(0)
  store.seek(1693); store.toggle(); store.tick(5)
  expect(store.position).toBe(1694); expect(store.playing).toBe(false)
  store.toggle(); expect(store.position).toBe(0); expect(store.playing).toBe(true)
})
test('event stepping stops at endpoints and filter does not change board state', async () => {
  const store = useObservationStore()
  await store.load('demo')
  const state = store.view
  store.scope = 'PRIVATE'
  expect(store.view).toEqual(state)
  expect(store.visibleEvents.every(e => e.scope === 'PRIVATE')).toBe(true)
  store.seek(0); store.step(1); expect(store.position).toBe(22)
  store.step(-1); expect(store.position).toBe(22)
  store.perspective = 'public'; expect(store.visibleEvents).toHaveLength(0)
})
test('unknown match ID reports failure instead of substituting a demo', async () => {
  const store = useObservationStore()
  await store.load('real-game-unknown')
  expect(store.match).toBeNull(); expect(store.error).toContain('尚未接入'); expect(store.loading).toBe(false)
})
test('late load responses and disposed requests cannot overwrite a newer route', async () => {
  let finish: (match: ReturnType<typeof createObservationFixture>) => void = () => {}
  vi.spyOn(observationAdapter, 'get').mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
  const store = useObservationStore(), first = store.load('demo-first')
  await store.load('demo-next'); finish(createObservationFixture('demo-first')); await first
  expect(store.match?.gameId).toBe('demo-next')
  vi.spyOn(observationAdapter, 'get').mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
  const pending = store.load('demo-pending'); store.dispose(); finish(createObservationFixture('demo-pending')); await pending
  expect(store.match).toBeNull(); expect(store.loading).toBe(false)
})

test('有权威棋盘时用它渲染：回到开局显示服务端的开局棋盘', async () => {
  const fixture = createObservationFixture(), terminalAlive = [1, 2, 3, 5]
  // 对局视图（终局）：只剩 1、2、3、5 号存活。
  vi.spyOn(observationAdapter, 'get').mockResolvedValueOnce({ ...fixture,
    players: fixture.players.map(player => ({ ...player, alive: terminalAlive.includes(player.seatNo) })) })
  const seen: number[] = []
  fakeBoard.boardAt = async (_gameId, version) => {
    seen.push(version)
    return boardOf(fixture, version === 1
      ? { version, phase: '等待开局', round: 1 }
      : { version, phase: '游戏结束', round: 3, winner: '村民阵营胜利',
          players: fixture.players.map(player => ({ ...player, alive: terminalAlive.includes(player.seatNo) })) })
  }
  const store = useObservationStore()
  await store.load('demo')
  await afterDebounce()

  // 初始位置是 fixture 的 initialPosition（756 秒），对应版本 17。
  expect(seen).toEqual([17])
  expect(store.view?.players.filter(player => player.alive)).toHaveLength(4)

  // QA 复现路径：点"回到开局"。
  store.seek(0)
  await afterDebounce()

  expect(seen).toEqual([17, 1])
  expect(store.view?.players.filter(player => !player.alive)).toEqual([])
  expect(store.view?.players.filter(player => player.alive)).toHaveLength(9)
  expect(store.view?.phase).toBe('等待开局')
})

test('棋盘缺失角色时沿用整局的披露结果，拖动时间轴不改变谁的身份可见', async () => {
  const fixture = createObservationFixture()
  vi.spyOn(observationAdapter, 'get').mockResolvedValueOnce(fixture)
  // 历史快照在早期版本里没有角色（服务端按"当时未结束"脱敏）。
  fakeBoard.boardAt = async (_gameId, version) => boardOf(fixture, { version,
    players: fixture.players.map(player => ({ ...player, disclosedRole: undefined })) })
  const store = useObservationStore()
  await store.load('demo')
  store.seek(0)
  await afterDebounce()

  expect(store.view?.players.filter(player => player.disclosedRole === 'WEREWOLF')).toHaveLength(3)
})

test('拖动时间轴合并请求，迟到的棋盘响应不能覆盖更新的棋盘', async () => {
  const fixture = createObservationFixture(), terminalAlive = [1, 5]
  vi.spyOn(observationAdapter, 'get').mockResolvedValueOnce(fixture)
  const pending: { version: number; resolve: (board: ObservationBoard | null) => void }[] = []
  fakeBoard.boardAt = (_gameId, version) => new Promise(resolve => { pending.push({ version, resolve }) })
  const store = useObservationStore()
  await store.load('demo')

  // 一次拖动经过中间位置后停在开局：只应发出**一次**请求。
  store.seek(370); store.seek(0)
  await afterDebounce()
  expect(pending.map(entry => entry.version)).toEqual([1])

  // 用户随即又拖到结局：新请求发出，旧请求（版本 1）还悬着。
  store.seek(fixture.duration)
  await afterDebounce()
  expect(pending.map(entry => entry.version)).toEqual([1, 27])

  pending[1]!.resolve(boardOf(fixture, { version: 27, phase: '游戏结束', round: 3, winner: '村民阵营胜利',
    players: fixture.players.map(player => ({ ...player, alive: terminalAlive.includes(player.seatNo) })) }))
  await afterDebounce()
  expect(store.board?.version).toBe(27)
  expect(store.view?.players.filter(player => player.alive)).toHaveLength(2)

  // 旧响应后到：它的版本早于当前时间轴位置，覆盖上去就会把棋盘退回开局。
  pending[0]!.resolve(boardOf(fixture, { version: 1, phase: '猎人开枪', round: 2 }))
  await afterDebounce()

  expect(store.board?.version).toBe(27)
  expect(store.view?.phase).toBe('游戏结束')
  expect(store.view?.players.filter(player => player.alive)).toHaveLength(2)
})

test('请求的版本永远落在 [1, snapshot.version]：越界与 NaN 位置不发请求', async () => {
  const fixture = createObservationFixture()
  // 服务端只确认到版本 5，而事件里有更高的版本号：请求必须钳回快照版本。
  vi.spyOn(observationAdapter, 'get').mockResolvedValueOnce({ ...fixture,
    snapshot: { version: 5, phase: '等待开局', round: 1, speaker: null, winner: null, finished: true } })
  const seen: number[] = []
  fakeBoard.boardAt = async (_gameId, version) => { seen.push(version); return boardOf(fixture, { version, phase: '等待开局' }) }
  const store = useObservationStore()
  await store.load('demo')
  await afterDebounce()
  store.seek(0)
  await afterDebounce()
  store.seek(fixture.duration)
  await afterDebounce()

  expect(seen).toEqual([5, 1, 5])
  expect(seen.every(version => Number.isInteger(version) && version >= 1 && version <= 5)).toBe(true)

  const before = seen.length
  store.position = Number.NaN          // 位置不是有限数时不得把 NaN 拼进 URL
  await afterDebounce()
  expect(seen).toHaveLength(before)
})

test('没有历史能力的适配器退回本地投影，界面不留空', async () => {
  const store = useObservationStore()
  await store.load('demo')
  store.seek(756)
  await afterDebounce()
  expect(store.board).toBeNull()
  expect(store.view?.players.filter(player => !player.alive).map(player => player.seatNo)).toEqual([6, 7])
})
