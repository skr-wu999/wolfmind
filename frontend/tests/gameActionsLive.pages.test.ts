// @vitest-environment jsdom
import { afterAll, afterEach, beforeAll, expect, test, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import GamePage from '../src/pages/GamePage.vue'
import RoomWaitingPage from '../src/pages/RoomWaitingPage.vue'
import { useGameStore } from '../src/stores/game'
import { useSeatStore, wireAdapterProviders } from '../src/stores/session'
import { setSessionToken, sessionToken, setSessionExpiryHandler } from '../src/api/config'
import { request } from '../src/api/apiClient'
import type { GameView, RoomView } from '../src/api/restTypes'
import { setCurrentUserProvider } from '../src/api/adapters'
import { installJsdomStubs, installNodeWebSocket, installStorage } from './support/jsdomStubs'

const BASE = process.env.WOLFMIND_E2E_BASE ?? 'http://127.0.0.1:18080'
type Account = { token: string; userId: string; seatNo: number; role: string }
type Match = { gameId: string; players: Account[] }
let wrapper: VueWrapper | undefined

async function json<T>(path: string, token?: string, body?: unknown): Promise<T> {
  const response = await fetch(`${BASE}/api/v1${path}`, {
    method: body === undefined ? 'GET' : 'POST',
    headers: { 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID(), ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const value = response.status === 204 ? undefined : await response.json()
  if (!response.ok) throw new Error(`${path}: ${response.status} ${JSON.stringify(value)}`)
  return value as T
}

async function humans(): Promise<Match> {
  const players: Account[] = []
  for (let seatNo = 1; seatNo <= 9; seatNo++) {
    const credentials = { username: `act_${crypto.randomUUID().slice(0, 8)}_${seatNo}`, password: 'correct-horse' }
    await json('/auth/register', undefined, credentials)
    const loggedIn = await json<{ sessionToken: string; userId: string }>('/auth/login', undefined, credentials)
    players.push({ token: loggedIn.sessionToken, userId: loggedIn.userId, seatNo, role: '' })
  }
  const owner = players[0]!
  const room = await json<{ roomId: string }>('/rooms', owner.token, { name: '真人技能交互验收', config: { playerCount: 9 } })
  for (const player of players.slice(1)) await json(`/rooms/${room.roomId}/join`, player.token, {})
  for (const player of players) await json(`/rooms/${room.roomId}/ready`, player.token, {})
  const started = await json<{ gameId: string }>(`/rooms/${room.roomId}/games`, owner.token, { autoFillAi: false })
  for (const player of players) {
    const view = await json<GameView>(`/games/${started.gameId}`, player.token)
    player.role = view.me!.role!
  }
  return { gameId: started.gameId, players }
}

async function until(predicate: () => boolean, timeout = 8000): Promise<void> {
  const deadline = Date.now() + timeout
  while (!predicate() && Date.now() < deadline) {
    await flushPromises()
    await new Promise(resolve => setTimeout(resolve, 30))
  }
  expect(predicate()).toBe(true)
  await flushPromises()
}

async function asPlayer(match: Match, player: Account) {
  wrapper?.unmount()
  setSessionToken(player.token)
  const pinia = createPinia()
  setActivePinia(pinia)
  useSeatStore(pinia).set(player.seatNo)
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/', component: { template: '<div>首页</div>' } },
    { path: '/lobby', name: 'lobby', component: { template: '<div>大厅</div>' } },
    { path: '/login', name: 'login', component: { template: '<div>登录</div>' } },
    { path: '/games/:gameId', component: GamePage, meta: { requiresAuth: true } },
  ] })
  wireAdapterProviders(() => { void router.replace({ name: 'login', query: { redirect: router.currentRoute.value.fullPath } }) })
  await router.push(`/games/${match.gameId}`)
  wrapper = mount(GamePage, { attachTo: document.body, global: { plugins: [pinia, router] } })
  const store = useGameStore(pinia)
  await until(() => !!store.view && store.connection === 'local')
  expect(wrapper.find('#game-scenario').exists()).toBe(false)
  expect(wrapper.text()).not.toContain('模拟断线')
  expect(wrapper.text()).not.toContain('在线同步尚未接入')
  return { store, router, pinia }
}

async function click(text: string) {
  const findButton = () => wrapper!.findAll('button').find(candidate => candidate.text() === text || (candidate.find('strong').exists() && candidate.find('strong').text() === text))
  await until(() => !!findButton() && findButton()!.attributes('disabled') === undefined)
  const button = findButton()
  expect(button, text).toBeDefined()
  expect(button!.attributes('disabled'), text).toBeUndefined()
  await button!.trigger('click')
  await flushPromises()
}

async function chooseTarget(seatNo: number) {
  await until(() => wrapper!.findAll('.game-targets button').some(button =>
    button.attributes('aria-label')?.startsWith(`选择 ${seatNo} 号 `) && button.attributes('disabled') === undefined))
  const target = wrapper!.findAll('.game-targets button').find(button => button.attributes('aria-label')?.startsWith(`选择 ${seatNo} 号 `))
  expect(target, `可选座位 ${seatNo}`).toBeDefined()
  await target!.trigger('click')
  expect(target!.attributes('aria-pressed')).toBe('true')
}

async function confirmAction() {
  await wrapper!.get('.game-confirm').trigger('click')
  await flushPromises()
  expect(wrapper!.find('.game-dialog-actions').exists()).toBe(true)
  await click('确认提交')
  await until(() => !useGameStore().busy)
}

async function action(match: Match, player: Account, type: string, params = {}) {
  const view = await json<GameView>(`/games/${match.gameId}`, player.token)
  await json(`/games/${match.gameId}/actions`, player.token, { actionType: type, params, expectedVersion: view.version })
}

beforeAll(() => { installStorage(); installJsdomStubs(); installNodeWebSocket() })
afterEach(() => { wrapper?.unmount(); wrapper = undefined; document.body.innerHTML = ''; setSessionExpiryHandler() })
afterAll(() => vi.unstubAllGlobals())

test.each(['save', 'poison', 'shoot', 'give-up', 'self-save-forbidden'] as const)('真实页面提交狼人、预言家与女巫行动：%s', async mode => {
  const match = await humans()
  const wolves = match.players.filter(player => player.role === 'WEREWOLF')
  const seer = match.players.find(player => player.role === 'SEER')!
  const witch = match.players.find(player => player.role === 'WITCH')!
  const hunter = match.players.find(player => player.role === 'HUNTER')!
  const villagers = match.players.filter(player => player.role === 'VILLAGER')
  const victim = mode === 'self-save-forbidden' ? witch : mode === 'shoot' || mode === 'give-up' ? hunter : villagers[0]!

  let { store } = await asPlayer(match, wolves[0]!)
  expect(wrapper!.find('#wolf-message').exists()).toBe(false)
  await chooseTarget(victim.seatNo)
  await confirmAction()
  expect(store.error).toBe('')
  const wolfView = await json<GameView>(`/games/${match.gameId}`, wolves[0]!.token)
  expect(wolfView.permissions.some(permission => permission.type === 'WOLF_KILL')).toBe(false)
  for (const wolf of wolves.slice(1)) await action(match, wolf, 'SKIP')

  ;({ store } = await asPlayer(match, seer))
  await chooseTarget(wolves[0]!.seatNo)
  await confirmAction()
  expect(store.error).toBe('')
  expect(store.view!.privateResult?.content).toContain('狼人')
  expect(wrapper!.get('.game-private-result').text()).toContain('狼人')

  ;({ store } = await asPlayer(match, witch))
  expect(wrapper!.find('.witch-potions').exists()).toBe(true)
  const rescue = wrapper!.get('.potion-row small').text()
  if (mode === 'self-save-forbidden') {
    expect(rescue).toContain('今晚没有可救助目标')
    expect(wrapper!.findAll('button').find(button => button.text() === '使用解药')!.attributes('disabled')).toBeDefined()
  } else expect(rescue).toContain(`${victim.seatNo} 号`)
  expect(rescue).not.toContain('北巷')
  if (mode === 'save') {
    await click('使用解药')
    expect(wrapper!.get('.game-dialog-actions').element.parentElement!.textContent).toContain(`${victim.seatNo} 号`)
    await click('确认提交')
  } else if (mode === 'poison') {
    await click('选择目标')
    await chooseTarget(villagers[1]!.seatNo)
    await confirmAction()
  } else {
    await click('今晚不用药')
    await click('确认提交')
  }
  await until(() => !store.busy)
  expect(store.error).toBe('')
  const witchView = await json<GameView>(`/games/${match.gameId}`, witch.token)
  expect(witchView.permissions.some(permission => permission.type.startsWith('WITCH_'))).toBe(false)
  const events = await json<{ events: { type: string; payload: Record<string, unknown> }[] }>(`/games/${match.gameId}/replay?from=1&to=${witchView.version}`, witch.token)
  const witchEvent = events.events.find(event => event.type === (mode === 'save' ? 'WITCH_SAVED' : mode === 'poison' ? 'WITCH_POISONED' : 'WITCH_PASSED'))
  expect(witchEvent).toBeDefined()
  if (mode === 'save') expect(Number(witchEvent!.payload.targetSeatNo)).toBe(victim.seatNo)

  if (mode === 'shoot' || mode === 'give-up') {
    ;({ store } = await asPlayer(match, hunter))
    expect(store.view!.phase).toBe('ROLE_REACTION')
    if (mode === 'shoot') await chooseTarget(villagers[0]!.seatNo)
    else await click('放弃开枪')
    await confirmAction()
    expect(store.error).toBe('')
    const after = await json<GameView>(`/games/${match.gameId}`, hunter.token)
    expect(after.permissions.some(permission => permission.type === 'HUNTER_SHOOT')).toBe(false)
    expect(after.phase).not.toBe('HUNTER_SHOOT')
    if (mode === 'give-up') {
      expect(store.view!.phase).toBe('LAST_WORDS')
      expect(wrapper!.get('#phase-title').text()).toContain('遗言')
      await click('发言')
      await wrapper!.get('#speech-content').setValue('这是猎人的遗言。')
      await wrapper!.get('.game-speech-form').trigger('submit')
      await until(() => !store.busy)
      expect(store.error).toBe('')
      expect(store.view!.messages.some(message => message.content === '这是猎人的遗言。')).toBe(true)
    }
  }
}, 30000)

test('真实公开发言和投票通过页面提交，已投票的座位禁用重复投票', async () => {
  const match = await humans()
  for (const wolf of match.players.filter(player => player.role === 'WEREWOLF')) await action(match, wolf, 'SKIP')
  const seer = match.players.find(player => player.role === 'SEER')!
  await action(match, seer, 'SEER_INSPECT', { targetSeatNo: seer.seatNo === 1 ? 2 : 1 })
  await action(match, match.players.find(player => player.role === 'WITCH')!, 'WITCH_PASS')
  const first = match.players[0]!
  const { store } = await asPlayer(match, first)
  await click('发言')
  await wrapper!.get('#speech-content').setValue('我的判断来自当前公开发言。')
  await wrapper!.get('.game-speech-form').trigger('submit')
  await until(() => !store.busy)
  expect(store.error).toBe('')
  expect(store.view!.messages.some(message => message.content === '我的判断来自当前公开发言。')).toBe(true)
  for (const player of match.players.slice(1)) await action(match, player, 'SPEAK', { content: `${player.seatNo} 号公开发言。` })
  await until(() => store.view!.phase === 'DAY_VOTING')
  await chooseTarget(2)
  await confirmAction()
  expect(store.error).toBe('')
  expect(store.view!.permissions.some(permission => permission.type === 'VOTE')).toBe(false)
  expect(wrapper!.find('.game-confirm').exists()).toBe(false)
  const view = await json<GameView>(`/games/${match.gameId}`, first.token)
  expect(view.votes?.find(vote => vote.voterSeat === first.seatNo)?.targetSeatNo).toBe(2)
  await asPlayer(match, match.players[1]!)
  await click('弃票')
  await confirmAction()
  const abstained = await json<GameView>(`/games/${match.gameId}`, match.players[1]!.token)
  expect(abstained.votes?.find(vote => vote.voterSeat === 2)?.abstain).toBe(true)
}, 30000)

test('会话被服务端注销后，真实 401 清凭据和座位并保留对局回跳地址', async () => {
  const match = await humans()
  const owner = match.players[0]!
  const { router, pinia } = await asPlayer(match, owner)
  await json('/auth/logout', owner.token, {})
  await expect(request('/api/v1/auth/me')).rejects.toMatchObject({ status: 401 })
  await until(() => router.currentRoute.value.name === 'login')
  expect(sessionToken()).toBeNull()
  expect(useSeatStore(pinia).seatNo).toBeNull()
  expect(router.currentRoute.value.query.redirect).toBe(`/games/${match.gameId}`)
})

test('无效会话的真实 WebSocket 认证失败也返回登录页', async () => {
  const match = await humans()
  const owner = match.players[0]!
  await json('/auth/logout', owner.token, {})
  const pinia = createPinia()
  setActivePinia(pinia)
  setSessionToken(owner.token)
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/games/:gameId', component: GamePage },
    { path: '/login', name: 'login', component: { template: '<div>登录</div>' } },
    { path: '/lobby', component: { template: '<div>大厅</div>' } },
  ] })
  wireAdapterProviders(() => { void router.replace({ name: 'login', query: { redirect: router.currentRoute.value.fullPath } }) })
  await router.push(`/games/${match.gameId}`)
  wrapper = mount(GamePage, { global: { plugins: [pinia, router] } })
  await until(() => router.currentRoute.value.name === 'login')
  expect(sessionToken()).toBeNull()
})

test('加载期间禁用操作，网络失败保留会话，重试后恢复真实对局', async () => {
  const match = await humans()
  const { store } = await asPlayer(match, match.players[0]!)
  const nativeFetch = globalThis.fetch
  let fail!: (error: Error) => void
  vi.stubGlobal('fetch', (url: string, init: RequestInit) => String(url).endsWith(`/games/${match.gameId}`)
    ? new Promise<Response>((_resolve, reject) => { fail = reject }) : nativeFetch(url, init))
  const loading = store.load(match.gameId)
  await flushPromises()
  expect(wrapper!.get('.game-loading').attributes('aria-busy')).toBe('true')
  expect(wrapper!.find('.game-confirm').exists()).toBe(false)
  fail(new TypeError('模拟网络中断'))
  await loading
  await flushPromises()
  expect(wrapper!.get('.game-error').attributes('role')).toBe('alert')
  expect(wrapper!.get('.game-error').text()).toContain('无法连接到后端')
  expect(wrapper!.text()).not.toContain('打开示例对局')
  expect(sessionToken()).toBe(match.players[0]!.token)
  vi.stubGlobal('fetch', nativeFetch)
  await click('重试')
  await until(() => !!store.view && store.connection === 'local')
  expect(wrapper!.findAll('.game-seat')).toHaveLength(9)
  expect(store.error).toBe('')
})

test('房主从等待房准备、选择 AI 补位并开局，其他成员刷新后进入同一对局', async () => {
  async function register(suffix: string) {
    const credentials = { username: `start_${crypto.randomUUID().slice(0, 8)}_${suffix}`, password: 'correct-horse' }
    await json('/auth/register', undefined, credentials)
    return json<{ userId: string; sessionToken: string }>('/auth/login', undefined, credentials)
  }
  const owner = await register('owner')
  const guest = await register('guest')
  const room = await json<RoomView>('/rooms', owner.sessionToken, { name: '页面开局验收', config: { playerCount: 9 } })
  await json(`/rooms/${room.roomId}/join`, guest.sessionToken, {})
  await json(`/rooms/${room.roomId}/ready`, guest.sessionToken, {})
  async function waiting(user: typeof owner) {
    wrapper?.unmount()
    setSessionToken(user.sessionToken)
    setCurrentUserProvider(() => user.userId)
    const pinia = createPinia()
    setActivePinia(pinia)
    const router = createRouter({ history: createMemoryHistory(), routes: [
      { path: '/rooms/:roomId', component: RoomWaitingPage },
      { path: '/games/:gameId', component: GamePage },
      { path: '/lobby', component: { template: '<div>大厅</div>' } },
      { path: '/', component: { template: '<div>首页</div>' } },
    ] })
    await router.push(`/rooms/${room.roomId}`)
    wrapper = mount(RouterView, { global: { plugins: [pinia, router] } })
    await until(() => wrapper!.text().includes('页面开局验收') || router.currentRoute.value.path.startsWith('/games/'))
    return router
  }
  const ownerRouter = await waiting(owner)
  expect(wrapper!.get('.start-game-button').attributes('disabled')).toBeDefined()
  await click('准备')
  await until(() => wrapper!.text().includes('你已准备'))
  expect(wrapper!.get('.start-game-button').attributes('disabled')).toBeDefined()
  await wrapper!.get('.room-ai-fill input').setValue(true)
  expect(wrapper!.get('.start-game-button').attributes('disabled')).toBeUndefined()
  await click('开始游戏')
  await until(() => ownerRouter.currentRoute.value.path.startsWith('/games/'))
  const gameId = String(ownerRouter.currentRoute.value.params.gameId)
  const view = await json<GameView>(`/games/${gameId}`, owner.sessionToken)
  expect(view.players.filter(player => player.controller.type === 'AI')).toHaveLength(7)
  expect(view.me!.seatNo).toBe(1)
  const guestRouter = await waiting(guest)
  await until(() => guestRouter.currentRoute.value.path === `/games/${gameId}`)
  const guestView = await json<GameView>(`/games/${gameId}`, guest.sessionToken)
  expect(guestView.me!.seatNo).toBe(2)
}, 30000)
