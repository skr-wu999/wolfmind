import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { ApiError, request, newIdempotencyKey } from '@/api/apiClient'
import { setSessionToken, sessionToken } from '@/api/config'
import type { CurrentUserResponse, LoginResponse, RegisterResponse } from '@/api/restTypes'

/**
 * 账号与会话。
 *
 * **令牌只在这里落地**：`apiClient` 从 `config.sessionToken()` 读它，而写入只发生在
 * 登录成功那一个位置。分散写入会让"登出后某个页面还能发授权请求"这种缺陷出现——
 * 因为它读的是自己缓存的那份。
 *
 * **`me()` 失败不等于需要重新登录**：网络不通也会失败。只有 401 才清会话；
 * 其它错误保留令牌，否则用户会在地铁里被登出。
 */
export const useAuthStore = defineStore('auth', () => {
  const userId = ref<string | null>(null)
  const username = ref<string | null>(null)
  const displayName = ref<string | null>(null)
  const activeGameId = ref<string | null>(null)
  const spectatingGameIds = ref<string[]>([])
  const loading = ref(false)
  const error = ref('')

  /** 是否已登录。以"有没有令牌"为准而不是以 `userId` 为准：刷新页面后 `userId` 还没拉到。 */
  const authenticated = computed(() => sessionToken() !== null)

  function apply(me: CurrentUserResponse): void {
    userId.value = me.userId
    username.value = me.username
    displayName.value = me.displayName
    activeGameId.value = me.activeGameId
    spectatingGameIds.value = me.spectatingGameIds
  }

  function clear(): void {
    setSessionToken(null)
    userId.value = null
    username.value = null
    displayName.value = null
    activeGameId.value = null
    spectatingGameIds.value = []
  }

  /** 注册；**不下发令牌**（api-spec.md §3.1），因此注册后需要显式登录。 */
  async function register(name: string, password: string, display?: string): Promise<RegisterResponse> {
    loading.value = true
    error.value = ''
    try {
      return await request<RegisterResponse>('/api/v1/auth/register', {
        method: 'POST',
        body: { username: name, password, displayName: display },
        authenticated: false,
      })
    } catch (cause) {
      error.value = describe(cause)
      throw cause
    } finally {
      loading.value = false
    }
  }

  /** 登录并保存令牌。 */
  async function login(name: string, password: string): Promise<void> {
    loading.value = true
    error.value = ''
    try {
      const result = await request<LoginResponse>('/api/v1/auth/login', {
        method: 'POST',
        body: { username: name, password },
        authenticated: false,
      })
      // 唯一一处写入令牌的地方。
      setSessionToken(result.sessionToken)
      userId.value = result.userId
      // 立刻拉一次身份：`login` 只返回 userId，而界面需要 username 与 displayName。
      await me()
    } catch (cause) {
      error.value = describe(cause)
      clear()
      throw cause
    } finally {
      loading.value = false
    }
  }

  /**
   * 拉取当前账号。
   *
   * @returns 是否处于登录状态
   */
  async function me(): Promise<boolean> {
    if (!sessionToken()) {
      clear()
      return false
    }
    try {
      apply(await request<CurrentUserResponse>('/api/v1/auth/me'))
      return true
    } catch (cause) {
      // 只有明确的"凭据无效"才清会话。网络失败保留令牌，
      // 否则用户一断网就被登出——而他什么都没做错。
      if (cause instanceof ApiError && cause.requiresLogin) {
        clear()
        return false
      }
      error.value = describe(cause)
      return sessionToken() !== null
    }
  }

  /** 登出；无论后端是否成功，本地会话都必须清掉。 */
  async function logout(): Promise<void> {
    loading.value = true
    try {
      if (sessionToken()) {
        await request('/api/v1/auth/logout', {
          method: 'POST',
          body: {},
          idempotencyKey: newIdempotencyKey('logout'),
        })
      }
    } catch {
      // 后端登出失败也要清本地会话：留着令牌会让用户以为"登不出去"。
      // 服务端那份会话会在 12 小时后自然过期（redis-schema.md §5.9）。
    } finally {
      clear()
      loading.value = false
    }
  }

  return {
    userId, username, displayName, activeGameId, spectatingGameIds, loading, error,
    authenticated, register, login, me, logout, clear,
  }
})

/** 把异常转成可读文案；未知异常给一句通用提示而不是 `undefined`。 */
function describe(cause: unknown): string {
  if (cause instanceof ApiError) return cause.message
  if (cause instanceof Error) return cause.message
  return '操作失败，请重试。'
}
