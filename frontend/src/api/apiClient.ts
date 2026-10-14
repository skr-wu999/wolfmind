import { API_BASE_URL, expireSession, sessionToken } from './config'
import { newUuid } from './uuid'

/**
 * 后端错误（api-spec.md §1 的错误信封）。
 *
 * `code` 是**稳定的机读标识**（如 `NOT_READY`、`AUTH_REQUIRED`），`message` 是给人看的文案。
 * 界面逻辑一律按 `code` 分支，不匹配文案——文案会改，改文案不该让"未就绪"变成"未知错误"。
 */
export class ApiError extends Error {
  constructor(
    readonly code: string,
    message: string,
    readonly status: number,
    readonly details: unknown[] = [],
    readonly traceId?: string,
  ) {
    super(message)
    this.name = 'ApiError'
  }

  /** 是否需要重新登录（401）。界面据此清会话并跳登录页。 */
  get requiresLogin(): boolean {
    return this.status === 401
  }

  /** 是否是"房间/对局状态不满足"这类可重试的冲突（409）。 */
  get isConflict(): boolean {
    return this.status === 409
  }
}

/** 网络层失败（连不上后端）。与 `ApiError` 分开：前者是"后端说不行"，后者是"没够到后端"。 */
export class NetworkError extends Error {
  constructor(message: string, readonly cause?: unknown) {
    super(message)
    this.name = 'NetworkError'
  }
}

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'DELETE' | 'PATCH' | 'PUT'
  body?: unknown
  /** 幂等键。写操作必填（api-spec.md §1）；缺失时后端返回 400。 */
  idempotencyKey?: string
  /** 是否附带会话令牌；默认附带（有就带）。 */
  authenticated?: boolean
  /** 外部取消信号。 */
  signal?: AbortSignal
}

/** HTTP 和 HTTPS 使用同一套操作编号，避免不同意图被当成重复提交。 */
export function newIdempotencyKey(prefix = 'ui'): string {
  return `${prefix}_${newUuid()}`
}

/**
 * 发一次 HTTP 请求。
 *
 * 三件事必须在一起做，否则会出现"有的地方带了令牌、有的地方没带"这类漂移：
 * 1. 拼 URL 与鉴权头；
 * 2. 把非 2xx 响应翻译成 `ApiError`（带 `code`）；
 * 3. 把网络失败翻译成 `NetworkError`。
 */
export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { method = 'GET', body, idempotencyKey, authenticated = true, signal } = options

  const headers: Record<string, string> = { Accept: 'application/json' }
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  if (idempotencyKey) headers['Idempotency-Key'] = idempotencyKey
  const token = authenticated ? sessionToken() : null
  if (token) headers.Authorization = `Bearer ${token}`

  let response: Response
  try {
    response = await fetch(API_BASE_URL + path, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      signal,
    })
  } catch (error) {
    // 取消是正常控制流，不该被当成"后端不可用"。
    if (error instanceof DOMException && error.name === 'AbortError') throw error
    throw new NetworkError('无法连接到后端，请确认服务已启动', error)
  }

  if (response.status === 204) return undefined as T

  const text = await response.text()
  let parsed: unknown = undefined
  if (text) {
    try {
      parsed = JSON.parse(text)
    } catch {
      // 非 JSON 响应（例如代理返回的 HTML 错误页）：不当成解析失败，
      // 而是让下面的非 2xx 分支用状态码给出可读信息。
      parsed = undefined
    }
  }

  if (!response.ok) {
    if (response.status === 401) expireSession(token)
    const envelope = (parsed ?? {}) as Record<string, unknown>
    const code = typeof envelope.code === 'string' ? envelope.code : `HTTP_${response.status}`
    const message =
      typeof envelope.message === 'string'
        ? envelope.message
        : `请求失败（HTTP ${response.status}）`
    const details = Array.isArray(envelope.details) ? envelope.details : []
    const traceId = typeof envelope.traceId === 'string' ? envelope.traceId : undefined
    throw new ApiError(code, message, response.status, details, traceId)
  }

  return parsed as T
}
