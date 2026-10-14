// @vitest-environment jsdom
import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import LoginPage from '../src/pages/LoginPage.vue'
import { SESSION_TOKEN_KEY, sessionToken } from '../src/api/config'

/**
 * 登录 / 注册页。
 *
 * 断言的重点不是样式，而是三件会让用户卡住的事：
 * 1. **注册成功后自动登录**，用户不必把手刚输的口令再输一遍（后端注册不下发令牌）；
 * 2. **失败文案来自后端**，前端不重写——否则同一个原因会有两种说法；
 * 3. **切换模式时清掉上一模式的错误**，否则"用户名已存在"会留在登录模式下，
 *    让用户以为登录也失败了。
 */

const store = new Map<string, string>()
vi.stubGlobal('localStorage', {
  getItem: (k: string) => store.get(k) ?? null,
  setItem: (k: string, v: string) => void store.set(k, v),
  removeItem: (k: string) => void store.delete(k),
})

/** 安装 fetch 替身；`handler` 按 URL 决定响应。 */
function setup(handler: (url: string) => { status: number; body?: unknown }) {
  const spy = vi.fn(async (url: string) => {
    const { status, body } = handler(url)
    return {
      ok: status >= 200 && status < 300,
      status,
      text: async () => (body === undefined ? '' : JSON.stringify(body)),
      headers: new Headers(),
    } as unknown as Response
  })
  vi.stubGlobal('fetch', spy)
  return spy
}

const ME = {
  userId: 'u_1',
  username: 'wolf01',
  displayName: 'wolf01',
  activeGameId: null,
  spectatingGameIds: [],
}

let wrapper: VueWrapper | undefined

async function mountPage() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'home', component: { template: '<div>home</div>' } },
      { path: '/login', name: 'login', component: LoginPage },
      { path: '/lobby', name: 'lobby', component: { template: '<div>lobby</div>' } },
    ],
  })
  await router.push('/login')
  await router.isReady()
  wrapper = mount(LoginPage, { global: { plugins: [createPinia(), router], stubs: { FogVillageScene: true } } })
  return { wrapper, router }
}

/**
 * 按按钮文字找 tab。
 *
 * **不能按 `aria-selected` 选**：那个属性表示"当前哪个 tab 被选中"，因此"另一个 tab"
 * 在两次点击之间会变。我曾用它写测试，结果第二次点击又点回了同一个 tab（模式没变），
 * 而失败信息看起来像"切模式后残留了确认口令错误"——一个并不存在的缺陷。
 */
function findTab(page: VueWrapper, label: string) {
  const tab = page.findAll('[role="tab"]').find((node) => node.text() === label)
  if (!tab) throw new Error(`找不到 tab：${label}`)
  return tab
}

/** 填表并提交。 */
async function submitForm(page: VueWrapper, username: string, password: string, confirm?: string) {
  await page.find('#login-username').setValue(username)
  await page.find('#login-password').setValue(password)
  if (confirm !== undefined) await page.find('#login-confirm').setValue(confirm)
  await page.find('form').trigger('submit')
  await flushPromises()
}

beforeEach(() => {
  store.clear()
  vi.resetAllMocks()
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  vi.unstubAllGlobals()
  // 重新装回 localStorage 替身（unstubAllGlobals 会把它一起清掉）。
  vi.stubGlobal('localStorage', {
    getItem: (k: string) => store.get(k) ?? null,
    setItem: (k: string, v: string) => void store.set(k, v),
    removeItem: (k: string) => void store.delete(k),
  })
})

test('登录成功保存令牌并跳转大厅', async () => {
  setup((url) =>
    url.endsWith('/auth/login')
      ? { status: 200, body: { userId: 'u_1', sessionToken: 'u_1.s_1.secret', expiresAt: 1 } }
      : { status: 200, body: ME },
  )
  const { wrapper: page, router } = await mountPage()

  await submitForm(page, 'wolf01', 'correct-horse')

  expect(sessionToken()).toBe('u_1.s_1.secret')
  expect(router.currentRoute.value.name).toBe('lobby')
})

test('登录失败展示后端文案，且不保存令牌', async () => {
  setup(() => ({ status: 401, body: { code: 'AUTH_REQUIRED', message: '用户名或口令不正确' } }))
  const { wrapper: page, router } = await mountPage()

  await submitForm(page, 'wolf01', 'wrong-password')

  // 文案直接用后端的：前端重写会让同一条失败出现两种说法。
  expect(page.find('.login-error').text()).toBe('用户名或口令不正确')
  expect(sessionToken()).toBeNull()
  expect(router.currentRoute.value.name).toBe('login')
})

test('注册成功后自动登录（用户不必再输一遍口令）', async () => {
  const calls: string[] = []
  setup((url) => {
    calls.push(url)
    if (url.endsWith('/auth/register')) {
      return { status: 201, body: { userId: 'u_1', username: 'wolf01', displayName: 'wolf01' } }
    }
    if (url.endsWith('/auth/login')) {
      return { status: 200, body: { userId: 'u_1', sessionToken: 'u_1.s_1.secret', expiresAt: 1 } }
    }
    return { status: 200, body: ME }
  })
  const { wrapper: page, router } = await mountPage()

  await findTab(page, '注册').trigger('click')
  await submitForm(page, 'wolf01', 'correct-horse', 'correct-horse')

  // 后端注册不下发令牌（api-spec.md §3.1），因此必须再登录一次——
  // 但这一步对用户应当是透明的。
  expect(calls.some((u) => u.endsWith('/auth/register'))).toBe(true)
  expect(calls.some((u) => u.endsWith('/auth/login'))).toBe(true)
  expect(sessionToken()).toBe('u_1.s_1.secret')
  expect(router.currentRoute.value.name).toBe('lobby')
})

test('两次口令不一致时本地拦下，不发请求', async () => {
  const spy = setup(() => ({ status: 200, body: ME }))
  const { wrapper: page } = await mountPage()

  await findTab(page, '注册').trigger('click')
  await submitForm(page, 'wolf01', 'correct-horse', 'different-horse')

  expect(page.find('.login-error').text()).toBe('两次输入的口令不一致')
  // 本地就能判断的错误不该白跑一次往返。
  expect(spy).not.toHaveBeenCalled()
})

test('口令过短时本地拦下', async () => {
  setup(() => ({ status: 200, body: ME }))
  const { wrapper: page } = await mountPage()

  await submitForm(page, 'wolf01', 'short')

  expect(page.find('.login-error').text()).toBe('口令至少 8 个字符')
})

test('用户名过短时本地拦下', async () => {
  setup(() => ({ status: 200, body: ME }))
  const { wrapper: page } = await mountPage()

  await submitForm(page, 'ab', 'correct-horse')

  expect(page.find('.login-error').text()).toBe('用户名需为 3 至 32 个字符')
})

test('切换模式清掉上一模式的错误', async () => {
  setup(() => ({ status: 409, body: { code: 'USERNAME_TAKEN', message: '用户名已被占用' } }))
  const { wrapper: page } = await mountPage()

  await findTab(page, '注册').trigger('click')
  await submitForm(page, 'wolf01', 'correct-horse', 'correct-horse')
  expect(page.find('.login-error').text()).toBe('用户名已被占用')

  await findTab(page, '登录').trigger('click')

  // 两条都要验：陈旧的"用户名已被占用"必须消失（否则用户以为登录也失败了），
  // 且**不能**被"两次输入的口令不一致"顶替（确认口令只在注册模式下有意义——
  // 那是一条与登录页无关的错误）。
  const text = page.find('.login-error').exists() ? page.find('.login-error').text() : ''
  expect(text).not.toBe('用户名已被占用')
  expect(text).not.toBe('两次输入的口令不一致')
})

test('字段为空时提交按钮不可用', async () => {
  setup(() => ({ status: 200, body: ME }))
  const { wrapper: page } = await mountPage()

  expect(page.find('#login-submit').attributes('disabled')).toBeDefined()

  await page.find('#login-username').setValue('wolf01')
  await page.find('#login-password').setValue('correct-horse')

  expect(page.find('#login-submit').attributes('disabled')).toBeUndefined()
})
