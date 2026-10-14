import { ApiError, newIdempotencyKey, request } from '@/api/apiClient'
import { toDemoRoom, toDemoRoomSummary } from '@/api/mapRoom'
import type { Page, RoomView, StartGameResponse } from '@/api/restTypes'
import { LobbyError } from '@/features/lobby/types'
import type {
  CreateDemoRoom,
  DemoRoom,
  DemoRoomSummary,
  RoomAdapter,
} from '@/features/lobby/types'

/**
 * 真实房间适配器：把 `/rooms/*` 接到前端的 `RoomAdapter` 接口上。
 *
 * **错误翻译是这里的核心职责**。后端返回 `{code, message}`（api-spec.md §1），而前端界面
 * 已经按 `LobbyError.code` 分支（`mockRoomAdapter` 就是这么写的）。因此这里把
 * `ApiError` 转成 `LobbyError` 并**保留后端 code**——不去重新发明一套前端错误码，
 * 否则同一件事会有两种叫法，而"未就绪"在一个地方叫 `NOT_READY`、另一个地方叫
 * `ROOM_NOT_READY` 时，界面逻辑迟早按错的那套分支。
 *
 * **幂等键在适配器里生成**：每个写操作各自生成一个。派生自同一键（例如同一 `roomId`）
 * 会让"加入两次"被后端当成同一次重复提交而返回第一次的结果——用户会看到"点了没反应"。
 * 界面要重试同一个操作时必须复用同一个键，那是界面层的事（这里每次调用即一次用户意图）。
 */
export function createRealRoomAdapter(currentUserId: () => string | null): RoomAdapter {
  /** 把后端错误翻译成界面认识的 `LobbyError`，保留 code。 */
  const translate = <T>(promise: Promise<T>): Promise<T> =>
    promise.catch((error: unknown) => {
      if (error instanceof ApiError) throw new LobbyError(error.code, error.message)
      throw error
    })

  const roomPath = (roomId: string) => `/api/v1/rooms/${encodeURIComponent(roomId)}`

  /** 写操作的公共形状：带幂等键、POST、返回房间。 */
  const mutate = (path: string, body?: unknown): Promise<DemoRoom> =>
    translate(
      request<RoomView>(path, {
        method: 'POST',
        body: body ?? {},
        idempotencyKey: newIdempotencyKey('room'),
      }).then((view) => toDemoRoom(view, currentUserId())),
    )

  return {
    async list(): Promise<DemoRoomSummary[]> {
      const page = await translate(
        request<Page<RoomView>>('/api/v1/rooms?page=1&pageSize=50', {
          authenticated: false,
        }),
      )
      // 列表项不含座位明细，因此 mySeatNo 未知——`toDemoRoomSummary` 不假装知道。
      return page.items.filter(room => room.status === 'WAITING' || room.status === 'RUNNING').map(toDemoRoomSummary)
    },

    async get(roomId: string): Promise<DemoRoom> {
      const view = await translate(request<RoomView>(roomPath(roomId), { authenticated: false }))
      return toDemoRoom(view, currentUserId())
    },

    async create(input: CreateDemoRoom): Promise<DemoRoom> {
      return mutate('/api/v1/rooms', { name: input.name, config: { playerCount: 9 } })
    },

    async join(roomId: string): Promise<DemoRoom> {
      return mutate(`${roomPath(roomId)}/join`)
    },

    async leave(roomId: string): Promise<void> {
      await translate(
        request<RoomView>(`${roomPath(roomId)}/leave`, {
          method: 'POST',
          body: {},
          idempotencyKey: newIdempotencyKey('room'),
        }),
      )
    },

    async takeSeat(roomId: string, seatNo: number): Promise<DemoRoom> {
      return mutate(`${roomPath(roomId)}/seats/${seatNo}/take`)
    },

    async ready(roomId: string): Promise<DemoRoom> {
      // 后端的 /ready 返回 `{readyCount, allReady, room}`，不是房间本身——
      // 这一点与其它写路由不同，因此这里单独取 room 字段。
      const response = await translate(
        request<{ readyCount: number; allReady: boolean; room: RoomView }>(
          `${roomPath(roomId)}/ready`,
          { method: 'POST', body: {}, idempotencyKey: newIdempotencyKey('room') },
        ),
      )
      return toDemoRoom(response.room, currentUserId())
    },

    async setAi(roomId: string, seatNo: number, enabled: boolean): Promise<DemoRoom> {
      const path = `${roomPath(roomId)}/seats/${seatNo}/ai`
      if (!enabled) {
        const view = await translate(
          request<RoomView>(path, {
            method: 'DELETE',
            idempotencyKey: newIdempotencyKey('room'),
          }),
        )
        return toDemoRoom(view, currentUserId())
      }
      const response = await translate(
        request<{ seatNo: number; controller: unknown; room: RoomView }>(path, {
          method: 'POST',
          body: { agentConfigId: 'rule-baseline' },
          idempotencyKey: newIdempotencyKey('room'),
        }),
      )
      return toDemoRoom(response.room, currentUserId())
    },
  }
}

/**
 * 开局（api-spec.md §5.1）。
 *
 * 单独一个函数而不是塞进 `RoomAdapter`：`RoomAdapter` 是"大厅与等待房"的契约，
 * 而开局会**离开等待房进入对局**，属于另一个流程。把它塞进去会让
 * `RoomAdapter` 的实现者（包括 mock）都必须提供一个它不该关心的能力。
 */
export async function startGame(roomId: string, autoFillAi = true): Promise<StartGameResponse> {
  try {
    return await request<StartGameResponse>(
      `/api/v1/rooms/${encodeURIComponent(roomId)}/games`,
      {
        method: 'POST',
        body: { autoFillAi },
        idempotencyKey: newIdempotencyKey('start'),
      },
    )
  } catch (error) {
    if (error instanceof ApiError) throw new LobbyError(error.code, error.message)
    throw error
  }
}
