import { computed, ref, watch } from 'vue'
import { defineStore } from 'pinia'
import { roomAdapter as selectedRoomAdapter } from '@/api/adapters'
import { LobbyError } from '@/features/lobby/types'
import type { CreateDemoRoom, DemoRoomSummary, LobbyFilter, RoomAdapter } from '@/features/lobby/types'

export function createLobbyState(adapter: RoomAdapter) {
  const rooms = ref<DemoRoomSummary[]>([])
  const filter = ref<LobbyFilter>('ALL')
  const page = ref(1)
  const loading = ref(false)
  const loaded = ref(false)
  const pendingRoomId = ref<string | null>(null)
  const creating = ref(false)
  const error = ref('')
  const actionError = ref('')
  let requestVersion = 0
  const filteredRooms = computed(() => rooms.value.filter((room) => filter.value === 'ALL'
    || (filter.value === 'PUBLIC' ? room.visibility === 'PUBLIC' : room.status === filter.value)))
  const pageCount = computed(() => Math.max(1, Math.ceil(filteredRooms.value.length / 6)))
  const visibleRooms = computed(() => filteredRooms.value.slice((page.value - 1) * 6, page.value * 6))
  watch(filter, () => { page.value = 1 }, { flush: 'sync' })
  watch(pageCount, (count) => { page.value = Math.min(page.value, count) }, { flush: 'sync' })
  const message = (cause: unknown) => cause instanceof LobbyError ? cause.message : '暂时无法读取房间，请稍后重试。'
  async function refresh() {
    const version = ++requestVersion
    loading.value = true
    error.value = ''
    try {
      const items = await adapter.list()
      if (version === requestVersion) { rooms.value = items; loaded.value = true }
    } catch (cause) { if (version === requestVersion) error.value = message(cause) }
    finally { if (version === requestVersion) loading.value = false }
  }
  async function join(roomId: string) {
    if (pendingRoomId.value !== null) return null
    actionError.value = ''
    pendingRoomId.value = roomId
    try { return await adapter.join(roomId) }
    catch (cause) { actionError.value = message(cause); return null }
    finally { pendingRoomId.value = null }
  }
  async function create(input: CreateDemoRoom) {
    if (creating.value) return null
    actionError.value = ''
    creating.value = true
    try { return await adapter.create(input) }
    catch (cause) { actionError.value = message(cause); return null }
    finally { creating.value = false }
  }
  return { rooms, filter, page, pageCount, filteredRooms, visibleRooms, loading, loaded, pendingRoomId, creating, error, actionError, refresh, join, create }
}
// 默认从注册表取适配器：mock / real 的选择只发生在 pi/adapters.ts 一处。
// 测试仍然显式注入自己的适配器（createLobbyState(adapter)），因此不受这里影响。
export const useLobbyStore = defineStore('lobby', () => createLobbyState(selectedRoomAdapter()))
