package com.wolfmind.application.agent.strategy;

import com.wolfmind.application.agent.model.AgentConfig;
import com.wolfmind.application.agent.model.AgentContext;
import com.wolfmind.application.agent.model.AgentView;
import com.wolfmind.application.agent.model.Candidate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ContextAssembler}：五分区非空、对局区只放可见内容、三级截断（agent-runtime.md §6、
 * strategy-layer.md §7、§11）。
 */
class ContextAssemblerTest {

    private final WolfMindStrategy strategy = new WolfMindStrategy();

    private static AgentView seerDayView() {
        return AgentViews.builder()
                .phase(StrategyCodes.DAY_DISCUSS)
                .round(2)
                .me(4, StrategyCodes.SEER, "VILLAGE", true)
                .nineSeats(4)
                .speech(2, 1, StrategyCodes.DAY_DISCUSS, 0, "我是预言家，查验了5号是狼")
                .speech(3, 2, StrategyCodes.DAY_DISCUSS, 0, "我先听听大家怎么说")
                .votes(AgentViews.vote(2, 5, false), AgentViews.vote(3, null, true))
                .deaths(AgentViews.death(9, 1))
                .privateFact(AgentViews.inspect(5, "WOLF", 1))
                .build();
    }

    @Test
    @DisplayName("五个分区都产出非空内容")
    void allSectionsAreNonEmpty() {
        AgentView view = seerDayView();
        List<Candidate> candidates = strategy.candidates(
                view, StrategyCodes.SEER, StrategyCodes.DAY_DISCUSS,
                AgentViews.config(AgentConfig.StrategyProfile.BALANCED));

        AgentContext context = strategy.assembleContext(
                view, candidates, AgentViews.config(AgentConfig.StrategyProfile.BALANCED), null);

        for (String section : List.of("SYSTEM", "PERSONA", "CONTEXT", "PRIVATE", "MEMORY", "CANDIDATES", "TASK")) {
            assertFalse(context.sections().get(section).isBlank(), section + " 分区不能为空");
        }
        assertTrue(context.system().contains("硬约束"));
        assertTrue(context.privateNotes().contains(StrategyCodes.SEER));
        assertTrue(context.task().contains("候选集"));
    }

    @Test
    @DisplayName("狼人刀人时私有区列出今晚夜聊（只取最近 12 条），白天不再出现")
    void wolfKillPrivateZoneShowsTonightsChat() {
        AgentViews.Builder builder = AgentViews.builder()
                .phase(StrategyCodes.NIGHT_WOLF)
                .me(1, StrategyCodes.WEREWOLF, "WOLF", true)
                .nineSeats(1)
                .privateFact(AgentViews.teammate(2, 1));
        for (int index = 1; index <= 13; index++) {
            builder.wolfChat(2, "第" + index + "条：刀5号");
        }
        AgentView night = builder.build();
        AgentConfig config = AgentViews.config(AgentConfig.StrategyProfile.BALANCED);
        String notes = strategy.assembleContext(
                night, strategy.candidates(night, StrategyCodes.WEREWOLF, StrategyCodes.NIGHT_WOLF, config),
                config, null).privateNotes();

        assertTrue(notes.contains("今晚狼队夜聊"), notes);
        assertTrue(notes.contains("2号：第13条：刀5号"), notes);
        assertFalse(notes.contains("第1条："), "只保留最近 12 条：" + notes);

        AgentView day = AgentViews.builder()
                .phase(StrategyCodes.DAY_DISCUSS)
                .me(1, StrategyCodes.WEREWOLF, "WOLF", true)
                .nineSeats(1)
                .wolfChat(2, "刀5号")
                .build();
        String dayNotes = strategy.assembleContext(
                day, strategy.candidates(day, StrategyCodes.WEREWOLF, StrategyCodes.DAY_DISCUSS, config),
                config, null).privateNotes();
        assertFalse(dayNotes.contains("今晚狼队夜聊"), "白天不渲染夜聊：" + dayNotes);
    }

    @Test
    @DisplayName("发言阶段的任务区要求独立表态、不照搬他人，并写明当前轮次")
    void speechTaskAsksForOwnViewpoint() {
        AgentView view = seerDayView();
        AgentConfig config = AgentViews.config(AgentConfig.StrategyProfile.BALANCED);
        List<Candidate> candidates = strategy.candidates(view, StrategyCodes.SEER, StrategyCodes.DAY_DISCUSS, config);

        String task = strategy.assembleContext(view, candidates, config, null).task();

        assertTrue(task.contains("不要复述或照搬其他玩家"), task);
        assertTrue(task.contains("现在是第 2 轮"), task);
        assertTrue(task.contains("不要把白天放逐说成夜里出局"), task);
    }

    @Test
    @DisplayName("出局记录写明白天放逐 / 猎人带走 / 夜里死亡，夜里死亡不给死因")
    void deathsAreRenderedWithPublicWay() {
        AgentView view = AgentViews.builder()
                .phase(StrategyCodes.DAY_DISCUSS)
                .round(3)
                .me(4, StrategyCodes.WITCH, "VILLAGE", true)
                .nineSeats(4)
                .deaths(new AgentView.DeathView(2, 1, AgentView.DeathView.EXILE),
                        new AgentView.DeathView(7, 2, AgentView.DeathView.NIGHT),
                        new AgentView.DeathView(8, 2, AgentView.DeathView.HUNTER_SHOT),
                        AgentViews.death(9, 2))
                .build();
        AgentConfig config = AgentViews.config(AgentConfig.StrategyProfile.BALANCED);

        String timeline = strategy.assembleContext(
                view, strategy.candidates(view, StrategyCodes.WITCH, StrategyCodes.DAY_DISCUSS, config),
                config, null).context();

        assertTrue(timeline.contains("2号：第 1 天白天被投票放逐"), timeline);
        assertTrue(timeline.contains("7号：第 2 夜出局（夜里出局的原因不公开"), timeline);
        assertTrue(timeline.contains("8号：第 2 轮被猎人开枪带走"), timeline);
        assertTrue(timeline.contains("9号：第 2 轮出局（出局方式不在可读记录内"), timeline);
        assertFalse(timeline.contains("狼刀死亡") || timeline.contains("被毒"), timeline);
    }

    @Test
    @DisplayName("原样复述公开出局记录不能被泄漏守卫拦下（否则发言退成安全模板）")
    void quotingPublicDeathRecordIsNotALeak() {
        AgentView view = AgentViews.builder()
                .phase(StrategyCodes.DAY_DISCUSS)
                .round(3)
                .me(1, StrategyCodes.VILLAGER, "VILLAGE", true)
                .nineSeats(1)
                .build();
        LeakGuard guard = new LeakGuard();
        FactStore facts = new FactExtractor().extractStore(view);
        for (String way : List.of(AgentView.DeathView.EXILE, AgentView.DeathView.HUNTER_SHOT,
                AgentView.DeathView.NIGHT, AgentView.DeathView.UNKNOWN)) {
            String speech = "3 号" + ContextAssembler.describeDeath(new AgentView.DeathView(3, 2, way)) + "，我们接着分析。";
            StrategyPort.LeakCheckResult result = guard.check(speech, view, facts);
            assertFalse(result.leaked(), way + " → " + speech + " → " + result);
        }
    }

    @Test
    @DisplayName("候选集为空时候选区仍有内容：补一条 SKIP 占位，任务区说明只能跳过")
    void emptyCandidatesStillRenderProposalSection() {
        AgentView view = AgentViews.builder()
                .phase(StrategyCodes.NIGHT_WOLF)
                .round(1)
                .me(1, StrategyCodes.VILLAGER, "VILLAGE", true)
                .nineSeats(1)
                .build();

        AgentContext context = strategy.assembleContext(
                view, List.of(), AgentViews.config(AgentConfig.StrategyProfile.BALANCED), null);

        assertFalse(context.sections().get("CANDIDATES").isBlank());
        assertTrue(context.sections().get("CANDIDATES").contains(StrategyCodes.SKIP));
        assertTrue(context.task().contains(StrategyCodes.SKIP));
    }

    @Test
    @DisplayName("对局区只放公开内容：私有事实与 JUDGE 事实不进时间线")
    void privateFactsStayOutOfPublicTimeline() {
        AgentView view = seerDayView();
        AgentContext context = strategy.assembleContext(
                view, List.of(), AgentViews.config(AgentConfig.StrategyProfile.BALANCED), null);

        String timeline = context.sections().get("CONTEXT");
        assertFalse(timeline.contains("SEER_INSPECT"), "私有事实的 kind 不得出现在公开时间线");
        assertFalse(timeline.contains("WOLF（第"), "私有查验结果不得出现在公开时间线");
        assertFalse(timeline.contains("死因"));
        assertTrue(context.sections().get("PRIVATE").contains("SEER_INSPECT"),
                "私有事实必须出现在 PRIVATE 分区");
    }

    @Test
    @DisplayName("玩家发言是数据：被包在数据边界内，且 SYSTEM 分区不含玩家文本")
    void playerSpeechIsFencedData() {
        AgentView view = seerDayView();

        AgentContext context = strategy.assembleContext(
                view, List.of(), AgentViews.config(AgentConfig.StrategyProfile.BALANCED), null);

        String timeline = context.sections().get("CONTEXT");
        assertTrue(timeline.contains(ContextAssembler.DATA_OPEN));
        assertTrue(timeline.contains(ContextAssembler.DATA_CLOSE));
        assertTrue(timeline.contains("2号发言"));
        assertFalse(context.sections().get("SYSTEM").contains("我是预言家"),
                "SYSTEM 分区不得包含玩家文本（提示注入防线）");
    }

    @Test
    @DisplayName("截断层级：事件少时 0，超过阈值 1，超过两倍阈值 2")
    void truncationLevelFollowsThreshold() {
        AgentConfig config = AgentViews.config(AgentConfig.StrategyProfile.BALANCED, 0.5, 3, 10);

        AgentContext level0 = strategy.assembleContext(
                simpleView(3), List.of(), config, null);
        AgentContext level1 = strategy.assembleContext(
                simpleView(15), List.of(), config, null);
        AgentContext level2 = strategy.assembleContext(
                simpleView(30), List.of(), config, null);

        assertEquals(0, level0.truncationLevel());
        assertEquals(1, level1.truncationLevel());
        assertEquals(2, level2.truncationLevel());
        assertTrue(level2.sections().get("CONTEXT").contains("历史轮次摘要"));
    }

    @Test
    @DisplayName("私有区包含技能状态与可用动作，记忆区在无召回时也非空")
    void privateAndMemorySectionsAlwaysFilled() {
        AgentView view = AgentViews.builder()
                .phase(StrategyCodes.NIGHT_WITCH)
                .round(1)
                .me(7, StrategyCodes.WITCH, "VILLAGE", true, true, false, true)
                .nineSeats(7)
                .privateFact(AgentViews.nightKill(5, 1))
                .build();

        AgentContext context = strategy.assembleContext(
                view, List.of(), AgentViews.config(AgentConfig.StrategyProfile.BALANCED), "  ");

        assertTrue(context.sections().get("PRIVATE").contains("解药可用 是"));
        assertTrue(context.sections().get("PRIVATE").contains("毒药可用 否"));
        assertTrue(context.sections().get("PRIVATE").contains("NIGHT_KILL"));
        assertFalse(context.sections().get("MEMORY").isBlank());
    }

    @Test
    @DisplayName("facts 随上下文一起交付，供打分与 Trace 复用")
    void factsAreCarriedInContext() {
        AgentContext context = strategy.assembleContext(
                seerDayView(), List.of(), AgentViews.config(AgentConfig.StrategyProfile.BALANCED), null);

        assertEquals(2, context.facts().speeches().size());
        assertEquals(2, context.facts().votes().size());
        assertEquals(1, context.facts().deaths().size());
        assertEquals(1, context.facts().privateFacts().size());
    }

    /** 造一个含 N 条公开发言的局面，用来驱动三级截断。 */
    private static AgentView simpleView(int speeches) {
        AgentViews.Builder builder = AgentViews.builder()
                .phase(StrategyCodes.DAY_DISCUSS)
                .round(3)
                .me(1, StrategyCodes.VILLAGER, "VILLAGE", true)
                .nineSeats(1);
        for (int index = 0; index < speeches; index++) {
            int seatNo = 2 + (index % 8);
            builder.speech(seatNo, 1 + (index / 8), StrategyCodes.DAY_DISCUSS, index, "第" + index + "条发言");
        }
        return builder.build();
    }
}
