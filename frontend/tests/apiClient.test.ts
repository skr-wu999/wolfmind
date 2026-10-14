import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { ApiError, NetworkError, newIdempotencyKey, request } from '../src/api/apiClient'
import { SESSION_TOKEN_KEY, deriveWsUrl, sessionToken, setSessionToken, setSessionExpiryHandler } from '../src/api/config'

/**
 * HTTP 客户端的行为契约。
 *
 * 这些断言守的是"错误翻译"这一件事：后端说不行（`ApiError`）与够不到后端
 * （`NetworkError`）必须能被界面区分——前者要显示后端给的原因，后者要提示检查服务是否启动。
 * 混成一种错误会让用户看到"请求失败"而完全不知道该怎么办。
 */

/** 构造一个 fetch 替身；`body` 为对象时自动序列化。 */
function stubFetch(
  status: number,
  body: unknown,
  init: { headers?: Record<string, string> } = {},
) {
  const text = body === undefined ? '' : JSON.stringify(body)
  const response = {
    ok: status >= 200 && status < 300,
    status,
    text: async () => text,
    headers: new Headers(init.headers ?? {}),
  }
  const spy = vi.fn(async () => response as unknown as Response)
  vi.stubGlobal('fetch', spy)
  return spy
}

/** 取出最后一次 fetch 调用的参数。 */
function lastCall(spy: ReturnType<typeof vi.fn>): [string, RequestInit] {
  const call = spy.mock.calls.at(-1)!
  return [call[0] as string, call[1] as RequestInit]
}

describe('apiClient', () => {
  beforeEach(() => {
    // node 环境没有 localStorage，因此 `setSessionToken` 会按设计静默降级（存不下也不报错）。
    // 这里装一个替身来验证"有令牌就带上"这条行为；替身本身的降级行为另有断言。
    const store = new Map<string, string>()
    vi.stubGlobal('localStorage', {
      getItem: (key: string) => store.get(key) ?? null,
      setItem: (key: string, value: string) => void store.set(key, value),
      removeItem: (key: string) => void store.delete(key),
    })
    setSessionToken(null)
  })

  afterEach(() => {
    setSessionExpiryHandler()
    vi.unstubAllGlobals()
  })

  test('成功响应直接返回解析后的对象', async () => {
    stubFetch(200, { roomId: 'r_1', occupied: 2 })

    const result = await request<{ roomId: string }>('/api/v1/rooms/r_1')

    expect(result.roomId).toBe('r_1')
  })

  test('204 返回 undefined 而不是试图解析空响应体', async () => {
    // 登出接口就是 204（api-spec.md §3）：解析空体会抛 SyntaxError，
    // 而"登出成功却报错"会让界面显示一个不存在的问题。
    stubFetch(204, undefined)

    await expect(request('/api/v1/auth/logout', { method: 'POST' })).resolves.toBeUndefined()
  })

  test('非 2xx 翻译成带 code 的 ApiError', async () => {
    stubFetch(409, {
      code: 'NOT_READY',
      message: '还有 1 个座位未就绪',
      details: [],
      traceId: 'cor_1',
    })

    const error = (await request('/api/v1/rooms/r_1/games', { method: 'POST' }).catch((e) => e)) as ApiError

    expect(error).toBeInstanceOf(ApiError)
    expect(error.code).toBe('NOT_READY')
    expect(error.status).toBe(409)
    expect(error.traceId).toBe('cor_1')
    // 界面按 code 分支，不匹配文案——这条断言守住"code 必须来自后端"。
    expect(error.message).toBe('还有 1 个座位未就绪')
  })

  test('401 被标记为需要重新登录', async () => {
    stubFetch(401, { code: 'AUTH_REQUIRED', message: '会话无效' })

    const error = (await request('/api/v1/auth/me').catch((e) => e)) as ApiError

    expect(error.requiresLogin).toBe(true)
  })

  test('授权请求的 401 清会话且只通知一次，登录失败不影响已有会话', async () => {
    const expired = vi.fn()
    setSessionExpiryHandler(expired)
    setSessionToken('old-session')
    stubFetch(401, { code: 'AUTH_REQUIRED', message: '会话无效' })
    await request('/api/v1/auth/me').catch(() => {})
    await request('/api/v1/auth/me').catch(() => {})
    expect(sessionToken()).toBeNull()
    expect(expired).toHaveBeenCalledTimes(1)
    setSessionToken('new-session')
    await request('/api/v1/auth/login', { authenticated: false }).catch(() => {})
    expect(sessionToken()).toBe('new-session')
    expect(expired).toHaveBeenCalledTimes(1)
  })

  test('旧请求的迟到 401 不清除后来登录的新会话', async () => {
    const expired = vi.fn()
    setSessionExpiryHandler(expired)
    setSessionToken('old-session')
    let finish!: (response: Response) => void
    vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>(resolve => { finish = resolve })))
    const pending = request('/api/v1/auth/me').catch(() => {})
    setSessionToken('new-session')
    finish(new Response(JSON.stringify({ code: 'AUTH_REQUIRED' }), { status: 401 }))
    await pending
    expect(sessionToken()).toBe('new-session')
    expect(expired).not.toHaveBeenCalled()
  })

  test('409 被标记为可重试的冲突', async () => {
    stubFetch(409, { code: 'ROOM_FULL', message: '房间已满' })

    const error = (await request('/api/v1/rooms/r_1/join', { method: 'POST' }).catch((e) => e)) as ApiError

    expect(error.isConflict).toBe(true)
    expect(error.requiresLogin).toBe(false)
  })

  test('网络失败翻译成 NetworkError，而不是 ApiError', async () => {
    // 两者的界面处置不同：ApiError 显示后端原因，NetworkError 提示检查服务是否启动。
    vi.stubGlobal('fetch', vi.fn(async () => {
      throw new TypeError('Failed to fetch')
    }))

    const error = (await request('/api/v1/rooms').catch((e) => e)) as NetworkError

    expect(error).toBeInstanceOf(NetworkError)
    expect(error).not.toBeInstanceOf(ApiError)
    expect(error.message).toContain('无法连接到后端')
  })

  test('取消（AbortError）原样抛出，不被当成后端不可用', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => {
      throw new DOMException('aborted', 'AbortError')
    }))

    const error = await request('/api/v1/rooms').catch((e) => e)

    // 取消是正常控制流（例如用户离开页面），把它包成 NetworkError 会让界面弹出
    // 一个虚假的"连接失败"提示。
    expect(error).not.toBeInstanceOf(NetworkError)
    expect((error as DOMException).name).toBe('AbortError')
  })

  test('非 JSON 响应体不会掩盖 HTTP 状态码', async () => {
    // 反向代理常常返回 HTML 错误页；此时仍应给出可读的状态码信息。
    const response = { ok: false, status: 502, text: async () => '<html>Bad Gateway</html>' }
    vi.stubGlobal('fetch', vi.fn(async () => response as unknown as Response))

    const error = (await request('/api/v1/rooms').catch((e) => e)) as ApiError

    expect(error).toBeInstanceOf(ApiError)
    expect(error.code).toBe('HTTP_502')
    expect(error.message).toContain('502')
  })

  test('有令牌时附带 Authorization，无令牌时不附带', async () => {
    const spy = stubFetch(200, { ok: true })

    await request('/api/v1/rooms')
    expect((lastCall(spy)[1].headers as Record<string, string>).Authorization).toBeUndefined()

    setSessionToken('u_1.s_1.secret')
    await request('/api/v1/rooms')
    expect((lastCall(spy)[1].headers as Record<string, string>).Authorization).toBe(
      'Bearer u_1.s_1.secret',
    )
  })

  test('authenticated=false 时不附带令牌（只读接口允许匿名）', async () => {
    setSessionToken('u_1.s_1.secret')
    const spy = stubFetch(200, { ok: true })

    await request('/api/v1/rooms', { authenticated: false })

    expect((lastCall(spy)[1].headers as Record<string, string>).Authorization).toBeUndefined()
  })

  test('幂等键原样进请求头', async () => {
    const spy = stubFetch(201, { roomId: 'r_1' })

    await request('/api/v1/rooms', { method: 'POST', body: {}, idempotencyKey: 'ui_1' })

    expect((lastCall(spy)[1].headers as Record<string, string>)['Idempotency-Key']).toBe('ui_1')
  })

  test('JSON 请求体被序列化且带 Content-Type', async () => {
    const spy = stubFetch(201, { roomId: 'r_1' })

    await request('/api/v1/rooms', { method: 'POST', body: { name: '测试房' } })

    const [, init] = lastCall(spy)
    expect(init.body).toBe(JSON.stringify({ name: '测试房' }))
    expect((init.headers as Record<string, string>)['Content-Type']).toBe('application/json')
  })
})

describe('newIdempotencyKey', () => {
  test('每次生成都不同', () => {
    // 相同会让后端的"重复提交"保护把两次不同的用户意图合并，
    // 表现是"点了没反应"——比报错更难排查。
    const keys = new Set(Array.from({ length: 50 }, () => newIdempotencyKey('room')))
    expect(keys.size).toBe(50)
  })

  test('带前缀，便于在后端日志里定位来源', () => {
    expect(newIdempotencyKey('room')).toMatch(/^room_/)
    expect(newIdempotencyKey('start')).toMatch(/^start_/)
  })
})

describe('deriveWsUrl', () => {
  test('http 转 ws、https 转 wss，并固定加上 /ws 路径', () => {
    expect(deriveWsUrl('http://127.0.0.1:8080')).toBe('ws://127.0.0.1:8080/ws')
    expect(deriveWsUrl('https://example.com')).toBe('wss://example.com/ws')
  })

  test('忽略末尾斜杠，避免出现双斜杠路径', () => {
    expect(deriveWsUrl('http://127.0.0.1:8080/')).toBe('ws://127.0.0.1:8080/ws')
  })

  test('会话令牌键是稳定的字符串', () => {
    // 改这个键会让所有已登录用户在下一次发布后掉线，因此它是一条契约。
    expect(SESSION_TOKEN_KEY).toBe('wolfmind.sessionToken')
  })
})
