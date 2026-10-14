/**
 * 运行时配置：后端地址与接入模式。
 *
 * 两个变量都来自 Vite 的环境变量，且**都有可用的默认值**——没有它们的开发环境应该能直接跑起来
 * （默认指向本机后端的默认端口），而不是启动就崩。
 */
export const API_BASE_URL: string = import.meta.env.VITE_API_BASE_URL ?? 'http://127.0.0.1:8080'

/** WebSocket 地址；未配置或为空时按 API 地址推导，同源部署使用当前页面地址。 */
export const WS_URL: string = import.meta.env.VITE_WS_URL?.trim() || deriveWsUrl(API_BASE_URL)

/**
 * 是否使用真实后端。
 *
 * 默认 **false**（用 mock）：前端可以在后端没起来时独立开发与演示。
 * 设 `VITE_USE_REAL_API=true` 切换到真实适配器。
 *
 * 默认值是 mock 而不是 real，理由是失败代价不对称：mock 默认能跑，real 默认在后端
 * 未启动时整页报错。演示与开发是更常见的情形。
 */
export const USE_REAL_API: boolean = (import.meta.env.VITE_USE_REAL_API ?? 'false') === 'true'

/** 由 HTTP 地址推导 WebSocket 地址（http→ws、https→wss）。 */
export function deriveWsUrl(apiBaseUrl: string): string {
  const normalized = apiBaseUrl.replace(/\/+$/, '')
  if (normalized.startsWith('https://')) return 'wss://' + normalized.slice('https://'.length) + '/ws'
  if (normalized.startsWith('http://')) return 'ws://' + normalized.slice('http://'.length) + '/ws'
  // 相对地址（前后端同源部署）时用当前页面协议。
  const scheme = typeof location !== 'undefined' && location.protocol === 'https:' ? 'wss' : 'ws'
  return scheme + '://' + (typeof location !== 'undefined' ? location.host : '127.0.0.1') + '/ws'
}

/** 会话令牌的存储键；登出时清掉它。 */
export const SESSION_TOKEN_KEY = 'wolfmind.sessionToken'

/** 读取当前会话令牌；非浏览器环境返回 null。 */
export function sessionToken(): string | null {
  try {
    return globalThis.localStorage?.getItem(SESSION_TOKEN_KEY) ?? null
  } catch {
    // 隐私模式或测试环境可能禁用 storage；没有令牌等价于未登录，不是错误。
    return null
  }
}

/** 写入或清除会话令牌。 */
export function setSessionToken(token: string | null): void {
  try {
    if (token) globalThis.localStorage?.setItem(SESSION_TOKEN_KEY, token)
    else globalThis.localStorage?.removeItem(SESSION_TOKEN_KEY)
  } catch {
    // 存不下就只在本次会话内有效：用户仍能用，刷新后需要重新登录。
  }
}

let sessionExpiryHandler: (() => void) | undefined

export function setSessionExpiryHandler(handler?: () => void): void {
  sessionExpiryHandler = handler
}

/** 旧请求的 401 不能注销随后登录的新会话。 */
export function expireSession(expectedToken: string | null): void {
  if (!expectedToken || sessionToken() !== expectedToken) return
  setSessionToken(null)
  sessionExpiryHandler?.()
}
