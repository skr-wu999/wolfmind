import { createRouter, createWebHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'
import { sessionToken } from '@/api/config'

export const routes: RouteRecordRaw[] = [
  { path: '/', name: 'home', component: () => import('@/pages/HomePage.vue') },
  {
    path: '/lobby',
    name: 'lobby',
    component: () => import('@/pages/LobbyPage.vue'),
    meta: { requiresAuth: true },
  },
  // 登录页放在大厅之前：`/rooms/*` 与对局页都需要登录，而没有入口的话用户会被卡在 401 上。
  {
    path: '/login',
    name: 'login',
    component: () => import('@/pages/LoginPage.vue'),
    // 已登录的人不该再看到登录页：那会让他以为会话丢了。
    meta: { redirectIfAuthenticated: true },
  },
  {
    path: '/rooms/:roomId',
    name: 'room-waiting',
    component: () => import('@/pages/RoomWaitingPage.vue'),
    meta: { requiresAuth: true },
  },
  {
    path: '/games/:gameId',
    name: 'game',
    component: () => import('@/pages/GamePage.vue'),
    meta: { requiresAuth: true },
  },
  // 观战、回放、分析是**公开**的：观战的价值之一就是不登录也能看，
  // 而回放与分析的数据由服务端按可见性策略裁剪（AGENTS.md §8）。
  { path: '/watch/:gameId', name: 'ai-watch', component: () => import('@/pages/AIWatchPage.vue') },
  { path: '/games/:gameId/replay', name: 'replay', component: () => import('@/pages/ReplayPage.vue') },
  { path: '/games/:gameId/analysis', name: 'analysis', component: () => import('@/pages/AnalysisPage.vue') },
  { path: '/lab', name: 'lab', component: () => import('@/pages/LabPage.vue') },
  { path: '/:pathMatch(.*)*', redirect: { name: 'home' } },
]

export const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes,
  scrollBehavior: (_to, _from, savedPosition) => savedPosition ?? { top: 0 },
})

/**
 * 路由守卫。
 *
 * **判据是 `sessionToken()` 而不是 `authStore.authenticated`**：会话 token 是
 * 唯一真正的凭据（它在 `localStorage` 里，跨刷新存活），而 store 的
 * `authenticated` 是它的派生值。守卫在导航时运行，而 store 可能还没被用过——
 * 依赖 store 会让"刷新页面后守卫失效"，那是最典型的症状。
 * 另外这样写也让守卫在 mock 适配器下同样正确（它只关心有没有 token）。
 *
 * **带 `redirect` 回跳**：登录成功后回到用户原本想去的页面。
 * 不带的话，用户点了一条对局链接、被弹到登录页、登录完落在大厅——
 * 他必须自己再找一次那个对局。这是"能登录"与"能用"的差别。
 */
router.beforeEach((to) => {
  const hasSession = sessionToken() !== null

  if (to.meta.requiresAuth && !hasSession) {
    return {
      name: 'login',
      // 用 fullPath 而不是 path：查询串与 hash 一起带上，否则回跳会丢参数。
      query: { redirect: to.fullPath },
    }
  }

  if (to.meta.redirectIfAuthenticated && hasSession) {
    return { name: 'lobby' }
  }

  return true
})
