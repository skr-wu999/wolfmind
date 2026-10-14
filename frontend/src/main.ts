import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import { router } from './router'
import { useAuthStore } from './stores/auth'
import { wireAdapterProviders } from './stores/session'
import './assets/main.css'

const pinia = createPinia()
const app = createApp(App).use(pinia).use(router)

/**
 * 启动接线。
 *
 * 三件事必须在挂载之前做完，顺序也不能反：
 *
 * 1. **先建 Pinia 实例**：`useAuthStore()` / `useSeatStore()` 在组件外需要一个活动实例
 *    （`setActivePinia` 只在测试里显式调用，生产代码没有它）。
 * 2. **再注入适配器的身份提供者**（`wireAdapterProviders`）。适配器需要"当前账号"与
 *    "我的座位号"才能算出 `mySeatNo` 与视图里的 `me`——服务端视图按可见性脱敏，
 *    但**没有单独的"我"**（那是 `ViewerContext` 的职责）。
 *    注入的是**读取函数**而不是值：令牌与座位号会在登录、入座、离开后变化，
 *    注入快照会让适配器一直拿着旧身份。
 * 3. **最后恢复会话**。`me()` 在没有令牌时不发请求，因此冷启动零开销。
 *
 * `me()` 用 `void` 而不是 await：**恢复会话失败不该阻止界面渲染**。
 * 用户应当先看到页面与登录入口，而不是盯着白屏等一个可能超时的请求。
 */
wireAdapterProviders(() => {
  const current = router.currentRoute.value
  if (current.meta.requiresAuth) {
    void router.replace({ name: 'login', query: { redirect: current.fullPath } })
  }
})

app.mount('#app')

void useAuthStore().me()
