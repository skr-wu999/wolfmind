package com.wolfmind.application.agent.strategy;

import com.wolfmind.application.agent.model.AgentConfig;
import com.wolfmind.application.agent.model.AgentView;
import com.wolfmind.application.agent.model.Candidate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CandidateGenerator} 与五个角色策略：断言各角色各阶段的候选集边界
 * （strategy-layer.md §5、§11 第三行）。
 *
 * <p>每个用例都在验同一件事：候选集是**硬约束边界**，LLM 只能在其中取舍。
 * 因此断言的重点不是"有哪些候选"，而是"哪些候选绝不允许出现"。
 */
class CandidateGeneratorTest {

    private final FactExtractor extractor = new FactExtractor();
    private final ThreatModel threatModel = new ThreatModel();
    private final SuspicionModel suspicionModel = new SuspicionModel();
    private final CandidateGenerator generator = new CandidateGenerator(threatModel, suspicionModel);
    private final AgentConfig config = AgentViews.config(AgentConfig.StrategyProfile.BALANCED);

    private List<Candidate> generate(AgentView view, String role, String phase) {
        return generator.generate(view, role, phase, config, extractor.extractStore(view));
    }

    private static List<Integer> targets(List<Candidate> candidates) {
        return candidates.stream()
                .filter(Candidate::hasTarget)
                .map(Candidate::targetSeatNo)
                .toList();
    }

    @Test
    @DisplayName("狼刀候选：排除自己、排除阵营可见的队友、排除私有名单里的队友，含空刀")
    void werewolfKillExcludesWolfCamp() {
        AgentView view = AgentViews.builder()
                .phase(StrategyCodes.NIGHT_WOLF)
                .round(1)
                .me(1, StrategyCodes.WEREWOLF, "WOLF", true)
                .seat(new AgentView.SeatView("p_2", 2, true, StrategyCodes.WEREWOLF, "WOLF", "AI"))
                .privateFact(AgentViews.teammate(3, 1))
                .others(4, 5, 6, 7, 8, 9)
                .build();

        List<Candidate> candidates = generate(view, StrategyCodes.WEREWOLF, StrategyCodes.NIGHT_WOLF);
        List<Integer> killTargets = targets(candidates.stream()
                .filter(candidate -> StrategyCodes.WOLF_KILL.equals(candidate.actionType()))
                .toList());

        assertFalse(killTargets.contains(1), "自己不能是刀口");
        assertFalse(killTargets.contains(2), "阵营字段可见的队友不能是刀口（INV-6）");
        assertFalse(killTargets.contains(3), "私有队友名单里的座位不能是刀口（INV-6）");
        assertTrue(killTargets.size() <= config.candidateLimit());
        assertTrue(candidates.stream().anyMatch(candidate ->
                StrategyCodes.WOLF_KILL.equals(candidate.actionType()) && !candidate.hasTarget()),
                "本局允许空刀时必须给出空刀候选");
    }

    @Test
    @DisplayName("狼刀候选：本局禁止空刀时不出现空刀选项")
    void werewolfKillWithoutEmptyKillRule() {
        AgentView view = AgentViews.builder()
                .phase(StrategyCodes.NIGHT_WOLF)
                .round(1)
                .rule("allowWolfEmptyKill", false)
                .me(1, StrategyCodes.WEREWOLF, "WOLF", true)
                .nineSeats(1)
                .build();

        List<Candidate> candidates = generate(view, StrategyCodes.WEREWOLF, StrategyCodes.NIGHT_WOLF);

        assertTrue(candidates.stream().allMatch(Candidate::hasTarget));
    }

    @Test
    @DisplayName("狼人白天投票同样不指向队友，且带弃权候选")
    void werewolfVoteExcludesTeammates() {
        AgentView view = AgentViews.builder()
                .phase(StrategyCodes.DAY_VOTE)
                .round(2)
                .me(1, StrategyCodes.WEREWOLF, "WOLF", true)
                .seat(new AgentView.SeatView("p_2", 2, true, StrategyCodes.WEREWOLF, "WOLF", "AI"))
                .others(3, 4, 5, 6, 7, 8, 9)
                .build();

        List<Candidate> candidates = generate(view, StrategyCodes.WEREWOLF, StrategyCodes.DAY_VOTE);
        List<Integer> voteTargets = targets(candidates.stream()
                .filter(candidate -> StrategyCodes.VOTE.equals(candidate.actionType()))
                .toList());

        assertFalse(voteTargets.contains(2), "狼人的投票候选里不出现队友");
        assertTrue(candidates.stream().anyMatch(candidate ->
                StrategyCodes.VOTE.equals(candidate.actionType()) && !candidate.hasTarget()),
                "允许弃权时必须给出弃权候选");
    }

    @Test
    @DisplayName("预言家查验候选：全部存活他人、未查验优先、没有跳过查验这个选项")
    void seerInspectCoversAllAliveOthers() {
        AgentView view = AgentViews.builder()
                .phase(StrategyCodes.NIGHT_SEER)
                .round(2)
                .me(4, StrategyCodes.SEER, "VILLAGE", true)
                .others(1, 2, 3, 5, 6, 7, 8)
                .seat(AgentViews.seat(9, false))
                .privateFact(AgentViews.inspect(2, "WOLF", 1))
                .build();

        List<Candidate> candidates = generate(view, StrategyCodes.SEER, StrategyCodes.NIGHT_SEER);

        List<Integer> order = targets(candidates);
        assertEquals(java.util.Set.of(1, 3, 5, 6, 7, 8), java.util.Set.copyOf(order.subList(0, 6)),
                "未查验的座位在前（平局顺序由本局的平局键决定）");
        assertEquals(2, order.get(6), "已查验的排到最后");
        assertTrue(candidates.stream().allMatch(candidate ->
                StrategyCodes.SEER_INSPECT.equals(candidate.actionType())));
        assertTrue(targets(candidates).size() > config.candidateLimit(),
                "§5 要求预言家的候选是全部存活他人，不按 candidateLimit 截断");
        assertFalse(targets(candidates).contains(4), "不能查验自己");
        assertFalse(targets(candidates).contains(9), "死者不进候选");
    }

    @Test
    @DisplayName("女巫解药候选只能是当夜被刀者，且不可自救")
    void witchSaveOnlyNightVictim() {
        AgentView view = AgentViews.builder()
                .phase(StrategyCodes.NIGHT_WITCH)
                .round(1)
                .me(7, StrategyCodes.WITCH, "VILLAGE", true, true, true, true)
                .nineSeats(7)
                .privateFact(AgentViews.nightKill(5, 1))
                .build();

        List<Candidate> candidates = generate(view, StrategyCodes.WITCH, StrategyCodes.NIGHT_WITCH);
        List<Candidate> saves = candidates.stream()
                .filter(candidate -> StrategyCodes.WITCH_SAVE.equals(candidate.actionType()))
                .toList();

        assertEquals(1, saves.size());
        assertEquals(5, saves.get(0).targetSeatNo(), "解药候选只能是当夜被刀者");
        assertTrue(candidates.stream().anyMatch(candidate ->
                StrategyCodes.WITCH_PASS.equals(candidate.actionType())), "不用药永远是合法候选");
    }

    @Test
    @DisplayName("女巫不可自救：当夜刀口是自己时不出现解药候选")
    void witchCannotSaveSelf() {
        AgentView view = AgentViews.builder()
                .phase(StrategyCodes.NIGHT_WITCH)
                .round(1)
                .me(7, StrategyCodes.WITCH, "VILLAGE", true, true, true, true)
                .nineSeats(7)
                .privateFact(AgentViews.nightKill(7, 1))
                .build();

        List<Candidate> candidates = generate(view, StrategyCodes.WITCH, StrategyCodes.NIGHT_WITCH);

        assertTrue(candidates.stream().noneMatch(candidate ->
                StrategyCodes.WITCH_SAVE.equals(candidate.actionType())));
    }

    @Test
    @DisplayName("仅首夜可自救：首夜刀口是自己时给出救自己的候选，第二夜不给")
    void witchSelfSaveFirstNightOnly() {
        AgentView firstNight = AgentViews.builder()
                .phase(StrategyCodes.NIGHT_WITCH)
                .round(1)
                .rule("witchSelfSave", true)
                .rule("witchSelfSaveFirstNightOnly", true)
                .me(7, StrategyCodes.WITCH, "VILLAGE", true, true, true, true)
                .nineSeats(7)
                .privateFact(AgentViews.nightKill(7, 1))
                .build();
        assertTrue(generate(firstNight, StrategyCodes.WITCH, StrategyCodes.NIGHT_WITCH).stream()
                .anyMatch(candidate -> StrategyCodes.WITCH_SAVE.equals(candidate.actionType())
                        && candidate.targets(7)), "首夜可以救自己");

        AgentView secondNight = AgentViews.builder()
                .phase(StrategyCodes.NIGHT_WITCH)
                .round(2)
                .rule("witchSelfSave", true)
                .rule("witchSelfSaveFirstNightOnly", true)
                .me(7, StrategyCodes.WITCH, "VILLAGE", true, true, true, true)
                .nineSeats(7)
                .privateFact(AgentViews.nightKill(7, 2))
                .build();
        assertTrue(generate(secondNight, StrategyCodes.WITCH, StrategyCodes.NIGHT_WITCH).stream()
                .noneMatch(candidate -> StrategyCodes.WITCH_SAVE.equals(candidate.actionType())),
                "第二夜起不可自救");
    }

    @Test
    @DisplayName("女巫药已用完时对应候选消失，不给必然被拒的选项")
    void witchSkipsUsedPotions() {
        AgentView usedView = AgentViews.builder()
                .phase(StrategyCodes.NIGHT_WITCH)
                .round(2)
                .me(7, StrategyCodes.WITCH, "VILLAGE", true, false, false, true)
                .nineSeats(7)
                .privateFact(AgentViews.nightKill(5, 2))
                .privateFact(AgentViews.saved(5, 1))
                .privateFact(AgentViews.poisoned(3, 1))
                .build();

        List<Candidate> candidates = generate(usedView, StrategyCodes.WITCH, StrategyCodes.NIGHT_WITCH);

        assertTrue(candidates.stream().noneMatch(candidate ->
                StrategyCodes.WITCH_SAVE.equals(candidate.actionType())), "解药已用");
        assertTrue(candidates.stream().noneMatch(candidate ->
                StrategyCodes.WITCH_POISON.equals(candidate.actionType())), "毒药已用");
        assertEquals(List.of(StrategyCodes.WITCH_PASS),
                candidates.stream().map(Candidate::actionType).toList());
    }

    @Test
    @DisplayName("女巫毒药候选来自怀疑排序前 N，且都是存活他人")
    void witchPoisonFromSuspicionRanking() {
        AgentView view = AgentViews.speakAll(AgentViews.builder()
                .phase(StrategyCodes.NIGHT_WITCH)
                .round(2)
                .me(7, StrategyCodes.WITCH, "VILLAGE", true, true, true, true)
                .others(1, 2, 3, 4, 5, 6, 8)
                .seat(AgentViews.seat(9, false))
                .votes(AgentViews.vote(2, 7, false)), new int[] {1, 2, 3, 4, 5, 6, 8}, 2, 5)
                .build();
        FactStore facts = extractor.extractStore(view);

        List<Candidate> candidates = generate(view, StrategyCodes.WITCH, StrategyCodes.NIGHT_WITCH);
        List<Integer> poisonTargets = targets(candidates.stream()
                .filter(candidate -> StrategyCodes.WITCH_POISON.equals(candidate.actionType()))
                .toList());
        List<Integer> expected = suspicionModel.rankAliveOthers(view, facts).stream()
                .limit(config.candidateLimit())
                .map(SuspicionAssessment::seatNo)
                .toList();

        assertEquals(expected, poisonTargets, "毒药候选必须等于怀疑排序前 N");
        assertEquals(config.candidateLimit(), poisonTargets.size());
        assertFalse(poisonTargets.contains(7), "不能毒自己");
        assertFalse(poisonTargets.contains(9), "死者不进候选");
        assertTrue(poisonTargets.contains(2), "把票投给我的座位应当在怀疑排序前 N 内");
    }

    @Test
    @DisplayName("猎人开枪候选：存活他人 + 放弃开枪；开枪次数用尽时只剩放弃")
    void hunterShootCandidates() {
        AgentView alive = AgentViews.builder()
                .phase(StrategyCodes.HUNTER_SHOOT_PHASE)
                .round(2)
                .me(5, StrategyCodes.HUNTER, "VILLAGE", false, true, true, true)
                .nineSeats(5)
                .build();

        List<Candidate> candidates = generate(alive, StrategyCodes.HUNTER, StrategyCodes.HUNTER_SHOOT_PHASE);

        List<Integer> expectedShots = suspicionModel.rankAliveOthers(alive, extractor.extractStore(alive)).stream()
                .limit(config.candidateLimit())
                .map(SuspicionAssessment::seatNo)
                .toList();
        assertTrue(targets(candidates).containsAll(expectedShots), "按怀疑度取前 N 的存活玩家");
        assertFalse(targets(candidates).contains(5));
        assertTrue(candidates.stream().anyMatch(candidate ->
                StrategyCodes.HUNTER_SHOOT.equals(candidate.actionType()) && !candidate.hasTarget()),
                "放弃开枪是合法候选");

        AgentView noShot = AgentViews.builder()
                .phase(StrategyCodes.HUNTER_SHOOT_PHASE)
                .round(2)
                .me(5, StrategyCodes.HUNTER, "VILLAGE", false, true, true, false)
                .nineSeats(5)
                .build();

        List<Candidate> exhausted = generate(noShot, StrategyCodes.HUNTER, StrategyCodes.HUNTER_SHOOT_PHASE);

        assertEquals(1, exhausted.size());
        assertEquals(StrategyCodes.HUNTER_SHOOT, exhausted.get(0).actionType());
        assertFalse(exhausted.get(0).hasTarget());
    }

    @Test
    @DisplayName("村民：投票候选按怀疑度前 N 加弃权；夜间没有动作时返回空候选集")
    void villagerCandidates() {
        AgentView voteView = AgentViews.speakAll(AgentViews.builder()
                .phase(StrategyCodes.DAY_VOTE)
                .round(2)
                .me(1, StrategyCodes.VILLAGER, "VILLAGE", true)
                .others(2, 3, 4, 5, 6, 7, 8)
                .seat(AgentViews.seat(9, false))
                .votes(AgentViews.vote(4, 1, false)), new int[] {2, 3, 4, 5, 6, 7, 8}, 2, 5)
                .build();

        List<Candidate> votes = generate(voteView, StrategyCodes.VILLAGER, StrategyCodes.DAY_VOTE);

        assertEquals(config.candidateLimit() + 1, votes.size(), "前 N 个目标 + 一条弃权");
        assertFalse(targets(votes).contains(1), "不能投自己");
        assertFalse(targets(votes).contains(9), "不能投死者");
        assertTrue(votes.get(0).targets(4), "投过我的座位怀疑度最高");

        AgentView nightView = AgentViews.builder()
                .phase(StrategyCodes.NIGHT_WOLF)
                .round(1)
                .me(1, StrategyCodes.VILLAGER, "VILLAGE", true)
                .nineSeats(1)
                .build();

        assertTrue(generate(nightView, StrategyCodes.VILLAGER, StrategyCodes.NIGHT_WOLF).isEmpty(),
                "村民夜里没有合法动作：空候选集是合法结果");
    }

    @Test
    @DisplayName("本局不允许弃权时，投票候选里没有弃权")
    void abstainCandidateRespectsRules() {
        AgentView view = AgentViews.builder()
                .phase(StrategyCodes.DAY_VOTE)
                .round(1)
                .rule("abstainAllowed", false)
                .me(1, StrategyCodes.VILLAGER, "VILLAGE", true)
                .nineSeats(1)
                .build();

        List<Candidate> candidates = generate(view, StrategyCodes.VILLAGER, StrategyCodes.DAY_VOTE);

        assertTrue(candidates.stream().allMatch(Candidate::hasTarget));
    }

    @Test
    @DisplayName("发言阶段：村民与狼人都有且只有一条发言候选")
    void speakCandidateInSpeechPhases() {
        for (String role : List.of(StrategyCodes.VILLAGER, StrategyCodes.WEREWOLF,
                StrategyCodes.SEER, StrategyCodes.WITCH, StrategyCodes.HUNTER)) {
            AgentView view = AgentViews.builder()
                    .phase(StrategyCodes.DAY_DISCUSS)
                    .round(1)
                    .me(1, role, CampRules.campOfRole(role), true)
                    .nineSeats(1)
                    .build();

            List<Candidate> candidates = generate(view, role, StrategyCodes.DAY_DISCUSS);

            assertEquals(1, candidates.size(), role + " 在发言阶段应当只有一条发言候选");
            assertEquals(StrategyCodes.SPEAK, candidates.get(0).actionType());
            assertFalse(candidates.get(0).hasTarget());
        }
    }

    @Test
    @DisplayName("五个角色都有策略实现，未知角色返回空候选集")
    void allFiveRolesAreRegistered() {
        assertTrue(generator.supportedRoles().containsAll(List.of(
                StrategyCodes.WEREWOLF, StrategyCodes.SEER, StrategyCodes.WITCH,
                StrategyCodes.HUNTER, StrategyCodes.VILLAGER)));

        AgentView view = AgentViews.builder()
                .phase(StrategyCodes.DAY_DISCUSS)
                .me(1, StrategyCodes.VILLAGER, "VILLAGE", true)
                .nineSeats(1)
                .build();

        assertTrue(generate(view, "SOMETHING_ELSE", StrategyCodes.DAY_DISCUSS).isEmpty());
    }

    @Test
    @DisplayName("全体角色全体阶段：候选里绝不出现自己或死者")
    void noCandidateEverTargetsSelfOrDead() {
        String[] roles = {StrategyCodes.WEREWOLF, StrategyCodes.SEER, StrategyCodes.WITCH,
                StrategyCodes.HUNTER, StrategyCodes.VILLAGER};
        String[] phases = {StrategyCodes.NIGHT_WOLF, StrategyCodes.NIGHT_SEER, StrategyCodes.NIGHT_WITCH,
                StrategyCodes.DAY_DISCUSS, StrategyCodes.DAY_VOTE, StrategyCodes.HUNTER_SHOOT_PHASE,
                StrategyCodes.LAST_WORDS};
        for (String role : roles) {
            for (String phase : phases) {
                AgentView view = AgentViews.builder()
                        .phase(phase)
                        .round(2)
                        .me(3, role, CampRules.campOfRole(role), true)
                        .others(1, 2, 4, 5, 6, 7, 8)
                        .seat(AgentViews.seat(9, false))
                        .privateFact(AgentViews.nightKill(5, 2))
                        .build();

                List<Candidate> candidates = generate(view, role, phase);

                for (Candidate candidate : candidates) {
                    if (candidate.hasTarget()) {
                        assertTrue(candidate.targetSeatNo() != 3, role + "/" + phase + " 出现了自己");
                        assertTrue(candidate.targetSeatNo() != 9, role + "/" + phase + " 出现了死者");
                        assertTrue(candidate.targetId() != null, "有目标的候选必须带 targetId");
                    }
                }
                assertTrue(candidates.stream().allMatch(candidate ->
                        StrategyCodes.normalize(candidate.actionType()).equals(candidate.actionType())),
                        "候选的 actionType 必须是规范形式");
            }
        }
    }
}
