// @vitest-environment jsdom
import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import LobbyPage from '../src/pages/LobbyPage.vue'
import { setSessionToken } from '../src/api/config'
import { installStorage } from './support/jsdomStubs'

const service = vi.hoisted(() => ({ list: vi.fn(), get: vi.fn(), create: vi.fn(), join: vi.fn(), leave: vi.fn(), takeSeat: vi.fn(), ready: vi.fn(), setAi: vi.fn() }))
vi.mock('../src/features/lobby/mockRoomAdapter', () => ({ roomAdapter: service }))
vi.mock('../src/api/adapters', async (original) => ({ ...await original<object>(), usingRealBackend: () => true }))
let wrapper: VueWrapper | undefined

function me(activeGameId: string | null) {
  const body = { userId: 'u_1', username: 'alice', displayName: 'alice', activeGameId, spectatingGameIds: [] }
  vi.stubGlobal('fetch', vi.fn(async () => ({ ok: true, status: 200, headers: new Headers(), text: async () => JSON.stringify(body) }) as Response))
}
async function render() {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/:any(.*)*', component: { template: '<div />' } }] })
  await router.push('/lobby')
  wrapper = mount(LobbyPage, { attachTo: document.body, global: { plugins: [createPinia(), router], stubs: { VillageScene: true, VillageHud: true, VillageIllustration: true, PixelAvatar: true } } })
  await flushPromises()
}
beforeEach(() => {
  installStorage(); setSessionToken('u_1.s.secret'); service.list.mockResolvedValue([])
  HTMLDialogElement.prototype.showModal = function () { this.open = true }
  HTMLDialogElement.prototype.close = function () { if (this.open) { this.open = false; this.dispatchEvent(new Event('close')) } }
})
afterEach(() => { wrapper?.unmount(); wrapper = undefined; document.body.innerHTML = ''; vi.unstubAllGlobals(); setSessionToken(null) })

test('lobby offers to continue the running game reported by /auth/me', async () => {
  me('g_running')
  await render()
  const link = wrapper!.get('.lobby-resume a')
  expect(link.text()).toContain('继续对局')
  expect(link.attributes('href')).toBe('/games/g_running')
})

test('no resume entry without an active game', async () => {
  me(null)
  await render()
  expect(wrapper!.find('.lobby-resume').exists()).toBe(false)
})
