// @vitest-environment jsdom
import { expect, test } from 'vitest'
import { mount } from '@vue/test-utils'
import GameRuleSummary from '../src/components/GameRuleSummary.vue'

test('side kill explains why surviving gods cannot prevent defeat after all villagers die', () => {
  const wrapper = mount(GameRuleSummary, { props: { rules: { winCondition: 'SIDE_KILL', witchSelfSave: true, witchSelfSaveFirstNightOnly: true } } })
  expect(wrapper.text()).toContain('平民全部出局或神职全部出局')
  expect(wrapper.text()).toContain('每瓶整局只能使用一次')
  expect(wrapper.text()).toContain('女巫仅第一夜可以自救')
})
test('outnumber rooms display their actual policy rather than side kill', () => {
  const wrapper = mount(GameRuleSummary, { props: { rules: { winCondition: 'WOLVES_OUTNUMBER', witchSelfSave: false } } })
  expect(wrapper.text()).toContain('存活狼人数量达到或超过存活好人数量')
  expect(wrapper.text()).toContain('女巫不能自救')
  expect(wrapper.text()).not.toContain('屠边')
})
test('an absent or future policy is shown as unsynchronized', () => {
  for (const winCondition of [undefined, 'FUTURE_RULE']) {
    const wrapper = mount(GameRuleSummary, { props: { rules: { winCondition } } })
    expect(wrapper.text()).toContain('胜利条件尚未同步')
    expect(wrapper.text()).not.toContain('屠边')
  }
})
