// @vitest-environment jsdom
import { afterEach, beforeEach, expect, test } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import GameChatPanel from '../src/components/GameChatPanel.vue'
import { chatChannels, chatItems } from '../src/features/game/chat'
import { createMockGameAdapter } from '../src/features/game/mockGameAdapter'
import type { GamePresentation } from '../src/features/game/types'
let wrapper: VueWrapper | undefined
beforeEach(() => sessionStorage.clear())
afterEach(() => { wrapper?.unmount(); wrapper = undefined; document.body.innerHTML = '' })
async function fixture(scenario = 'discussion') { return createMockGameAdapter().scenario('demo', scenario) }
async function render(view: GamePresentation) {
  wrapper = mount(GameChatPanel, { attachTo: document.body, props: { view, actionable: true, busy: false, validation: '', error: '', active: true, speechDraft: '', wolfDraft: '' }, global: { stubs: { PixelAvatar: true } } })
  await flushPromises(); return wrapper
}
test('chat orders committed facts and drops redundant speech notices', async () => {
  const view = await fixture(); view.preview = null
  view.records.push({ id: 'speech-2', text: '同一条发言已提交', scope: 'PUBLIC', round: 2, order: 4 })
  expect(chatItems(view, 'PUBLIC').map(item => item.content)).toEqual([view.records[0]!.text, view.messages[0]!.content, view.messages[1]!.content, view.records[1]!.text])
})
test('one speech ID remains stable from streamed preview to accepted final message', async () => {
  const view = await fixture(), key = 'PUBLIC:speech:speech-3'
  view.preview!.content = '第一段'; expect(chatItems(view, 'PUBLIC').find(item => item.id === key)?.streaming).toBe(true)
  view.messages.push({ id: 'speech-3', seatNo: 3, round: 2, scope: 'PUBLIC', content: '第一段与最终内容' }); view.preview = null
  expect(chatItems(view, 'PUBLIC').filter(item => item.id === key)).toHaveLength(1)
  expect(chatItems(view, 'PUBLIC').find(item => item.id === key)?.streaming).toBeUndefined()
  expect(chatItems(view, 'PUBLIC').at(-1)?.id).toBe(key)
})
test('a role alone never creates private or faction channels', async () => {
  const view = await fixture(); view.me.role = 'WEREWOLF'; view.privateRecords = []; view.wolfMessages = []; view.permissions = []; view.privateResult = null
  expect(chatChannels(view)).toEqual(['PUBLIC']); expect(chatItems(view, 'WOLF')).toEqual([])
})
test('private and wolf records stay out of public bubbles and require explicit channel selection', async () => {
  const view = await fixture('wolf'), page = await render(view)
  expect(page.find('.game-turn-order').exists()).toBe(false); expect(page.get('.game-chat-header').text()).toContain('公开记录')
  expect(page.get('.game-chat-list').text()).not.toContain('我建议先讨论一下 1 号')
  await page.get('.game-chat-channels button:last-child').trigger('click')
  expect(page.get('.game-chat-list').text()).toContain('我建议先讨论一下 1 号'); expect(page.find('#speech-content').exists()).toBe(false)
  await page.setProps({ view: { ...view, wolfMessages: [], privateRecords: [], permissions: [] } }); await flushPromises()
  expect(page.get('.game-chat-list').text()).not.toContain('我建议先讨论一下 1 号'); expect(page.find('.wolf-chat-form').exists()).toBe(false)
})
test('private results are readable in their channel and are never copied into public history', async () => {
  const view = await fixture('seer'), page = await render(view)
  expect(page.get('.game-chat-list').text()).not.toContain('非狼人阵营')
  await page.get('.game-chat-channels button:last-child').trigger('click')
  expect(page.get('.game-chat-list').text()).toContain('非狼人阵营'); expect(page.find('textarea').exists()).toBe(false)
})
test('reading earlier messages keeps scroll position and shows a return-to-latest reminder', async () => {
  const view = await fixture(), page = await render(view), list = page.get('.game-chat-list').element as HTMLElement
  Object.defineProperty(list, 'scrollHeight', { configurable: true, value: 900 }); Object.defineProperty(list, 'clientHeight', { configurable: true, value: 200 })
  list.scrollTop = 100; await page.get('.game-chat-list').trigger('scroll')
  const next = { ...view, messages: [...view.messages, { id: 'new', seatNo: 8, round: 2, scope: 'PUBLIC' as const, content: '新增已提交发言' }] }
  await page.setProps({ view: next }); await flushPromises()
  expect(list.scrollTop).toBe(100); expect(page.get('.game-new-messages').text()).toContain('1 条新消息')
  await page.get('.game-new-messages').trigger('click'); await flushPromises()
  expect(list.scrollTop).toBeGreaterThan(100); expect(page.find('.game-new-messages').exists()).toBe(false)
})
test('multiple streaming chunks count as one unread message, and commit does not duplicate it', async () => {
  const view = await fixture(), page = await render(view), list = page.get('.game-chat-list').element as HTMLElement
  Object.defineProperty(list, 'scrollHeight', { value: 900 }); Object.defineProperty(list, 'clientHeight', { value: 200 }); list.scrollTop = 0; await page.get('.game-chat-list').trigger('scroll')
  for (const content of ['第一段', '第一段第二段']) { await page.setProps({ view: { ...view, preview: { ...view.preview!, content } } }); await flushPromises(); expect(page.get('.game-new-messages').text()).toContain('1 条') }
  await page.setProps({ view: { ...view, preview: null, messages: [...view.messages, { id: view.preview!.speechId, seatNo: 3, round: 2, scope: 'PUBLIC', content: '最终发言' }] } }); await flushPromises()
  expect(page.findAll('[data-message-id="PUBLIC:speech:speech-3"]')).toHaveLength(1); expect(page.get('.game-new-messages').text()).toContain('1 条')
  expect(list.scrollTop).toBe(0)
})
test('the current scroll follows incoming messages only when already at the end', async () => {
  const view = await fixture(), page = await render(view), list = page.get('.game-chat-list').element as HTMLElement
  Object.defineProperty(list, 'scrollHeight', { configurable: true, value: 900 }); Object.defineProperty(list, 'clientHeight', { value: 200 }); list.scrollTop = 700; await page.get('.game-chat-list').trigger('scroll')
  await page.setProps({ view: { ...view, preview: { ...view.preview!, content: '下一段' } } }); await flushPromises()
  expect(list.scrollTop).toBeGreaterThanOrEqual(700); expect(page.find('.game-new-messages').exists()).toBe(false)
})
test('day filtering retains history and can return to newer rounds', async () => {
  const view = await fixture(); view.preview = null; view.messages.push({ id: 'earlier', seatNo: 7, round: 1, content: '第一天的判断', scope: 'PUBLIC' })
  const page = await render(view); await page.get('select').setValue('1')
  expect(page.get('.game-chat-list').text()).toContain('第一天的判断'); expect(page.get('.game-chat-list').text()).not.toContain('昨夜平安')
  await page.get('select').setValue('all'); expect(page.findAll('.game-chat-day').length).toBeGreaterThan(1)
})
test('only explicit speech permission enables sending; dead players may give authorized last words', async () => {
  const view = await fixture(), page = await render(view)
  expect(page.get('textarea').attributes('disabled')).toBeDefined(); await page.get('form').trigger('submit'); expect(page.emitted('speech')).toBeUndefined()
  await page.setProps({ view: { ...view, phase: 'LAST_WORDS', me: { ...view.me, alive: false }, permissions: [{ type: 'SPEAK', label: '发言', targets: [] }] }, speechDraft: '我的遗言' })
  expect(page.get('textarea').attributes('disabled')).toBeUndefined(); await page.get('form').trigger('submit'); expect(page.emitted('speech')).toHaveLength(1)
  await page.setProps({ actionable: false }); expect(page.get('textarea').attributes('disabled')).toBeDefined(); await page.get('form').trigger('submit'); expect(page.emitted('speech')).toHaveLength(1)
})
test('finished and spectator views hide sending even if stale permissions remain', async () => {
  const view = await fixture('my-turn'), page = await render({ ...view, phase: 'GAME_OVER' })
  expect(page.find('textarea').exists()).toBe(false); await page.get('form').trigger('submit'); expect(page.emitted('speech')).toBeUndefined()
  await page.setProps({ view: { ...view, me: { ...view.me, seatNo: 0 } } }); expect(page.find('textarea').exists()).toBe(false)
})
test('a faction history is read-only unless the explicit wolf chat grant is present', async () => {
  const view = await fixture('wolf'); view.permissions = view.permissions.filter(grant => grant.type !== 'WOLF_CHAT')
  const page = await render(view); await page.get('.game-chat-channels button:last-child').trigger('click')
  expect(page.find('.wolf-chat-form').exists()).toBe(false); expect(page.get('.game-chat-readonly').text()).toContain('没有发送权限')
  await page.setProps({ view: { ...view, permissions: [{ type: 'WOLF_CHAT', label: '密谈', targets: [] }] }, wolfDraft: '授权密谈' }); await page.get('.wolf-chat-form').trigger('submit'); expect(page.emitted('wolf')).toHaveLength(1)
})
