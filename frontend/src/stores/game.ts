import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { gameAdapter as selectedGameAdapter } from '@/api/adapters'
import { newIdempotencyKey } from '@/api/apiClient'
import type { GameAdapter, GameActionType, GameCommand, GamePresentation } from '@/features/game/types'

export const useGameStore = defineStore('game', () => {
  const view = ref<GamePresentation | null>(null)
  const loading = ref(false), busy = ref(false), error = ref(''), connection = ref<'local' | 'disconnected' | 'syncing'>('local')
  const selected = ref<number | 'abstain' | null>(null)
  const activeAction = ref<GameActionType | null>(null)
  const clock = ref(Date.now()), offset = ref(0)
  const remaining = computed(() => view.value?.deadline === null || !view.value ? null : Math.max(0, Math.ceil((view.value.deadline - clock.value - offset.value) / 1000)))
  const permission = computed(() => view.value?.permissions.find(p => p.type === activeAction.value))
  const actionable = computed(() => !loading.value && !busy.value && connection.value === 'local' && remaining.value !== 0)
  // 默认从注册表取：mock / real 的选择只发生在 pi/adapters.ts 一处。
// load(gameId, source) 仍可显式注入，测试走的就是那条路径。
let adapter: GameAdapter = selectedGameAdapter(), generation = 0, unsubscribe: (() => void) | undefined
  function accept(next: GamePresentation) {
    if (view.value && next.gameId === view.value.gameId && next.version < view.value.version) return
    // 阶段变化必须同时看 `phase` 与 `scenario`，只看其中一个都是错的：
    // - mock 适配器切换剧本时 `scenario` 变，`phase` 是新场景里的值；
    // - 真实适配器的 `scenario` **恒为"经典九人"**（后端没有剧本概念），
    //   因此只看 `scenario` 会让它永远判定"没变"——后果是阶段推进后 `activeAction`
    //   仍保留上一个阶段选中的动作，用户会在夜间看到"确认投票"这种按钮。
    const phaseChanged = next.phase !== view.value?.phase || next.scenario !== view.value?.scenario
    view.value = next
    offset.value = next.serverNow - Date.now()
    if (phaseChanged) selected.value = null
    if (phaseChanged || !next.permissions.some(p => p.type === activeAction.value)) activeAction.value = next.permissions.find(p => !['SPEAK', 'WOLF_CHAT'].includes(p.type))?.type || null
    if (typeof selected.value === 'number' && !permission.value?.targets.includes(selected.value)) selected.value = null
  }
  async function load(gameId: string, source: GameAdapter = adapter) {
    const current = ++generation; unsubscribe?.(); unsubscribe = undefined; adapter = source
    loading.value = true; busy.value = false; error.value = ''; view.value = null; selected.value = null; connection.value = 'syncing'
    try {
      const result = await adapter.get(gameId)
      if (current !== generation) return
      offset.value = result.serverNow - Date.now(); clock.value = Date.now(); accept(result); connection.value = 'local'
      unsubscribe = adapter.subscribe(gameId, next => { if (current === generation && connection.value !== 'disconnected') accept(next) }, state => { if (current === generation) connection.value = state })
    } catch (cause) { if (current === generation) { error.value = cause instanceof Error ? cause.message : '对局加载失败，请重试。'; connection.value = 'disconnected' } }
    finally { if (current === generation) loading.value = false }
  }
  async function changeScenario(scenario: string) {
    if (!view.value || busy.value || connection.value !== 'local') return
    const current = ++generation, id = view.value.gameId; unsubscribe?.(); busy.value = true; error.value = ''
    try {
      const result = await adapter.scenario(id, scenario)
      if (current !== generation) return
      offset.value = result.serverNow - Date.now(); clock.value = Date.now(); accept(result)
      unsubscribe = adapter.subscribe(id, next => { if (current === generation && connection.value !== 'disconnected') accept(next) }, state => { if (current === generation) connection.value = state })
    } catch (cause) { if (current === generation) error.value = cause instanceof Error ? cause.message : '场景切换失败。' }
    finally { if (current === generation) busy.value = false }
  }
  function chooseAction(type: GameActionType) { if (actionable.value && view.value?.permissions.some(p => p.type === type)) { activeAction.value = type; selected.value = null } }
  function select(seat: number | 'abstain') {
    if (!actionable.value || !permission.value) return
    if (seat === 'abstain' ? permission.value.allowEmpty : permission.value.targets.includes(seat)) selected.value = seat
  }
  async function submit(type: GameActionType, params: GameCommand['params']) {
    if (!view.value || !actionable.value || !view.value.permissions.some(p => p.type === type)) return false
    const current = generation, id = view.value.gameId
    busy.value = true; error.value = ''
    try {
      const result = await adapter.submit(id, { commandId: newIdempotencyKey('action'), expectedVersion: view.value.version, actionType: type, params })
      if (current !== generation) return false
      accept(result); return true
    } catch (cause) {
      if (current !== generation) return false
      error.value = cause instanceof Error ? cause.message : '提交失败，请重新同步。'
      try { const snapshot = await adapter.get(id); if (current === generation) accept(snapshot) } catch { if (current === generation) connection.value = 'disconnected' }
      return false
    } finally { if (current === generation) busy.value = false }
  }
  function disconnect() { ++generation; unsubscribe?.(); unsubscribe = undefined; connection.value = 'disconnected'; busy.value = false }
  async function reconnect() {
    if (!view.value) return
    const id = view.value.gameId, current = ++generation; unsubscribe?.(); connection.value = 'syncing'; busy.value = false; error.value = ''
    try {
      const result = await adapter.get(id)
      if (current !== generation) return
      offset.value = result.serverNow - Date.now(); clock.value = Date.now(); accept(result); connection.value = 'local'
      unsubscribe = adapter.subscribe(id, next => { if (current === generation && connection.value !== 'disconnected') accept(next) }, state => { if (current === generation) connection.value = state })
    } catch (cause) { if (current === generation) { error.value = cause instanceof Error ? cause.message : '同步失败。'; connection.value = 'disconnected' } }
  }
  function dispose() { ++generation; unsubscribe?.(); unsubscribe = undefined; view.value = null; loading.value = false; busy.value = false }
  return { view, loading, busy, error, connection, selected, activeAction, remaining, permission, actionable, clock, load, changeScenario, chooseAction, select, submit, disconnect, reconnect, dispose }
})
