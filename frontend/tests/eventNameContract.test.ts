import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'
import { describe, expect, test } from 'vitest'
import { VIEW_MUTATING } from '../src/api/gameStream'

/**
 * **前端的事件名清单必须与后端 `EventType` 逐字一致。**
 *
 * ## 为什么需要这个测试
 *
 * `VIEW_MUTATING` 决定"哪些事件会触发重新拉取视图"。我第一版是按印象写的，
 * 里面有 `PLAYER_DIED`、`VOTE_RESOLVED`、`SPEECH_COMMITTED`、`REVEAL_PUBLISHED`
 * 四个**后端根本不存在**的事件名，而真正的 `PLAYER_KILLED`、`DEATH_ANNOUNCED`、
 * `VOTE_TALLY_RESOLVED`、`PLAYER_SPOKE` 一个都没写。
 *
 * 后果是**玩家死亡不刷新界面**——玩家列表永远停在开局那一刻。
 *
 * 而 `gameStream.test.ts` 发现不了它：那个测试喂的是我自己构造的事件名，
 * 因此它验证的是"清单里的名字能触发刷新"，**不是"清单里的名字是对的"**。
 * 自证式的测试无法发现命名错误——只有跨模块对照才能。
 *
 * ## 这个测试怎么工作
 *
 * 直接读后端源码里的 `EventType` 枚举，把两边对照。它是**跨模块契约测试**：
 * 后端改名、前端漏跟时会立刻失败，而失败信息会指出是哪个名字对不上。
 *
 * 代价是它依赖后端源码路径存在。这一点是刻意的：如果路径不见了，
 * 说明目录结构变了，而那时应该有人来看一眼——静默跳过会让契约无人守护。
 */

const here = dirname(fileURLToPath(import.meta.url))
const EVENT_TYPE_PATH = resolve(
  here,
  '../../backend/engine/src/main/java/com/wolfmind/domain/game/event/EventType.java',
)

/** 从后端源码里抽出枚举取值名。 */
function backendEventTypes(): Set<string> {
  const source = readFileSync(EVENT_TYPE_PATH, 'utf8')
  const names = new Set<string>()
  // 形如 `PLAYER_KILLED("PLAYER_KILLED"),`
  for (const match of source.matchAll(/^\s{4}([A-Z][A-Z0-9_]*)\("([A-Z][A-Z0-9_]*)"\)/gm)) {
    names.add(match[2]!)
  }
  return names
}

describe('事件名契约', () => {
  test('VIEW_MUTATING 里每个名字都是后端真实存在的事件', () => {
    const known = backendEventTypes()
    // 先确认解析成功：解析失败会让下面的断言"全部通过"。
    expect(known.size, `未能从 ${EVENT_TYPE_PATH} 解析事件类型`).toBeGreaterThan(20)

    const unknown = [...VIEW_MUTATING].filter((name) => !known.has(name))
    expect(unknown,
      `这些名字在后端 EventType 里不存在（拼错或事件已改名）：${unknown.join(', ')}。`
      + `\n后端现有：${[...known].sort().join(', ')}`)
      .toEqual([])
  })

  test('会改变视图的事件都被登记（漏一个的后果是界面滞后）', () => {
    const known = backendEventTypes()
    // 这些事件一定会改变界面上能看到的东西。
    // 漏掉其中任何一个，对应的界面变化就不会出现，而**不会有任何报错**。
    const mustRefresh = [
      'GAME_STARTED',      // 开局：座位与角色出现
      'ROLE_ASSIGNED',     // 我的角色
      'PHASE_CHANGED',     // 阶段与可用动作
      'PLAYER_KILLED',     // 存活状态
      'DEATH_ANNOUNCED',   // 死亡公布
      'VOTE_TALLY_RESOLVED', // 投票结果
      'GAME_FINISHED',     // 结束
      'PLAYER_SPOKE',      // 发言时间线
    ]
    const missing = mustRefresh.filter((name) => !VIEW_MUTATING.has(name))
    expect(missing, `这些事件必须触发刷新：${missing.join(', ')}`).toEqual([])
    // 顺带确认这份"必须"清单本身没有拼错。
    expect(mustRefresh.filter((name) => !known.has(name))).toEqual([])
  })

  test('每个登记的名字都确实存在于后端（防止清单里留死名）', () => {
    // 与第一条互补：第一条保证"清单 ⊆ 后端"，这一条把清单里的条目逐个数出来，
    // 让"新增一个拼错的名字"在 diff 里是可见的。
    expect(VIEW_MUTATING.size).toBeGreaterThanOrEqual(10)
    for (const name of VIEW_MUTATING) {
      expect(typeof name, `清单里出现了非字符串：${String(name)}`).toBe('string')
      expect(name, `事件名应为大写下划线：${name}`).toMatch(/^[A-Z][A-Z0-9_]*$/)
    }
  })
})
