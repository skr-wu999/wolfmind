<script setup lang="ts">
import type { RuleSettings } from '@/features/game/rules'
defineProps<{ rules?: RuleSettings }>()
</script>

<template>
  <div class="about-content">
    <template v-if="rules?.winCondition === 'SIDE_KILL'">
      <p><strong>胜利条件：屠边。</strong>狼人全部出局，好人获胜；平民全部出局或神职全部出局，狼人获胜。</p>
      <p>平民指村民职业；神职指预言家、女巫、猎人。即使仍有神职存活，平民全灭也会结束对局。</p>
    </template>
    <p v-else-if="rules?.winCondition === 'WOLVES_OUTNUMBER'"><strong>胜利条件：人数对比。</strong>狼人全部出局，好人获胜；存活狼人数量达到或超过存活好人数量，狼人获胜。好人包括平民和神职。</p>
    <p v-else>本局胜利条件尚未同步，请刷新后查看。</p>
    <p>女巫只有一瓶解药和一瓶毒药，每瓶整局只能使用一次，同一夜不能同时用两种药。</p>
    <p v-if="rules?.witchSelfSave === false">女巫不能自救。</p>
    <p v-else-if="rules?.witchSelfSave === true">{{ rules.witchSelfSaveFirstNightOnly ? '女巫仅第一夜可以自救，之后不能自救。' : '女巫可以自救。' }}</p>
  </div>
</template>
