import { ref } from 'vue'
import { defineStore } from 'pinia'
import { setCurrentUserProvider, setMySeatNoProvider } from '@/api/adapters'
import { useAuthStore } from './auth'
import { setSessionExpiryHandler } from '@/api/config'

/**
 * 当前对局的座位号。
 *
 * ## 为什么它必须存在
 *
 * 服务端视图按可见性脱敏，但**没有单独的"我"**——`GameView.players[].role` 只表达
 * "哪些角色对我可见"，而"哪个座位是我"是 `ViewerContext` 的职责，不下发。
 * 因此界面必须自己知道自己的座位号，否则对局页算不出 `me`，
 * 只能隐藏整个"我的操作"区域（第 13 轮就是这样，注入 null）。
 *
 * ## 为什么不做成 computed
 *
 * 它有两个互不相同的来源，而**任何一个都可能先到**：
 * 1. 房间页入座后知道座位号（`DemoRoom.mySeatNo`）；
 * 2. 对局视图的 `players` 里 `controller.refId === userId` 的那个座位。
 *
 * 做成 computed 需要在一个地方同时读这两个来源，而它们分属房间与对局两个 store——
 * 那会造出"房间 store 依赖对局 store"这类反向依赖。用一个可以被任一方写入的 ref
 * 更简单，也更接近事实：**座位号是"当前会话的位置"，不是某一份数据的派生值**。
 *
 * ## 什么时候必须清空
 *
 * 离开房间或对局时必须清：留着旧座位号会让下一次进入**另一个**对局时，
 * 界面以那个旧座位渲染操作区——用户会看到自己的按钮指向别人的座位。
 * 座位号在服务端校验，所以不会真的以别人身份行动，但界面会显示错误的可操作目标。
 */
export const useSeatStore = defineStore('seat', () => {
  const seatNo = ref<number | null>(null)

  /** 记录座位号；越界值直接忽略（座位是 1..9）。 */
  function set(value: number | null): void {
    if (value === null) {
      seatNo.value = null
      return
    }
    if (!Number.isInteger(value) || value < 1 || value > 9) return
    seatNo.value = value
  }

  function clear(): void {
    seatNo.value = null
  }

  return { seatNo, set, clear }
})

/**
 * 把注册表的身份提供者接到各 store。
 *
 * **在挂载之前调用一次**（`main.ts`），且只在这里做——散在各处注入会让
 * "某个页面用了另一个提供者"这种问题出现，而适配器读到的身份会随页面而变。
 *
 * 注入的是**读取函数**而不是值：令牌与座位号都会在登录、入座、离开后变化，
 * 注入快照会让适配器一直拿着旧身份。
 */
export function wireAdapterProviders(onSessionExpired?: () => void): void {
  setCurrentUserProvider(() => useAuthStore().userId)
  setMySeatNoProvider(() => useSeatStore().seatNo)
  setSessionExpiryHandler(() => {
    useAuthStore().clear()
    useSeatStore().clear()
    onSessionExpired?.()
  })
}
