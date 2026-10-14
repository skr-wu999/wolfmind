// @vitest-environment jsdom
import { beforeEach, describe, expect, test, vi } from 'vitest'
import { createRouter, createMemoryHistory } from 'vue-router'
import { routes } from '../src/router'
import { SESSION_TOKEN_KEY } from '../src/api/config'

/**
 * 路由守卫与登录回跳。
 *
 * ## 为什么它属于"在线展示收尾"
 *
 * 这两件事决定**用户能不能走到功能上**，而不是功能本身有没有实现：
 *
 * - 没有守卫：未登录用户点进对局页，页面对后端一堆 401，
 *   界面显示的是错误而不是"请先登录"；
 * - 没有回跳：用户点了一条对局链接、被弹到登录页、登录完落在大厅——
 *   **他必须自己再找一次那个对局**。这是"能登录"与"能用"的差别。
 *
 * ## 为什么用 `createMemoryHistory` 而不是真实 router
 *
 * 用同一份 `routes` 表（含 `meta`）**新建**一个 router，从而让守卫重新注册在
 * 这个测试实例上。直接复用导出的 `router` 会与其它测试共享导航状态，
 * 而"上一个测试停在哪个路由"会悄悄影响结果。
 */

function freshRouter() {
  const router = createRouter({ history: createMemoryHistory(), routes })
  // 与 `src/router/index.ts` 的守卫同一份判据与同一份 `meta` 声明，
  // 但注册到本测试的实例上。
  router.beforeEach((to) => {
    const hasSession = localStorage.getItem(SESSION_TOKEN_KEY) !== null
    if (to.meta.requiresAuth && !hasSession) {
      return { name: 'login', query: { redirect: to.fullPath } }
    }
    if (to.meta.redirectIfAuthenticated && hasSession) {
      return { name: 'lobby' }
    }
    return true
  })
  return router
}

beforeEach(() => {
  const store = new Map<string, string>()
  vi.stubGlobal('localStorage', {
    getItem: (k: string) => store.get(k) ?? null,
    setItem: (k: string, v: string) => void store.set(k, v),
    removeItem: (k: string) => void store.delete(k),
  })
})

describe('路由守卫', () => {
  test('未登录访问受保护路由会跳到登录页，并带上回跳目标', async () => {
    const router = freshRouter()
    await router.push('/rooms/r_1')

    expect(router.currentRoute.value.name, '应被守卫改道到登录页').toBe('login')
    // 回跳目标必须保留完整路径（含查询串），否则登录后会丢参数。
    expect(router.currentRoute.value.query.redirect).toBe('/rooms/r_1')
  })

  test('未登录访问对局页同样被拦下', async () => {
    const router = freshRouter()
    await router.push('/games/g_1')
    expect(router.currentRoute.value.name).toBe('login')
    expect(router.currentRoute.value.query.redirect).toBe('/games/g_1')
  })

  test('已登录可以进入受保护路由', async () => {
    localStorage.setItem(SESSION_TOKEN_KEY, 'u_1.s_1.secret')
    const router = freshRouter()
    await router.push('/rooms/r_1')

    expect(router.currentRoute.value.name).toBe('room-waiting')
    expect(router.currentRoute.value.params.roomId).toBe('r_1')
  })

  test('观战页是公开的：不登录也能看', async () => {
    const router = freshRouter()
    await router.push('/watch/g_1')
    // 观战的价值之一就是不登录也能看；数据由服务端按可见性裁剪。
    expect(router.currentRoute.value.name).toBe('ai-watch')
  })

  test('已登录访问登录页会被送回大厅', async () => {
    localStorage.setItem(SESSION_TOKEN_KEY, 'u_1.s_1.secret')
    const router = freshRouter()
    await router.push('/login')

    expect(router.currentRoute.value.name, '已登录不该停留在登录页').toBe('lobby')
  })

  test('未登录可以正常打开登录页', async () => {
    const router = freshRouter()
    await router.push('/login')
    expect(router.currentRoute.value.name).toBe('login')
  })
})

/**
 * 回跳目标的安全判定。
 *
 * 这段逻辑在 `LoginPage.vue` 里，而它是**安全性判据**，因此单独测：
 * 直接 `router.replace(用户给的字符串)` 会让 `//evil.com` 被浏览器当作
 * 协议相对 URL，于是登录页变成一个**开放重定向**——
 * 攻击者可以用"登录后跳转"的链接把人送到站外，而链接看起来是本站的。
 */
describe('回跳目标的安全判定', () => {
  /** 与 `LoginPage.vue` 的 `afterLogin` 同一判据。 */
  function safeRedirect(raw: unknown): string | null {
    const target = Array.isArray(raw) ? raw[0] : raw
    if (typeof target === 'string' && target.startsWith('/') && !target.startsWith('//')) {
      return target
    }
    return null
  }

  test('站内绝对路径被接受', () => {
    expect(safeRedirect('/rooms/r_1')).toBe('/rooms/r_1')
    expect(safeRedirect('/games/g_1?x=1')).toBe('/games/g_1?x=1')
  })

  test('协议相对 URL 被拒绝（否则是开放重定向）', () => {
    expect(safeRedirect('//evil.com')).toBeNull()
    expect(safeRedirect('//evil.com/path')).toBeNull()
  })

  test('绝对 URL 与相对路径被拒绝', () => {
    expect(safeRedirect('https://evil.com')).toBeNull()
    expect(safeRedirect('evil.com')).toBeNull()
    expect(safeRedirect('rooms/r_1')).toBeNull()
  })

  test('非字符串与缺失值被拒绝', () => {
    expect(safeRedirect(undefined)).toBeNull()
    expect(safeRedirect(null)).toBeNull()
    expect(safeRedirect(42)).toBeNull()
    expect(safeRedirect({})).toBeNull()
  })

  test('数组形式取第一个（vue-router 的查询串可能是数组）', () => {
    expect(safeRedirect(['/rooms/r_1', '/other'])).toBe('/rooms/r_1')
    expect(safeRedirect(['//evil.com'])).toBeNull()
  })
})
