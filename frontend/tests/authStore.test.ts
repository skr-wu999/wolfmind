import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAuthStore } from '../src/stores/auth'
import { SESSION_TOKEN_KEY, sessionToken } from '../src/api/config'

/**
 * 用真实的写入路径预置令牌。
 *
 * 不直接 `localStorage.setItem(key, token)`：那会绕过 `config` 的实现，
 * 于是"令牌存在哪个键下"这件事在测试里被复制了一份，改键名时测试仍会通过。
 */
function seedToken(value: string | null): void {
  const store = new Map<string, string>()
  if (value) store.set(SESSION_TOKEN_KEY, value)
  vi.stubGlobal('localStorage', {
    getItem: (k: string) => store.get(k) ?? null,
    setItem: (k: string, v: string) => void store.set(k, v),
    removeItem: (k: string) => void store.delete(k),
  })
}

/**
 * 会话与账号。
 *
 * 这一层守两条与"用户会不会莫名掉线"直接相关的规则：
 * 1. **`me()` 失败不等于需要重新登录**——网络不通也会失败。只有 401 才清会话，
 *    否则用户在地铁里刷一下页面就被登出。
 * 2. **登出必须清本地会话，哪怕后端失败**——留着令牌会让用户以为"登不出去"。
 */

/** 安装 localStorage + fetch 替身。 */
function setup(handler: (url: string, init?: RequestInit) => { status: number; body?: unknown }) {
  const store = new Map<string, string>()
  vi.stubGlobal('localStorage', {
    getItem: (k: string) => store.get(k) ?? null,
    setItem: (k: string, v: string) => void store.set(k, v),
    removeItem: (k: string) => void store.delete(k),
  })
  const spy = vi.fn(async (url: string, init?: RequestInit) => {
    const { status, body } = handler(url, init)
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

describe('useAuthStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  test('未登录时 authenticated 为 false', () => {
    setup(() => ({ status: 200, body: ME }))

    expect(useAuthStore().authenticated).toBe(false)
  })

  test('登录保存令牌并立刻拉取身份', async () => {
    const spy = setup((url) =>
      url.endsWith('/auth/login')
        ? { status: 200, body: { userId: 'u_1', sessionToken: 'u_1.s_1.secret', expiresAt: 1 } }
        : { status: 200, body: ME },
    )
    const auth = useAuthStore()

    await auth.login('wolf01', 'correct-horse')

    expect(sessionToken()).toBe('u_1.s_1.secret')
    // login 只返回 userId，而界面需要 username 与 displayName。
    expect(auth.username).toBe('wolf01')
    expect(spy.mock.calls.some(([url]) => String(url).endsWith('/auth/me'))).toBe(true)
  })

  test('登录失败清掉令牌，不留半登录状态', async () => {
    setup(() => ({ status: 401, body: { code: 'AUTH_REQUIRED', message: '用户名或口令不正确' } }))
    const auth = useAuthStore()

    await expect(auth.login('wolf01', 'wrong')).rejects.toThrow()

    // 留着上一次的令牌会让用户以为"登录成功了但界面没变"。
    expect(sessionToken()).toBeNull()
    expect(auth.error).toBe('用户名或口令不正确')
  })

  test('register 不下发令牌，因此不写会话', async () => {
    setup(() => ({ status: 201, body: { userId: 'u_1', username: 'wolf01', displayName: 'wolf01' } }))
    const auth = useAuthStore()

    await auth.register('wolf01', 'correct-horse')

    // api-spec.md §3.1：注册与登录是两步，让注册顺带下发会话会掩盖
    // "这个口令是否真的能登录"这个问题。
    expect(sessionToken()).toBeNull()
    expect(auth.authenticated).toBe(false)
  })

  test('me 遇到 401 清会话', async () => {
    setup(() => ({ status: 401, body: { code: 'AUTH_REQUIRED', message: '会话无效' } }))
    seedToken('u_1.s_1.stale')
    const auth = useAuthStore()

    const loggedIn = await auth.me()

    expect(loggedIn).toBe(false)
    expect(sessionToken()).toBeNull()
  })

  test('me 遇到网络失败保留令牌（不把断网当成登出）', async () => {
    vi.stubGlobal('localStorage', {
      getItem: () => 'u_1.s_1.secret',
      setItem: () => {},
      removeItem: () => {},
    })
    vi.stubGlobal('fetch', vi.fn(async () => {
      throw new TypeError('Failed to fetch')
    }))
    const auth = useAuthStore()

    const loggedIn = await auth.me()

    // 断网时把用户登出是错的：他什么都没做错，回到有网的地方还要重新输口令。
    expect(loggedIn).toBe(true)
    expect(sessionToken()).toBe('u_1.s_1.secret')
  })

  test('me 没有令牌时不发请求', async () => {
    const spy = setup(() => ({ status: 200, body: ME }))
    const auth = useAuthStore()

    expect(await auth.me()).toBe(false)
    // 没令牌还发请求只会拿到 401，白费一次往返并可能触发不必要的清会话逻辑。
    expect(spy).not.toHaveBeenCalled()
  })

  test('logout 清本地会话', async () => {
    setup(() => ({ status: 204 }))
    seedToken('u_1.s_1.secret')
    const auth = useAuthStore()

    await auth.logout()

    expect(sessionToken()).toBeNull()
    expect(auth.userId).toBeNull()
    expect(auth.authenticated).toBe(false)
  })

  test('后端登出失败也要清本地会话', async () => {
    setup(() => ({ status: 500, body: { code: 'INTERNAL_ERROR', message: '出错了' } }))
    seedToken('u_1.s_1.secret')
    const auth = useAuthStore()

    await auth.logout()

    // 留着令牌会让用户以为"登不出去"。服务端那份会自然过期。
    expect(sessionToken()).toBeNull()
  })

  test('logout 在没有令牌时不发请求', async () => {
    const spy = setup(() => ({ status: 204 }))
    const auth = useAuthStore()

    await auth.logout()

    expect(spy).not.toHaveBeenCalled()
  })

  test('activeGameId 与 spectatingGameIds 原样来自后端（尚未接入时为 null 与空数组）', async () => {
    setup(() => ({ status: 200, body: ME }))
    seedToken('u_1.s_1.secret')
    const auth = useAuthStore()

    await auth.me()

    // 后端目前恒返回 null 与空数组（需要按账号反查房间与订阅关系）。
    // 前端**不编一个值**：编错会让"继续对局"入口指向不存在的对局。
    expect(auth.activeGameId).toBeNull()
    expect(auth.spectatingGameIds).toEqual([])
  })
})
