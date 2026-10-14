// @vitest-environment jsdom
import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import HomePage from '../src/pages/HomePage.vue'
import App from '../src/App.vue'
import { routes } from '../src/router'
import { useHomeDemoStore } from '../src/stores/homeDemo'

vi.mock('../src/features/home/fogVillage', () => ({
  createFogVillage: () => ({ resize: () => {}, render: () => {} }),
  fitViewport: () => ({ W: 320, H: 180, scale: 4, cssWidth: 1280, cssHeight: 720 }),
}))

let wrapper: VueWrapper | undefined
const disconnect = vi.fn()
const removeMotionListener = vi.fn()
const cancelFrame = vi.fn()

beforeEach(() => {
  Object.defineProperty(HTMLCanvasElement.prototype, 'getContext', { configurable: true, value: () => null })
  vi.stubGlobal('requestAnimationFrame', vi.fn(() => 42))
  vi.stubGlobal('cancelAnimationFrame', cancelFrame)
  vi.stubGlobal('ResizeObserver', class {
    observe() {}
    disconnect = disconnect
  })
  vi.stubGlobal('matchMedia', vi.fn(() => ({
    matches: false,
    addEventListener: vi.fn(),
    removeEventListener: removeMotionListener,
  })))
  HTMLDialogElement.prototype.showModal = function () { this.open = true }
  HTMLDialogElement.prototype.close = function () {
    this.open = false
    this.dispatchEvent(new Event('close'))
  }
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  document.body.innerHTML = ''
  vi.unstubAllGlobals()
  vi.clearAllMocks()
})

test('router serves the homepage and redirects unimplemented paths', async () => {
  const router = createRouter({ history: createMemoryHistory(), routes })
  await router.push('/unimplemented-page')
  await router.isReady()
  wrapper = mount(App, { attachTo: document.body, global: { plugins: [createPinia(), router] } })
  await flushPromises()
  expect(router.currentRoute.value.name).toBe('home')
  expect(wrapper.get('h1').text()).toContain('雾隐村')
})

test('day, night and pause controls use the store and release browser resources', async () => {
  const pinia = createPinia()
  wrapper = mount(HomePage, { attachTo: document.body, global: { plugins: [pinia], stubs: { RouterLink: true } } })
  const demo = useHomeDemoStore(pinia)
  await wrapper.get('[aria-label="切换到黑夜"]').trigger('click')
  expect(wrapper.classes()).toContain('is-night')
  await wrapper.get('[aria-label="暂停村庄动画"]').trigger('click')
  expect(wrapper.get('.pause-state').text()).toBe('已暂停')
  await wrapper.get('[aria-label="切换到白天"]').trigger('click')
  expect(demo.phase).toBe('day')
  expect(demo.paused).toBe(true)
  wrapper.unmount()
  wrapper = undefined
  expect(cancelFrame).toHaveBeenCalledWith(42)
  expect(disconnect).toHaveBeenCalledOnce()
  expect(removeMotionListener).toHaveBeenCalledWith('change', expect.any(Function))
  expect(demo.paused).toBe(false)
})

test('empty names show feedback and the participant can vote only once', async () => {
  wrapper = mount(HomePage, { attachTo: document.body, global: { plugins: [createPinia()], stubs: { RouterLink: true } } })
  ;(wrapper.get('.explore-button').element as HTMLButtonElement).focus()
  await wrapper.get('.explore-button').trigger('click')
  await wrapper.get('.pixel-dialog .start-button').trigger('click')
  await wrapper.get('#player-name').setValue(' ')
  await wrapper.get('form').trigger('submit')
  expect(wrapper.get('[role="alert"]').text()).toBe('先给自己起个名字吧。')
  expect(document.activeElement?.id).toBe('player-name')
  await wrapper.get('#player-name').setValue('测试村民')
  await wrapper.get('form').trigger('submit')
  expect(wrapper.get('.dialog-description').text()).toContain('测试村民')
  expect(wrapper.get('.player-seat').attributes('disabled')).toBeDefined()
  await wrapper.get('.seat-2').trigger('click')
  expect(wrapper.get('.room-log').text()).toContain('你已投给 3 号村民')
  expect(wrapper.findAll('.player-seat').every((seat) => seat.attributes('disabled') !== undefined)).toBe(true)
  await wrapper.get('[aria-label="关闭窗口"]').trigger('click')
  expect(document.activeElement?.classList.contains('explore-button')).toBe(true)
})

test('spectator demonstration disables every voting seat', async () => {
  wrapper = mount(HomePage, { attachTo: document.body, global: { plugins: [createPinia()], stubs: { RouterLink: true } } })
  await wrapper.get('.explore-button').trigger('click')
  await wrapper.get('.pixel-dialog .start-button').trigger('click')
  await wrapper.findAll('.mode-options button')[1]!.trigger('click')
  await wrapper.get('form').trigger('submit')
  expect(wrapper.get('.dialog-description').text()).toContain('旁观脚本角色')
  expect(wrapper.findAll('.player-seat').every((seat) => seat.attributes('disabled') !== undefined)).toBe(true)
})
