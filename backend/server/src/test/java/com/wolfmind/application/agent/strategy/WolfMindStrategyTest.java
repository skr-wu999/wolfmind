package com.wolfmind.application.agent.strategy;

import com.wolfmind.application.agent.model.ActionProposal;
import com.wolfmind.application.agent.model.AgentConfig;
import com.wolfmind.application.agent.model.AgentContext;
import com.wolfmind.application.agent.model.AgentView;
import com.wolfmind.application.agent.model.Candidate;
import com.wolfmind.application.agent.model.ToolSession;
import com.wolfmind.application.agent.tool.ToolGatewayPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WolfMindStrategy}：装配、同源白名单、确定性与"无写路径"（strategy-layer.md §7 约束 1、§10、§11）。
 *
 * <p>假网关只会被问到"允许什么"，任何一次查询或提交都会抛异常——这正好把
 * "策略层只读、写路径唯一"这条约束变成一条会失败的断言。
 */
class WolfMindStrategyTest {

    private static final AgentConfig CONFIG = AgentViews.config(AgentConfig.StrategyProfile.BALANCED);

    /** 只提供白名单的假网关：其余方法一旦被调用就说明职责越界。 */
    private static final class FakeGateway implements ToolGatewayPort {

        private final List<String> actionTypes;
        private final List<String> tools;
        private int readCalls;
        private int writeCalls;

        FakeGateway(List<String> actionTypes, List<String> tools) {
            this.actionTypes = List.copyOf(actionTypes);
            this.tools = List.copyOf(tools);
        }

        @Override
        public ToolResult queryGameState(ToolSession session, String scope) {
            readCalls++;
            throw new UnsupportedOperationException("策略层不得调用只读工具");
        }

        @Override
        public ToolResult queryHistory(ToolSession session, Integer fromVersion, Integer toVersion, int limit) {
            readCalls++;
            throw new UnsupportedOperationException("策略层不得调用只读工具");
        }

        @Override
        public ToolResult submit(ToolSession session, String toolName, ActionProposal proposal) {
            writeCalls++;
            throw new UnsupportedOperationException("策略层不得调用写路径（tool-gateway.md §1）");
        }

        @Override
        public List<String> availableTools(String role, String phase) {
            return tools;
        }

        @Override
        public List<String> allowedActionTypes(String role, String phase) {
            return actionTypes;
        }
    }

    private static AgentView voteView() {
        return AgentViews.builder()
                .phase(StrategyCodes.DAY_VOTE)
                .round(2)
                .me(1, StrategyCodes.VILLAGER, "VILLAGE", true)
                .others(2, 3, 4, 5, 6, 7, 8)
                .build();
    }

    private static AgentView discussView() {
        return AgentViews.builder()
                .phase(StrategyCodes.DAY_DISCUSS)
                .round(1)
                .me(1, StrategyCodes.VILLAGER, "VILLAGE", true)
                .nineSeats(1)
                .build();
    }

    @Test
    @DisplayName("allowedActions 与网关同源：端口说什么就是什么，不维护第二份矩阵")
    void allowedActionsComeFromGateway() {
        WolfMindStrategy strategy = new WolfMindStrategy(
                new FakeGateway(List.of("SPEAK"), List.of("SpeakTool")));

        assertEquals(List.of("SPEAK"), strategy.allowedActions(StrategyCodes.VILLAGER, StrategyCodes.DAY_VOTE));
        assertEquals(List.of("SpeakTool"), strategy.availableTools(StrategyCodes.VILLAGER, StrategyCodes.DAY_VOTE));
    }

    @Test
    @DisplayName("端口缺席时回退到内置防御矩阵（仅离线场景）")
    void nullGatewayUsesDefensiveMatrix() {
        WolfMindStrategy strategy = new WolfMindStrategy();

        assertEquals(List.of(StrategyCodes.VOTE, StrategyCodes.SKIP),
                strategy.allowedActions(StrategyCodes.VILLAGER, StrategyCodes.DAY_VOTE));
        assertEquals(List.of(StrategyCodes.WOLF_KILL, StrategyCodes.SKIP),
                strategy.allowedActions(StrategyCodes.WEREWOLF, StrategyCodes.NIGHT_WOLF));
        assertEquals(List.of(StrategyCodes.SEER_INSPECT, StrategyCodes.SKIP),
                strategy.allowedActions(StrategyCodes.SEER, StrategyCodes.NIGHT_SEER));
        assertTrue(strategy.allowedActions(StrategyCodes.WITCH, StrategyCodes.NIGHT_WITCH)
                .containsAll(List.of(StrategyCodes.WITCH_SAVE, StrategyCodes.WITCH_POISON, StrategyCodes.WITCH_PASS)));
        assertEquals(List.of(StrategyCodes.HUNTER_SHOOT, StrategyCodes.SKIP),
                strategy.allowedActions(StrategyCodes.HUNTER, StrategyCodes.HUNTER_SHOOT_PHASE));
        assertTrue(strategy.allowedActions(StrategyCodes.VILLAGER, StrategyCodes.NIGHT_WOLF)
                .contains(StrategyCodes.SKIP));
    }

    @Test
    @DisplayName("候选集按网关白名单过滤：网关不给的动作不会出现在候选里")
    void candidatesAreFilteredByGatewayWhitelist() {
        AgentView view = discussView();

        WolfMindStrategy noSpeak = new WolfMindStrategy(new FakeGateway(List.of(StrategyCodes.SKIP), List.of()));
        assertTrue(noSpeak.candidates(view, StrategyCodes.VILLAGER, StrategyCodes.DAY_DISCUSS, CONFIG).isEmpty(),
                "网关只允许 SKIP 时发言候选必须被过滤掉");

        WolfMindStrategy withSpeak = new WolfMindStrategy(
                new FakeGateway(List.of(StrategyCodes.SPEAK, StrategyCodes.SKIP), List.of()));
        List<Candidate> candidates = withSpeak.candidates(
                view, StrategyCodes.VILLAGER, StrategyCodes.DAY_DISCUSS, CONFIG);
        assertEquals(List.of(StrategyCodes.SPEAK), candidates.stream().map(Candidate::actionType).toList());
    }

    @Test
    @DisplayName("端口返回别的词汇（例如工具名）时不做二次猜测，候选集不被清空")
    void unknownVocabularyDoesNotEmptyCandidates() {
        WolfMindStrategy strategy = new WolfMindStrategy(
                new FakeGateway(List.of("SpeakTool", "SubmitVoteTool"), List.of("SpeakTool")));

        List<Candidate> candidates = strategy.candidates(
                discussView(), StrategyCodes.VILLAGER, StrategyCodes.DAY_DISCUSS, CONFIG);

        assertFalse(candidates.isEmpty(), "词汇不匹配时宁可让网关在提交时拒绝，也不要静默空转");
    }

    @Test
    @DisplayName("确定性：同一 (view, role, phase, config) 两次调用产生完全相同的候选集")
    void candidatesAreDeterministic() {
        WolfMindStrategy strategy = new WolfMindStrategy();
        AgentView view = voteView();

        List<Candidate> first = strategy.candidates(view, StrategyCodes.VILLAGER, StrategyCodes.DAY_VOTE, CONFIG);
        List<Candidate> second = strategy.candidates(view, StrategyCodes.VILLAGER, StrategyCodes.DAY_VOTE, CONFIG);

        assertEquals(first, second);
        assertFalse(first.isEmpty());
    }

    @Test
    @DisplayName("整条只读链路不触碰网关的读写工具，也不产生任何写调用")
    void strategyNeverUsesGatewayReadOrWritePaths() {
        FakeGateway gateway = new FakeGateway(
                List.of(StrategyCodes.SPEAK, StrategyCodes.VOTE, StrategyCodes.SKIP), List.of("SpeakTool"));
        WolfMindStrategy strategy = new WolfMindStrategy(gateway);
        AgentView view = voteView();

        List<Candidate> candidates = strategy.candidates(view, StrategyCodes.VILLAGER, StrategyCodes.DAY_VOTE, CONFIG);
        strategy.validate("{\"actionType\":\"VOTE\",\"targetId\":\"p_2\"}", candidates, view, CONFIG);
        strategy.leakCheck("我觉得 2 号有问题", view);
        strategy.fallback(candidates, view, StrategyCodes.VILLAGER, StrategyCodes.DAY_VOTE);
        strategy.assembleContext(view, candidates, CONFIG, null);

        assertEquals(0, gateway.writeCalls, "策略层没有写路径");
        assertEquals(0, gateway.readCalls, "策略层不调用只读工具，视图由上层传入");
    }

    @Test
    @DisplayName("校验链路：候选集里存在的目标通过，越界目标被拒")
    void validateUsesCandidatesAndGatewayWhitelist() {
        WolfMindStrategy strategy = new WolfMindStrategy(
                new FakeGateway(List.of(StrategyCodes.VOTE, StrategyCodes.SKIP), List.of()));
        AgentView view = voteView();
        List<Candidate> candidates = strategy.candidates(view, StrategyCodes.VILLAGER, StrategyCodes.DAY_VOTE, CONFIG);
        int allowedSeat = candidates.get(0).targetSeatNo();

        StrategyPort.ValidationResult ok = strategy.validate(
                "{\"actionType\":\"VOTE\",\"targetId\":\"p_" + allowedSeat + "\"}", candidates, view, CONFIG);
        assertTrue(ok.valid(), ok.failureReason());

        StrategyPort.ValidationResult rejected = strategy.validate(
                "{\"actionType\":\"VOTE\",\"targetId\":\"p_9\"}", candidates, view, CONFIG);
        assertFalse(rejected.valid());
    }

    @Test
    @DisplayName("兜底链路：空候选集产出 SKIP，非空取最高分")
    void fallbackChain() {
        WolfMindStrategy strategy = new WolfMindStrategy();
        AgentView view = voteView();

        ActionProposal skipped = strategy.fallback(List.of(), view, StrategyCodes.VILLAGER, StrategyCodes.DAY_VOTE);
        assertEquals(StrategyCodes.SKIP, skipped.actionType());

        List<Candidate> candidates = strategy.candidates(view, StrategyCodes.VILLAGER, StrategyCodes.DAY_VOTE, CONFIG);
        ActionProposal proposal = strategy.fallback(candidates, view, StrategyCodes.VILLAGER, StrategyCodes.DAY_VOTE);
        assertEquals(candidates.get(0).actionType(), proposal.actionType());
        assertEquals(candidates.get(0).targetSeatNo(), proposal.targetSeatNo());
    }

    @Test
    @DisplayName("上下文装配：五分区非空，且 SYSTEM 里列出与网关同源的可用动作")
    void assembleContextKeepsSectionsAndWhitelist() {
        WolfMindStrategy strategy = new WolfMindStrategy(
                new FakeGateway(List.of(StrategyCodes.VOTE, StrategyCodes.SKIP), List.of("SubmitVoteTool")));
        AgentView view = voteView();
        List<Candidate> candidates = strategy.candidates(view, StrategyCodes.VILLAGER, StrategyCodes.DAY_VOTE, CONFIG);

        AgentContext context = strategy.assembleContext(view, candidates, CONFIG, "上一局的经验：先听后投");

        assertTrue(context.system().contains(StrategyCodes.VOTE));
        assertTrue(context.system().contains("SubmitVoteTool"));
        assertTrue(context.sections().get("MEMORY").contains("先听后投"));
        assertTrue(context.sections().values().stream().noneMatch(String::isBlank));
    }

    @Test
    @DisplayName("安全模板发言与 Trace 观察都可直接调用")
    void safeSpeechAndTraceObservations() {
        WolfMindStrategy strategy = new WolfMindStrategy();
        AgentView view = discussView();

        assertFalse(strategy.safeSpeech(view).isBlank());

        List<Candidate> candidates = List.of(new Candidate(StrategyCodes.VOTE, 5, "p_5",
                "投票", 0.1, java.util.Map.of(), List.of(), List.of()));
        List<String> observations = strategy.traceOnlyObservations("我盯 7 号，也请大家看 5 号", candidates);

        assertEquals(1, observations.size(), "只记录候选集之外的座位（7 号）");
        assertTrue(observations.get(0).contains("7"));
    }

    @Test
    @DisplayName("空视图或空角色不会抛异常：返回空候选集与可读的兜底上下文")
    void nullInputsAreSafe() {
        WolfMindStrategy strategy = new WolfMindStrategy();

        assertTrue(strategy.candidates(null, StrategyCodes.VILLAGER, StrategyCodes.DAY_VOTE, CONFIG).isEmpty());
        assertTrue(strategy.candidates(discussView(), "", StrategyCodes.GAME_OVER, CONFIG).isEmpty());
        assertFalse(strategy.assembleContext(null, List.of(), CONFIG, null).system().isBlank());
        assertEquals(StrategyCodes.SKIP,
                strategy.fallback(List.of(), null, StrategyCodes.VILLAGER, StrategyCodes.DAY_VOTE).actionType());
        assertFalse(strategy.leakCheck("随便说点什么", null).leaked());
    }
}
