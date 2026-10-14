package com.wolfmind.infrastructure.agent;

import com.wolfmind.application.agent.model.ActionProposal;
import com.wolfmind.application.agent.model.AgentView;
import com.wolfmind.application.agent.tool.ToolGatewayPort;
import com.wolfmind.application.agent.trace.AgentTracePort;
import com.wolfmind.domain.game.event.EventEnvelope;
import com.wolfmind.domain.game.model.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 审计（tool-gateway.md §7、§12"审计"）：每次调用一条记录、字段完整、
 * 只存参数摘要（不含发言全文）、不写对局事件流、开关生效。
 */
class ToolGatewayAuditTest {

    private GatewayHarness harness;

    @BeforeEach
    void setUp() {
        harness = new GatewayHarness();
    }

    @Test
    @DisplayName("成功调用：一条 ALLOWED 记录，traceId 与事件的 correlationId 一致")
    void allowedCallIsAudited() {
        int wolf = harness.seatOfRole(Role.WEREWOLF);
        int victim = harness.view(wolf).aliveOthers().stream()
                .filter(seat -> seat.role() == null || !"WEREWOLF".equals(seat.role()))
                .map(AgentView.SeatView::seatNo).findFirst().orElseThrow();

        ToolGatewayPort.ToolResult.Ok ok = ToolGatewayWriteTest.assertOk(harness.gateway().submit(
                harness.session(wolf), ToolCatalog.KILL,
                ActionProposal.of("WOLF_KILL", harness.playerIdOf(victim), victim)));

        assertEquals(1, harness.trace.toolCalls().size(), "每次调用写一条 AGENT_TOOL_CALLED");
        var record = harness.trace.last();
        assertEquals(AgentTracePort.TraceEventType.AGENT_TOOL_CALLED, record.type());
        assertEquals(GatewayHarness.GAME_ID, record.gameId());
        assertEquals(harness.playerIdOf(wolf), record.playerId());
        assertEquals("ALLOWED", record.payload().get("decision"));
        assertNull(record.payload().get("denialCode"));
        assertEquals(ToolCatalog.KILL, record.payload().get("tool"));
        assertEquals(ok.version(), record.payload().get("version"));
        assertEquals(ok.eventIds().get(0), record.payload().get("resultingEventId"));
        assertEquals("NIGHT_WOLF", record.payload().get("phase"));
        assertEquals(1, record.payload().get("round"));
        assertEquals(Boolean.FALSE, record.payload().get("replayed"));
        assertNotNull(record.payload().get("latencyMs"));
        assertNotNull(record.payload().get("argsDigest"));

        EventEnvelope committed = harness.events().get(harness.events().size() - 1);
        assertEquals(committed.correlationId(), record.payload().get("traceId"),
                "traceId 必须等于事件的 correlationId，否则审计追不到事件（§5）");
    }

    @Test
    @DisplayName("拒绝调用：一条 DENIED 记录带拒绝码，且事件流不变")
    void deniedCallIsAudited() {
        int before = harness.eventCount();
        int villager = harness.seatOfRole(Role.VILLAGER);

        ToolGatewayPort.ToolResult.Denied denied = ToolGatewayWriteTest.assertDenied(
                harness.gateway().submit(harness.session(villager), ToolCatalog.KILL,
                        ActionProposal.of("WOLF_KILL", harness.playerIdOf(2), 2)));

        assertEquals(1, harness.trace.toolCalls().size());
        var record = harness.trace.last();
        assertEquals("DENIED", record.payload().get("decision"));
        assertEquals(denied.code(), record.payload().get("denialCode"));
        assertNull(record.payload().get("resultingEventId"));
        assertEquals(before, harness.eventCount(), "拒绝不得写对局事件（§12）");
    }

    @Test
    @DisplayName("只读调用同样记录：AI 看到了什么是分析策略质量的输入")
    void readCallIsAudited() {
        int seat = harness.seatOfRole(Role.VILLAGER);
        ToolGatewayWriteTest.assertOk(harness.gateway().queryGameState(harness.session(seat), "PUBLIC"));

        assertEquals(1, harness.trace.toolCalls().size());
        assertEquals("ALLOWED", harness.trace.last().payload().get("decision"));
        assertEquals("scope=PUBLIC version=" + harness.version(),
                harness.trace.last().payload().get("argsDigest"));
    }

    @Test
    @DisplayName("审计只存摘要：发言全文不进 Trace")
    void speechTextIsNotAudited() {
        harness.driveTo(com.wolfmind.domain.game.model.GamePhase.DAY_DISCUSS);
        int speaker = harness.state().currentTurn().seatNo().value();
        String marker = "独特标记-A7F3-不该进审计";

        ToolGatewayWriteTest.assertOk(harness.gateway().submit(
                harness.session(speaker), ToolCatalog.SPEAK,
                new ActionProposal("SPEAK", null, null, marker, 0.7, "TEST", null)));

        assertFalse(harness.trace.anyPayloadContains(marker), "审计不得保存发言全文（§7）");
        String digest = String.valueOf(harness.trace.last().payload().get("argsDigest"));
        assertTrue(digest.contains("speechLen=" + marker.length()),
                "摘要里只保留长度：" + digest);
        assertTrue(digest.contains("action=SPEAK"));
    }

    @Test
    @DisplayName("开关：gateway.audit.enabled=false 时不写审计，但提交照常生效")
    void auditCanBeDisabled() {
        int wolf = harness.seatOfRole(Role.WEREWOLF);
        int victim = harness.view(wolf).aliveOthers().stream()
                .filter(seat -> seat.role() == null || !"WEREWOLF".equals(seat.role()))
                .map(AgentView.SeatView::seatNo).findFirst().orElseThrow();
        ToolGatewayAdapter gateway = harness.gateway(ToolGatewayConfig.DEFAULT.withAuditEnabled(false));

        ToolGatewayWriteTest.assertOk(gateway.submit(harness.session(wolf), ToolCatalog.KILL,
                ActionProposal.of("WOLF_KILL", harness.playerIdOf(victim), victim)));

        assertEquals(0, harness.trace.size(), "关闭审计后没有轨迹记录");
        assertEquals(GatewayHarness.STARTED_VERSION + 1, harness.eventCount(),
                "关闭审计不影响业务写路径");
    }

    @Test
    @DisplayName("审计不写对局事件流：一串拒绝与只读调用不改变事件长度，版本也不动")
    void auditNeverTouchesTheGameStream() {
        int seat = harness.seatOfRole(Role.VILLAGER);
        int before = harness.eventCount();
        int versionBefore = harness.version();

        for (int attempt = 0; attempt < 3; attempt++) {
            ToolGatewayWriteTest.assertDenied(harness.gateway().submit(
                    harness.session(seat), ToolCatalog.KILL, ActionProposal.of("WOLF_KILL", "p_2", 2)));
        }
        ToolGatewayWriteTest.assertOk(harness.gateway().queryGameState(harness.session(seat), "SELF"));
        ToolGatewayWriteTest.assertOk(harness.gateway().queryHistory(harness.session(seat), 1, 5, 5));

        assertEquals(before, harness.eventCount());
        assertEquals(versionBefore, harness.version());
        assertEquals(5, harness.trace.toolCalls().size(), "每一次调用都留下痕迹");
        assertTrue(harness.trace.toolCalls().stream()
                .allMatch(record -> record.type().name().startsWith("AGENT_")),
                "审计事件的类型前缀必须是 AGENT_（event-design.md §7.4）");
    }

    @Test
    @DisplayName("审计字段：常量表里的拒绝码与 decision 取值封闭")
    void auditVocabularyIsClosed() {
        assertEquals(java.util.Set.of("ALLOWED", "DENIED"),
                java.util.Set.of(ToolAuditRecorder.Decision.ALLOWED.name(),
                        ToolAuditRecorder.Decision.DENIED.name()));
        assertTrue(DenialCodes.all().size() >= 8, "拒绝码表必须覆盖 §4 的每一步");
        assertEquals(DenialCodes.ACTION_NOT_ALLOWED, DenialCodes.fromDomainCode(null),
                "没有领域码时按「动作不被允许」处理，而不是伪造成参数错误");
        assertEquals(DenialCodes.TOOL_ARGS_INVALID, DenialCodes.fromDomainCode("VALIDATION_ERROR"));
        assertEquals(DenialCodes.ACTION_NOT_ALLOWED, DenialCodes.fromDomainCode("SKILL_UNAVAILABLE"));
        assertEquals("CUSTOM_CODE", DenialCodes.fromDomainCode("CUSTOM_CODE"),
                "未登记的领域码原样透传，不静默改写");
        assertTrue(DenialCodes.retryable(DenialCodes.GAME_BUSY));
        assertTrue(DenialCodes.retryable(DenialCodes.GAME_VERSION_CONFLICT));
        assertFalse(DenialCodes.retryable(DenialCodes.ACTION_INVALID_TARGET));
        assertFalse(DenialCodes.retryable(DenialCodes.BUDGET_EXCEEDED));
    }
}
