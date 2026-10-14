// @vitest-environment jsdom
import { afterAll, afterEach, beforeAll, expect, test, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import ObservationPage from '../src/pages/ObservationPage.vue'
import { useObservationStore } from '../src/stores/observation'
import { setSessionToken } from '../src/api/config'
import type { GameView } from '../src/api/restTypes'
import { installJsdomStubs, installNodeWebSocket, installStorage } from './support/jsdomStubs'

const base = process.env.WOLFMIND_E2E_BASE ?? 'http://127.0.0.1:18080'
let wrapper: VueWrapper | undefined
let connections: WebSocket[] = []
async function json<T>(path: string, token?: string, body?: unknown): Promise<T> {
  const response = await fetch(`${base}/api/v1${path}`, { method: body === undefined ? 'GET' : 'POST',
    headers: { 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID(), ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body) })
  const result = await response.json()
  if (!response.ok) throw new Error(`${path}: ${response.status} ${JSON.stringify(result)}`)
  return result as T
}
async function account() {
  const credentials = { username: `watch_${crypto.randomUUID().slice(0, 8)}`, password: 'correct-horse' }
  await json('/auth/register', undefined, credentials)
  return json<{ sessionToken: string }>('/auth/login', undefined, credentials)
}
async function match() {
  const owner = await account()
  const room = await json<{ roomId: string }>('/rooms', owner.sessionToken, { name: '公开观战验收', config: { playerCount: 9 } })
  await json(`/rooms/${room.roomId}/ready`, owner.sessionToken, {})
  const started = await json<{ gameId: string }>(`/rooms/${room.roomId}/games`, owner.sessionToken, { autoFillAi: true })
  return { gameId: started.gameId, token: owner.sessionToken }
}
async function until(predicate: () => boolean, timeout = 8000) {
  const deadline = Date.now() + timeout
  while (!predicate() && Date.now() < deadline) { await flushPromises(); await new Promise(done => setTimeout(done, 30)) }
  expect(predicate()).toBe(true); await flushPromises()
}
async function watchGame(gameId: string, token: string | null) {
  setSessionToken(token)
  const pinia = createPinia(); setActivePinia(pinia)
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/watch/:gameId', component: ObservationPage }] })
  await router.push(`/watch/${gameId}`)
  wrapper = mount(ObservationPage, { props: { mode: 'watch' }, attachTo: document.body, global: { plugins: [pinia, router] } })
  const store = useObservationStore(pinia)
  await until(() => !store.loading)
  return store
}
beforeAll(() => {
  installStorage(); installJsdomStubs(); installNodeWebSocket()
  const Native = globalThis.WebSocket
  vi.stubGlobal('WebSocket', class extends Native { constructor(url: string | URL, protocols?: string | string[]) { super(url, protocols); connections.push(this) } })
})
afterEach(() => { wrapper?.unmount(); wrapper = undefined; connections = []; document.body.innerHTML = '' })
afterAll(() => vi.unstubAllGlobals())

test('匿名公开观战展示真实记录与登录提示，隐藏播放器和私有筛选', async () => {
  const game = await match(), store = await watchGame(game.gameId, null)
  expect(store.connection).toBe('login-required')
  expect(store.match?.events.length).toBeGreaterThan(0)
  expect(store.match?.events.every(event => event.scope === 'PUBLIC')).toBe(true)
  expect(store.match?.players.every(player => player.disclosedRole === undefined)).toBe(true)
  expect(wrapper!.text()).toContain('8 / 9')
  expect(wrapper!.text()).toContain('登录后实时同步')
  expect(wrapper!.find('.observation-controls').exists()).toBe(false)
  expect(wrapper!.find('option[value="WOLF"]').exists()).toBe(false)
  expect(wrapper!.text()).not.toContain('本地观战示例')
  expect(wrapper!.text()).not.toContain('共 3 天')
  expect(connections).toHaveLength(0)
})
test('参与者打开公开观战收到明确返回对局入口，不读取私有事件', async () => {
  const game = await match(), store = await watchGame(game.gameId, game.token)
  expect(store.match).toBeNull()
  expect(store.error).toContain('返回对局页面')
  expect(wrapper!.find(`a[href="/games/${game.gameId}"]`).exists()).toBe(true)
  expect(connections).toHaveLength(0)
})
test('其他账号公开观战经历物理断线，自动补齐并接收人机终局完整记录', async () => {
  const game = await match(), observer = await account()
  const store = await watchGame(game.gameId, observer.sessionToken)
  await until(() => store.connection === 'live')
  expect(store.match?.players.every(player => player.disclosedRole === undefined)).toBe(true)
  expect(store.match?.events.every(event => event.scope === 'PUBLIC')).toBe(true)
  const firstVersion = store.match!.snapshot!.version
  connections[0]!.close()
  await until(() => store.connection === 'reconnecting')
  const deadline = Date.now() + 60_000
  let current: GameView | undefined, humanActions = 0
  while (Date.now() < deadline) {
    current = await json<GameView>(`/games/${game.gameId}`, game.token)
    if (current.status === 'FINISHED') break
    if (current.permissions.length) {
      const speech = current.permissions.some(p => p.type === 'SPEAK')
      const response = await fetch(`${base}/api/v1/games/${game.gameId}/actions`, { method: 'POST',
        headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${game.token}`, 'Idempotency-Key': crypto.randomUUID() },
        body: JSON.stringify({ expectedVersion: current.version, actionType: speech ? 'SPEAK' : 'SKIP', params: speech ? { content: '真人发言可被公开观战接收。' } : {} }) })
      if (response.ok) humanActions++
      else expect(['GAME_VERSION_CONFLICT', 'GAME_BUSY']).toContain((await response.json()).code)
    }
    if (!store.match!.snapshot!.finished) {
      expect(store.match?.players.every(player => player.disclosedRole === undefined)).toBe(true)
      expect(store.match?.events.every(event => event.scope === 'PUBLIC')).toBe(true)
    }
    await new Promise(done => setTimeout(done, 100))
  }
  expect(current?.status).toBe('FINISHED')
  await until(() => store.connection === 'finished')
  expect(humanActions).toBeGreaterThan(0)
  expect(connections.length).toBeGreaterThan(1)
  expect(store.match?.snapshot?.version).toBe(current?.version)
  expect(store.match!.snapshot!.version).toBeGreaterThan(firstVersion)
  expect(store.match?.players.every(player => player.disclosedRole !== undefined)).toBe(true)
  expect(store.match?.events.filter(event => event.type === 'ROLE_ASSIGNED')).toHaveLength(9)
  expect(store.match?.events.some(event => event.scope === 'WOLF')).toBe(true)
  expect(store.match?.events.filter(event => event.type === 'GAME_FINISHED')).toHaveLength(1)
  expect(new Set(store.match!.events.map(event => event.id)).size).toBe(store.match!.events.length)
  expect(wrapper!.text()).toContain('对局已结束')
  expect(wrapper!.text()).toContain('完整记录 · 赛后')
  console.info('SPECTATOR_FINISHED', { gameId: game.gameId, version: current?.version, humanActions, connections: connections.length })
}, 80_000)
