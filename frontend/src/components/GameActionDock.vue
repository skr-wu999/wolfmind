<script setup lang="ts">
import { computed, ref } from 'vue'
import RoleSkillBar from './RoleSkillBar.vue'
import GameTargetPicker from './GameTargetPicker.vue'
import PixelIcon from './PixelIcon.vue'
import type { ActionPermission, GameActionType, GameCommand, GamePresentation } from '@/features/game/types'

const props = defineProps<{ view: GamePresentation; actionable: boolean; busy: boolean; remaining: number | null; permission?: ActionPermission; activeAction: GameActionType | null; selected: number | 'abstain' | null; voting: boolean; discussing: boolean; acting: boolean; icon: string; online: boolean }>()
const emit = defineEmits<{ select: [target: number | 'abstain']; action: [type: GameActionType]; confirm: [type?: GameActionType | null, params?: GameCommand['params']]; speech: []; logs: []; skills: []; result: []; restart: [] }>()
const panel = ref<HTMLElement | null>(null)
const selection = computed(() => props.selected === 'abstain' ? props.activeAction === 'HUNTER_SHOOT' ? '放弃开枪' : '弃票' : props.selected === null ? '未选择' : `${props.selected} 号 ${props.view.players.find(player => player.seatNo === props.selected)?.name}`)
const title = computed(() => props.voting ? '选择放逐目标' : props.view.phase === 'ROLE_REACTION' ? props.view.me.role === 'HUNTER' ? '猎人开枪' : '等待猎人行动' : '本夜行动')
const saveGrant = computed(() => props.view.permissions.find(grant => grant.type === 'WITCH_SAVE'))
const saveTarget = computed(() => saveGrant.value?.targets[0])
const choosing = computed(() => !!props.permission && !(props.view.me.role === 'WITCH' && props.activeAction !== 'WITCH_POISON'))
const submitting = computed(() => props.view.phase !== 'GAME_OVER' && props.view.me.seatNo > 0 && !props.view.submitted && (props.voting || props.acting))
const description = computed(() => props.view.phase === 'ROLE_REACTION' ? props.view.me.role === 'HUNTER' && props.permission ? '你已获得本次开枪资格，可以选择一名玩家。' : '猎人正在决定是否开枪，请等待结果公布。' : props.view.me.role === 'HUNTER' ? '出局时可以开枪带走一名玩家（被毒出局除外）。现在无需行动。' : props.view.me.role === 'WITCH' ? '解药与毒药分别使用，目标与可用药剂以当前权限为准。' : props.view.me.role === 'SEER' ? '选择一名玩家，查验其阵营。结果仅你可见。' : props.view.me.role === 'WEREWOLF' ? '选择今晚的袭击目标。' : props.view.phase === 'NIGHT_WAITING' ? '夜晚没有主动技能，请等待天亮。' : '本阶段没有可提交的行动。')
function focus() { panel.value?.focus({ preventScroll: true }); panel.value?.scrollIntoView?.({ block: 'nearest', behavior: 'instant' }) }
defineExpose({ focus })
</script>

<template>
  <RoleSkillBar :view="view" :actionable="actionable" :voting="voting" :discussing="discussing" :acting="acting" :has-permission="!!permission" :icon="icon">
    <template #actions><section ref="panel" class="game-action-panel" tabindex="-1" aria-labelledby="game-action-title">
      <div class="game-dock-title"><h2 id="game-action-title">{{ submitting ? title : view.phase === 'GAME_OVER' ? '对局已结束' : discussing ? '村庄会议' : view.submitted ? '行动已完成' : '阶段信息' }}</h2><div class="game-dock-records"><button class="game-text-button" @click="emit('skills')">技能记录</button><button class="game-text-button" @click="emit('logs')">查看日志</button></div></div>
      <template v-if="discussing"><p class="game-muted">{{ view.submitted || '发言在右侧群聊中提交，轮次与权限以当前对局为准。' }}</p><button v-if="view.me.seatNo > 0" id="game-speak" class="lobby-button px-button px-button-primary" :disabled="!actionable || !view.permissions.some(grant => grant.type === 'SPEAK')" @click="emit('speech')"><PixelIcon name="speech" :size="18" />{{ view.permissions.some(grant => grant.type === 'SPEAK') ? '发言' : '等待发言' }}</button></template>
      <template v-else-if="submitting"><p class="game-panel-description">{{ voting ? '点击游戏桌选择目标。提交后等待统一结算，不展示实时票数。' : description }}</p>
        <div v-if="view.me.role === 'WITCH' && acting && view.permissions.length" class="witch-potions"><div class="potion-row"><PixelIcon name="bottle" :size="20" /><div><strong>生命药剂 · {{ saveGrant?.remaining || 0 }} / 1</strong><small>{{ saveTarget === undefined ? '今晚没有可救助目标' : `今晚可救助：${saveTarget} 号 ${view.players.find(player => player.seatNo === saveTarget)?.name}` }}</small></div><button class="secondary-button px-button" :disabled="!actionable || saveTarget === undefined" @click="emit('confirm', 'WITCH_SAVE', { targetSeatNo: saveTarget })">使用解药</button></div><div class="potion-row poison"><PixelIcon name="bottle" :size="20" /><div><strong>剧毒药剂 · {{ view.permissions.find(grant => grant.type === 'WITCH_POISON')?.remaining || 0 }} / 1</strong><small>先选择玩家，再确认使用</small></div><button class="secondary-button px-button" :disabled="!actionable || !view.permissions.some(grant => grant.type === 'WITCH_POISON')" :aria-pressed="activeAction === 'WITCH_POISON'" @click="emit('action', 'WITCH_POISON')">选择目标</button></div></div>
        <template v-if="choosing && permission"><div class="game-dock-target"><p class="selection-summary">当前选择：<strong>{{ selection }}</strong></p><button v-if="permission.allowEmpty" class="secondary-button px-button" :aria-pressed="selected === 'abstain'" :disabled="!actionable" @click="emit('select', 'abstain')">{{ activeAction === 'HUNTER_SHOOT' ? '放弃开枪' : '弃票' }}</button><button class="lobby-button px-button px-button-primary game-confirm" :disabled="!actionable || selected === null" @click="emit('confirm')"><PixelIcon name="check" :size="18" />{{ busy ? '提交中…' : permission.label }}</button></div><details class="game-dock-target-list"><summary>展开目标列表</summary><GameTargetPicker :players="view.players" :targets="permission.targets" :selected="selected" :disabled="!actionable" @select="emit('select', $event)" /></details></template>
        <p v-else-if="!view.permissions.length" class="game-muted">本阶段你没有可提交的行动。</p>
        <button v-if="view.permissions.some(grant => grant.type === 'WITCH_PASS')" class="game-text-button" :disabled="!actionable" @click="emit('confirm', 'WITCH_PASS', {})">今晚不用药</button>
        <p v-if="remaining === 0" class="game-muted">行动时间已到，等待阶段更新。</p>
      </template>
      <template v-else><p class="game-panel-description">{{ view.submitted || view.result?.content || description }}</p><ul v-if="view.result?.tally" class="vote-tally"><li v-for="row in view.result.tally" :key="row.seatNo">{{ row.seatNo }} 号 {{ view.players.find(player => player.seatNo === row.seatNo)?.name }}<strong>{{ row.count }} 票</strong></li></ul><button v-if="!online && view.phase === 'GAME_OVER'" class="secondary-button px-button" @click="emit('restart')">重新体验示例</button></template>
      <button v-if="view.privateResult" class="game-text-button" @click="emit('result')">查看本次查验结果</button>
    </section></template>
  </RoleSkillBar>
</template>
