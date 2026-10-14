import { USE_REAL_API } from '@/api/config'
import { realObservationAdapter } from '@/features/observation/realObservationAdapter'
import { realAnalysisAdapter } from '@/features/analysis/realAnalysisAdapter'
import { realLabAdapter } from '@/features/lab/realLabAdapter'
import { createRealRoomAdapter } from '@/api/realRoomAdapter'
import { createRealGameAdapter } from '@/api/realGameAdapter'
import { roomAdapter as mockRoomAdapter } from '@/features/lobby/mockRoomAdapter'
import { gameAdapter as mockGameAdapter } from '@/features/game/mockGameAdapter'
import { observationAdapter as mockObservationAdapter } from '@/features/observation/mockObservationAdapter'
import { analysisAdapter as mockAnalysisAdapter } from '@/features/analysis/mockAnalysisAdapter'
import { labAdapter as mockLabAdapter } from '@/features/lab/mockLabAdapter'
import type { RoomAdapter } from '@/features/lobby/types'
import type { AnalysisAdapter } from '@/features/analysis/types'
import type { LabAdapter } from '@/features/lab/types'

/**
 * 适配器注册表：**唯一的接入选择点**。
 *
 * 存在的理由是一条具体的约束：`VITE_USE_REAL_API` 的值在构建时固定，而"用真实后端还是
 * mock"这个决定必须只做一次。散在每个 store 里各判断一次，就会出现"大厅走了真实接口、
 * 观战页还在吃 mock 数据"——那时页面上显示的对局在大厅里根本不存在，而这类不一致
 * 极难排查，因为两边都"看起来正常工作"。
 *
 * 默认 mock。理由见 `config.ts`：mock 默认能跑，real 默认在后端未启动时整页报错，
 * 而开发与演示是更常见的场景。
 *
 * 测试**不受这里影响**：测试直接构造 mock 适配器并注入（`createLobbyState(adapter)`、
 * `useObservationStore` + 模块级 mock），因此替换真实后端不会改动任何既有断言。
 */

let currentUserId: () => string | null = () => null
let mySeatNo: () => number | null = () => null

/**
 * 注入"当前登录账号"的读取函数。
 *
 * 适配器需要它来算出"哪个座位是我"（服务端返回的 `playerId` 是座位标识，不是账号）。
 * 用回调而不是直接 import store：适配器是基础设施，依赖 store 会形成反向依赖，
 * 也会让适配器在测试里必须拉起 Pinia。
 */
export function setCurrentUserProvider(provider: () => string | null): void {
  currentUserId = provider
}

/**
 * 注入"我在当前对局的座位号"；观战者返回 null。
 *
 * 真实对局适配器需要它才能算出视图里的 `me`——服务端视图按可见性脱敏，但**没有单独的"我"**
 * （那是 `ViewerContext` 的职责）。因此"哪个座位是我"必须由界面自己知道：来自房间的
 * `mySeatNo`，或按账号反查。传 null 时 `me.seatNo` 为 0，界面据此隐藏"我的操作"区域，
 * 这对观战页正是期望行为。
 */
export function setMySeatNoProvider(provider: () => number | null): void {
  mySeatNo = provider
}

/** 房间适配器：大厅与等待房。 */
export function roomAdapter(): RoomAdapter {
  return USE_REAL_API ? createRealRoomAdapter(currentUserId) : mockRoomAdapter
}

/**
 * 对局适配器：REST 取权威视图 + WebSocket 驱动刷新。
 *
 * 真实实现需要知道"我的座位号"才能算出视图里的 `me`——服务端视图按可见性脱敏，
 * 但没有单独的"我"字段（那是 `ViewerContext` 的职责）。观战者返回 null。
 */
export function gameAdapter() {
  return USE_REAL_API ? createRealGameAdapter({ mySeatNo: () => mySeatNo() }) : mockGameAdapter
}

/**
 * 观战适配器。
 *
 * **已接真实后端**：数据来自 `GET /games/{id}` + `GET /games/{id}/replay`，
 * 而 replay 端点按 `ViewerContext` 做可见性过滤（与 WebSocket 同一条实现）。
 * 因此"村民看不到狼人密谈"由服务端保证，前端不做裁剪——前端不是权威（AGENTS.md §9）。
 *
 * 未结束的对局不披露角色（观战页是公开路由，披露身份等于泄漏隐藏信息）。
 */
export function observationAdapter() {
  return USE_REAL_API ? realObservationAdapter : mockObservationAdapter
}

/**
 * 分析适配器。
 *
 * **已接真实后端**（Phase 5）：`GET /games/{id}/analysis/summary` 与
 * `GET /games/{id}/analysis/agents/{playerId}`。服务端只给运行时可观察的事实，
 * 因此真实数据里没有胜率估计与信念曲线——那两项在页面上显示"暂无数据"，
 * 而不是由前端编一组数字（分析页上任何一个像结论的数字都会被当成事实引用）。
 *
 * 演示模式仍走 mock：没有后端时页面仍可打开，且清楚地标着"演示数据"。
 */
export function analysisAdapter(): AnalysisAdapter {
  return USE_REAL_API ? realAnalysisAdapter : mockAnalysisAdapter
}

/**
 * 实验室适配器。
 *
 * **已接真实后端**（Phase 5）：`/lab/agent-configs`、`/lab/batches`、`/lab/comparisons`。
 * 批次对局由服务端用与真人对局相同的路径推进，因此实验室结论对真人对局有效。
 */
export function labAdapter(): LabAdapter {
  return USE_REAL_API ? realLabAdapter : mockLabAdapter
}

/** 当前是否走真实后端；界面据此显示"演示数据"提示。 */
export function usingRealBackend(): boolean {
  return USE_REAL_API
}
