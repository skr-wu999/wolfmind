/**
 * 端到端冒烟验证：真实后端 + 真实 WebSocket。
 *
 * **这个脚本存在的理由**：前面 13 轮里每一段都有单元测试，但没有一次把它们连起来跑过。
 * 单元测试用替身覆盖了各自的契约，而"两端说的是不是同一件事"只有真实连接能证明——
 * 比如前端发 `{type:'AUTH',payload:{token}}` 而网关期待 `{id,type,payload}` 时，
 * 两边各自的测试都会通过。
 *
 * 它按一个真实用户的操作顺序走：
 *   注册 → 登录 → 建房 → 第二人入座 → 两人准备 → 开局（AI 补位）
 *   → WebSocket 认证 → 订阅对局 → 收到事件 → REST 读视图 → 提交行动
 *
 * 用法：node e2e-smoke.mjs [baseUrl]   默认 http://127.0.0.1:18080
 * 退出码非 0 表示失败。
 */

const BASE = process.argv[2] ?? 'http://127.0.0.1:18080'
const WS = BASE.replace(/^http/, 'ws') + '/ws'

let passed = 0
const failures = []

function check(label, condition, detail = '') {
  if (condition) {
    passed += 1
    console.log(`  ok   ${label}`)
  } else {
    failures.push(`${label}${detail ? ` — ${detail}` : ''}`)
    console.log(`  FAIL ${label}${detail ? ` — ${detail}` : ''}`)
  }
}

/** 发一次请求并返回 {status, body}；非 2xx 不抛，便于断言状态码。 */
async function call(method, path, { token, idempotencyKey, body } = {}) {
  const headers = { Accept: 'application/json' }
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  if (idempotencyKey) headers['Idempotency-Key'] = idempotencyKey
  if (token) headers.Authorization = `Bearer ${token}`
  const response = await fetch(BASE + path, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const text = await response.text()
  let parsed
  try {
    parsed = text ? JSON.parse(text) : undefined
  } catch {
    parsed = text
  }
  return { status: response.status, body: parsed }
}

const suffix = Math.random().toString(36).slice(2, 10)

async function account(prefix) {
  const username = `${prefix}_${suffix}`
  const password = 'correct-horse-battery'
  const registered = await call('POST', '/api/v1/auth/register', {
    body: { username, password },
  })
  if (registered.status !== 201) {
    throw new Error(`注册失败 ${registered.status}: ${JSON.stringify(registered.body)}`)
  }
  const loggedIn = await call('POST', '/api/v1/auth/login', { body: { username, password } })
  if (loggedIn.status !== 200) {
    throw new Error(`登录失败 ${loggedIn.status}: ${JSON.stringify(loggedIn.body)}`)
  }
  return {
    userId: loggedIn.body.userId,
    token: loggedIn.body.sessionToken,
    username,
    registered: registered.body,
  }
}

/** 建一个 WebSocket，完成 AUTH 与 SUBSCRIBE，收集帧。 */
function openGameSocket(token, gameId) {
  return new Promise((resolve, reject) => {
    const socket = new WebSocket(WS)
    const frames = []
    const timeout = setTimeout(() => reject(new Error(`WebSocket 超时，已收到：${frames.map((f) => f.type)}`)), 15000)

    socket.addEventListener('open', () => {
      socket.send(JSON.stringify({ id: 'c_auth', type: 'AUTH', payload: { token } }))
    })
    socket.addEventListener('message', (event) => {
      const frame = JSON.parse(event.data)
      frames.push(frame)
      if (frame.type === 'AUTH_OK') {
        socket.send(
          JSON.stringify({ id: 'c_sub', type: 'SUBSCRIBE', payload: { gameId, lastSeq: 0 } }),
        )
      }
      // 拿到 SUBSCRIBED 就认为握手完成，后续事件继续收集。
      if (frame.type === 'SUBSCRIBED') {
        clearTimeout(timeout)
        resolve({ socket, frames })
      }
      if (frame.type === 'ERROR') {
        clearTimeout(timeout)
        reject(new Error(`WebSocket 返回 ERROR：${JSON.stringify(frame.payload)}`))
      }
    })
    socket.addEventListener('error', () => {
      clearTimeout(timeout)
      reject(new Error('WebSocket 连接失败'))
    })
  })
}

const wait = (ms) => new Promise((r) => setTimeout(r, ms))

async function main() {
  console.log(`端到端冒烟：${BASE}\n`)

  console.log('1) 注册与登录')
  const owner = await account('e2e_owner')
  const guest = await account('e2e_guest')
  check('两个账号都能注册并登录', !!owner.token && !!guest.token)
  check('注册响应不含令牌（api-spec.md §3.1）', !('sessionToken' in owner.registered), JSON.stringify(owner.registered))
  check('注册响应不含口令', !JSON.stringify(owner.registered).includes('correct-horse-battery'))

  console.log('\n2) 建房与入座')
  const created = await call('POST', '/api/v1/rooms', {
    token: owner.token,
    idempotencyKey: `e2e_room_${suffix}`,
    body: { name: '端到端验证房', config: { playerCount: 9 } },
  })
  check('建房返回 201', created.status === 201, `${created.status} ${JSON.stringify(created.body)}`)
  const roomId = created.body?.roomId
  check('房主自动占 1 号座', created.body?.ownerSeatNo === 1, JSON.stringify(created.body?.ownerSeatNo))
  check('返回 9 个座位（含空座）', Array.isArray(created.body?.seats) && created.body.seats.length === 9)
  check('控制器是嵌套的 {type,refId}', created.body?.seats?.[0]?.controller?.type === 'HUMAN')

  const joined = await call('POST', `/api/v1/rooms/${roomId}/join`, {
    token: guest.token,
    idempotencyKey: `e2e_join_${suffix}`,
    body: {},
  })
  check('第二人入座返回 200', joined.status === 200, JSON.stringify(joined.body))
  check('入座后 occupied 为 2', joined.body?.occupied === 2, String(joined.body?.occupied))

  const missingKey = await call('POST', `/api/v1/rooms/${roomId}/join`, { token: guest.token, body: {} })
  check('缺 Idempotency-Key 返回 400', missingKey.status === 400, String(missingKey.status))

  const anonymous = await call('POST', '/api/v1/rooms', {
    idempotencyKey: `e2e_anon_${suffix}`,
    body: { name: '匿名房' },
  })
  check('未登录建房返回 401', anonymous.status === 401, String(anonymous.status))

  console.log('\n3) 未就绪时开局应被拒')
  const tooEarly = await call('POST', `/api/v1/rooms/${roomId}/games`, {
    token: owner.token,
    idempotencyKey: `e2e_early_${suffix}`,
    body: { autoFillAi: true },
  })
  // 期望 NOT_READY：房间"没坐满"先于"没就绪"被检查（坐不满时讨论谁没准备还没有意义），
  // 两者都是 NOT_READY，因此断言状态码与码值，不断言消息文案（文案会改）。
  check('未就绪时开局被拒（409 NOT_READY）',
    tooEarly.status === 409 && tooEarly.body?.code === 'NOT_READY',
    `${tooEarly.status} ${JSON.stringify(tooEarly.body?.code)}`)

  console.log('\n4) 两人准备并开局（AI 补位）')
  for (const [who, account] of [['owner', owner], ['guest', guest]]) {
    const ready = await call('POST', `/api/v1/rooms/${roomId}/ready`, {
      token: account.token,
      idempotencyKey: `e2e_ready_${who}_${suffix}`,
      body: {},
    })
    check(`${who}准备返回 200`, ready.status === 200, JSON.stringify(ready.body))
  }

  const started = await call('POST', `/api/v1/rooms/${roomId}/games`, {
    token: owner.token,
    idempotencyKey: `e2e_start_${suffix}`,
    body: { autoFillAi: true },
  })
  check('开局返回 201', started.status === 201, `${started.status} ${JSON.stringify(started.body)}`)
  const gameId = started.body?.gameId
  check('开局响应带 gameId', typeof gameId === 'string' && gameId.length > 0, JSON.stringify(started.body))
  check('开局产生 11 条事件（version=11）', started.body?.version === 11, String(started.body?.version))
  check('开局后阶段为 NIGHT_WOLF', started.body?.phase === 'NIGHT_WOLF', String(started.body?.phase))
  // AI 补位的数量**用关系断言而不是硬编码数字**：本脚本是 2 真人 + 7 AI，
  // 而单测里是 1 真人 + 8 AI。写死数字会在场景变化时假失败，
  // 而"在岗 Agent 数 == AI 座位数"才是真正的不变量。
  const roomAfterStart = await call('GET', `/api/v1/rooms/${roomId}`)
  const aiSeatCount = (roomAfterStart.body?.seats ?? []).filter(
    (seat) => seat.controller?.type === 'AI',
  ).length
  check('AI 补位与 AI 座位数一致',
    started.body?.agentCount === aiSeatCount,
    `agentCount=${started.body?.agentCount} AI 座位=${aiSeatCount}`)

  const frozen = await call('POST', `/api/v1/rooms/${roomId}/join`, {
    token: guest.token,
    idempotencyKey: `e2e_after_${suffix}`,
    body: {},
  })
  check('开局后入座被拒（409 GAME_ALREADY_STARTED）',
    frozen.status === 409 && frozen.body?.code === 'GAME_ALREADY_STARTED',
    `${frozen.status} ${JSON.stringify(frozen.body?.code)}`)

  const restart = await call('POST', `/api/v1/rooms/${roomId}/games`, {
    token: owner.token,
    idempotencyKey: `e2e_restart_${suffix}`,
    body: { autoFillAi: false },
  })
  check('重复开局返回同一局（幂等）', restart.body?.gameId === gameId, JSON.stringify(restart.body?.gameId))

  console.log('\n5) REST 读对局视图')
  const view = await call('GET', `/api/v1/games/${gameId}`)
  check('读对局返回 200', view.status === 200, `${view.status} ${JSON.stringify(view.body)}`)
  check('对局状态为 RUNNING', view.body?.status === 'RUNNING', String(view.body?.status))
  check('对局含 9 个玩家', Array.isArray(view.body?.players) && view.body.players.length === 9,
    String(view.body?.players?.length))
  check('**permissions[] 存在**（api-spec.md §5.2 的服务端投影）',
    Array.isArray(view.body?.permissions), JSON.stringify(view.body?.permissions))

  console.log('\n6) WebSocket 认证与订阅（真实连接）')
  // 订阅前记录一次权威版本：`SUBSCRIBED.to` 是流尾，而它只增不减。
  // 用它做判据而不是硬编码数字——AI 在订阅的瞬间仍在推进对局。
  const beforeSubscribeVersion =
    (await call('GET', `/api/v1/games/${gameId}`, { token: owner.token })).body?.version
  const ownerSocket = await openGameSocket(owner.token, gameId)
  const authOk = ownerSocket.frames.find((f) => f.type === 'AUTH_OK')
  check('AUTH_OK 到达', !!authOk)
  check('AUTH_OK 带协商参数', authOk?.payload?.heartbeatIntervalMs === 15000,
    String(authOk?.payload?.heartbeatIntervalMs))
  check('AUTH_OK 不回显令牌', !JSON.stringify(authOk).includes(owner.token))

  const subscribed = ownerSocket.frames.find((f) => f.type === 'SUBSCRIBED')
  check('SUBSCRIBED 到达', !!subscribed)
  /*
   * SUBSCRIBED.to 必须是**真实流尾**，且请求区间未截断。
   *
   * 这里原先把期望值硬编码成 11，而 AI 驱动每 200ms 推进一次对局：
   * 订阅发生在 REST 探测之后，那一刻的流尾可能已经是 12+，
   * 于是这条检查**大约每 4 次失败 1 次**。偶发失败的检查比没有检查更糟——
   * 它训练人忽略失败。
   *
   * 要守的性质是"报的是流尾而不是别的东西"（例如补发列表的长度）：
   * 流尾只增不减，因此它必须**不小于**订阅前观测到的版本，也不能
   * 比它多出一个不合理的量（真实流尾与观测值之间的差只来自 AI 的推进）。
   * 而 truncated 必须为假——请求区间（lastSeq=0）永远落在流尾之内。
   */
  const tail = subscribed?.payload?.to
  const gap = typeof tail === 'number' ? tail - beforeSubscribeVersion : null
  check('SUBSCRIBED.to 是数字', typeof tail === 'number', String(tail))
  check('SUBSCRIBED.to 不小于订阅前的版本',
    gap !== null && gap >= 0, `${tail} vs ${beforeSubscribeVersion}`)
  check('SUBSCRIBED.to 与观测版本的差在合理范围内（确实在推进而不是别的东西）',
    gap !== null && gap < 50, `差 ${gap}`)
  check('SUBSCRIBED.truncated 为假（lastSeq=0 永远在流尾之内）',
    subscribed?.payload?.truncated === false, String(subscribed?.payload?.truncated))

  // 等快照与后续实时事件落地。
  await wait(500)

  /*
   * 首次订阅（lastSeq=0）由服务端发授权的 GAME_SNAPSHOT_SYNC 作为基线，
   * 不再把历史补发成 GAME_EVENT（websocket-protocol.md §8.3）。
   * 可见性因此在快照上验证：能看到的角色只有自己，狼人另外能看到狼队友。
   */
  const visibleRolesOk = (snapshot) => {
    const me = snapshot?.me
    if (!me?.role) return false
    return (snapshot.players ?? []).every((player) => player.role == null
      || player.seatNo === me.seatNo
      || (me.role === 'WEREWOLF' && player.role === 'WEREWOLF'))
  }

  console.log('\n7) 可见性过滤（端到端最关键的一条）')
  const ownerSnapshot = ownerSocket.frames.find((f) => f.type === 'GAME_SNAPSHOT_SYNC')?.payload
  check('首次订阅收到 GAME_SNAPSHOT_SYNC 基线', !!ownerSnapshot)
  check('快照基线不早于订阅前的版本',
    typeof ownerSnapshot?.version === 'number' && ownerSnapshot.version >= beforeSubscribeVersion,
    `${ownerSnapshot?.version} vs ${beforeSubscribeVersion}`)
  check('房主快照只含自己（及狼队友）的角色', visibleRolesOk(ownerSnapshot),
    JSON.stringify((ownerSnapshot?.players ?? []).map((p) => [p.seatNo, p.role])))
  const ownerEvents = ownerSocket.frames.filter((f) => f.type === 'GAME_EVENT')
  check('首次订阅不补发 ROLE_ASSIGNED 历史',
    !ownerEvents.some((f) => f.payload?.type === 'ROLE_ASSIGNED'),
    ownerEvents.map((f) => f.payload?.type).join(','))
  check('所有事件的 seq 严格递增',
    ownerEvents.every((f, i) => i === 0 || f.seq > ownerEvents[i - 1].seq),
    ownerEvents.map((f) => f.seq).join(','))

  const guestSocket = await openGameSocket(guest.token, gameId)
  await wait(500)
  const guestSnapshot = guestSocket.frames.find((f) => f.type === 'GAME_SNAPSHOT_SYNC')?.payload
  check('客人快照只含自己（及狼队友）的角色', visibleRolesOk(guestSnapshot),
    JSON.stringify((guestSnapshot?.players ?? []).map((p) => [p.seatNo, p.role])))
  check('两人快照里的"我"是各自的座位',
    ownerSnapshot?.me?.seatNo != null && guestSnapshot?.me?.seatNo != null
      && ownerSnapshot.me.seatNo !== guestSnapshot.me.seatNo,
    `${ownerSnapshot?.me?.seatNo} vs ${guestSnapshot?.me?.seatNo}`)

  console.log('\n8) 心跳与未认证订阅')
  const pongPromise = new Promise((resolve) => {
    const timer = setTimeout(() => resolve(false), 5000)
    ownerSocket.socket.addEventListener('message', (event) => {
      if (JSON.parse(event.data).type === 'PONG') {
        clearTimeout(timer)
        resolve(true)
      }
    })
  })
  ownerSocket.socket.send(JSON.stringify({ id: 'c_ping', type: 'PING', payload: {} }))
  check('PING 收到 PONG', await pongPromise)

  const unauth = await new Promise((resolve) => {
    const socket = new WebSocket(WS)
    const frames = []
    const timer = setTimeout(() => resolve(frames), 5000)
    socket.addEventListener('open', () => {
      socket.send(JSON.stringify({ id: 'c_sub', type: 'SUBSCRIBE', payload: { gameId, lastSeq: 0 } }))
    })
    socket.addEventListener('message', (event) => {
      frames.push(JSON.parse(event.data))
      if (frames.some((f) => f.type === 'ERROR')) {
        clearTimeout(timer)
        socket.close()
        resolve(frames)
      }
    })
  })
  check('未认证订阅返回 AUTH_REQUIRED',
    unauth.some((f) => f.type === 'ERROR' && f.payload?.code === 'AUTH_REQUIRED'),
    JSON.stringify(unauth.map((f) => [f.type, f.payload?.code])))
  check('未认证订阅不得出现 SUBSCRIBED', !unauth.some((f) => f.type === 'SUBSCRIBED'))

  console.log('\n9) 提交行动')
  const action = await call('POST', `/api/v1/games/${gameId}/actions`, {
    token: owner.token,
    idempotencyKey: `e2e_action_${suffix}`,
    body: { actionType: 'WOLF_KILL', params: { targetSeatNo: 9 }, expectedVersion: 11 },
  })
  // 房主是否是狼、是否轮到它都由服务端决定，因此这里只断言"接口存在且给出结构化回答"，
  // 不断言成功——把 200 当成必然会让脚本在角色随机分配时假失败。
  check('行动接口可达并返回结构化结果或明确的领域错误',
    action.status === 200 || (action.status >= 400 && typeof action.body?.code === 'string'),
    `${action.status} ${JSON.stringify(action.body)}`)

  ownerSocket.socket.close()
  guestSocket.socket.close()

  console.log(`\n通过 ${passed} 项，失败 ${failures.length} 项`)
  if (failures.length > 0) {
    console.log('\n失败明细：')
    for (const f of failures) console.log(`  - ${f}`)
    process.exit(1)
  }
}

main().catch((error) => {
  console.error('\n端到端脚本异常：', error.message)
  process.exit(1)
})
