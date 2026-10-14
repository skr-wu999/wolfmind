<script setup lang="ts">
import { computed, ref } from 'vue'
import { storeToRefs } from 'pinia'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useHomeDemoStore } from '@/stores/homeDemo'
import FogVillageScene from '@/components/FogVillageScene.vue'
import PixelIcon from '@/components/PixelIcon.vue'

/**
 * 登录 / 注册页。
 *
 * **两种模式共用一个表单**：注册与登录的字段完全相同（用户名、口令），分成两个页面会让
 * 用户在两页之间来回跳。区别只有提交时调哪个接口，以及注册成功后**必须再登录一次**——
 * 后端注册不下发令牌（api-spec.md §3.1），因为让"注册即可用"掩盖掉"这个口令是否真的能登录"
 * 是更差的选择。因此注册成功后自动登录，用户不必手动输第二遍。
 *
 * 错误文案直接用后端返回的 `message`：前端重写一遍会让同一条失败出现两种说法
 * （"用户名或口令不正确" vs "登录失败"），而用户看到的应当是与服务端一致的说明。
 */
const auth = useAuthStore()
const router = useRouter()
const route = useRoute()
const { loading, error } = storeToRefs(auth)
const { day } = storeToRefs(useHomeDemoStore())

const mode = ref<'login' | 'register'>('login')
const username = ref('')
const password = ref('')
const confirm = ref('')

/** 本地校验文案；与后端校验规则保持一致（用户名 3-32、口令至少 8）。 */
const localError = computed(() => {
  if (!username.value || !password.value) return ''
  if (username.value.length < 3 || username.value.length > 32) return '用户名需为 3 至 32 个字符'
  if (password.value.length < 8) return '口令至少 8 个字符'
  // "确认口令"只在注册模式下有意义。不限定模式会让切回登录后仍然拿上一次的确认值
  // 与当前口令比较——登录页因此显示一条与它无关的"两次输入的口令不一致"。
  if (mode.value === 'register' && confirm.value !== password.value) return '两次输入的口令不一致'
  return ''
})

const canSubmit = computed(() =>
  !loading.value && username.value.length > 0 && password.value.length > 0 && !localError.value,
)

/** 展示给用户的错误：本地校验优先，其次后端返回的。 */
const shownError = computed(() => localError.value || error.value)

function switchMode(next: 'login' | 'register'): void {
  mode.value = next
  // 切换模式时清掉上一模式的错误：留着"用户名已存在"会让用户以为登录也失败了。
  auth.error = ''
  confirm.value = ''
}

/**
 * 登录成功后去哪里。
 *
 * **优先回到用户原本想去的页面**（守卫会把它放在 `?redirect=`）。
 * 不回跳的话，用户点了一条对局链接、被弹到登录页、登录完落在大厅——
 * 他必须自己再找一次那个对局。这是"能登录"与"能用"的差别。
 *
 * 只接受**站内绝对路径**：`//evil.com` 这类值会被浏览器当成协议相对 URL，
 * 于是开放重定向成立。判据是"以单个 `/` 开头"，而不是"包含 `/`"。
 */
const afterLogin = computed(() => {
  const raw = route.query.redirect
  const target = Array.isArray(raw) ? raw[0] : raw
  if (typeof target === 'string' && target.startsWith('/') && !target.startsWith('//')) {
    return target
  }
  return null
})

async function submit(): Promise<void> {
  if (!canSubmit.value) return
  try {
    if (mode.value === 'register') {
      await auth.register(username.value, password.value)
      // 注册成功后自动登录：后端不下发令牌，但让用户手动再输一遍口令是无谓的摩擦。
      await auth.login(username.value, password.value)
    } else {
      await auth.login(username.value, password.value)
    }
    await goAfterLogin()
  } catch {
    // `auth` store 已经把后端文案写进 `error`，这里不重复处理。
  }
}

/** 已登录时直接离开登录页：停留在登录页没有意义。 */
async function goLobby(): Promise<void> {
  await goAfterLogin()
}

/** 回跳到 `?redirect=`，否则去大厅。 */
async function goAfterLogin(): Promise<void> {
  const target = afterLogin.value
  if (target) {
    // 用 replace 而不是 push：登录页不该留在历史里，
    // 否则用户按"后退"会回到登录页并（因为已登录）再被弹走一次。
    await router.replace(target)
    return
  }
  await router.push({ name: 'lobby' })
}
</script>

<template>
  <div class="landing login-page" :class="{ 'is-night': !day }">
    <FogVillageScene />
    <!-- 登录页不接音频与信息对话框，头部只保留回到首页的入口。 -->
    <header class="landing-header">
      <RouterLink to="/" class="landing-brand" aria-label="WolfMind 首页">
        <span class="brand-badge"><PixelIcon name="wolf" :size="20" /></span>
        <span>WolfMind</span>
      </RouterLink>
    </header>

    <main class="login-main">
      <section class="px-panel login-card" aria-labelledby="login-title">
        <p class="login-kicker"><PixelIcon name="key" :size="14" />村口登记处</p>
        <h1 id="login-title" class="login-title">
          {{ mode === 'login' ? '进入雾隐村' : '创建村民身份' }}
        </h1>
        <p class="login-lead">
          {{ mode === 'login' ? '登录后可以入座、开局，或回到进行中的对局。' : '注册后会自动登录，之后用这个账号入座和发言。' }}
        </p>

        <div class="login-tabs" role="tablist">
          <button
            type="button"
            role="tab"
            :aria-selected="mode === 'login'"
            :class="{ active: mode === 'login' }"
            @click="switchMode('login')"
          >
            登录
          </button>
          <button
            type="button"
            role="tab"
            :aria-selected="mode === 'register'"
            :class="{ active: mode === 'register' }"
            @click="switchMode('register')"
          >
            注册
          </button>
        </div>

        <form class="login-form" @submit.prevent="submit">
          <label for="login-username">用户名</label>
          <input
            id="login-username"
            v-model="username"
            type="text"
            autocomplete="username"
            :disabled="loading"
            placeholder="3 至 32 个字符"
          />

          <label for="login-password">口令</label>
          <input
            id="login-password"
            v-model="password"
            type="password"
            autocomplete="current-password"
            :disabled="loading"
            placeholder="至少 8 个字符"
          />

          <template v-if="mode === 'register'">
            <label for="login-confirm">确认口令</label>
            <input
              id="login-confirm"
              v-model="confirm"
              type="password"
              autocomplete="new-password"
              :disabled="loading"
            />
          </template>

          <p v-if="shownError" class="login-error" role="alert">{{ shownError }}</p>

          <button id="login-submit" class="px-button px-button-primary login-submit" type="submit" :disabled="!canSubmit">
            {{ loading ? '处理中…' : mode === 'login' ? '登录' : '注册并登录' }}
          </button>
        </form>

        <p v-if="auth.authenticated" class="login-signed-in">
          已登录为 {{ auth.username }}
          <button type="button" @click="goLobby">前往大厅</button>
        </p>
        <RouterLink to="/" class="login-back"><PixelIcon name="door" :size="14" />返回村庄</RouterLink>
      </section>
    </main>
  </div>
</template>
