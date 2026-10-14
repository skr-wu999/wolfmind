package com.wolfmind.infrastructure.agent;

import com.wolfmind.application.agent.fake.FakeAgentView;
import com.wolfmind.application.agent.llm.LlmClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ModelToolCodecTest {
    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            SpeakTool|{"content":"你好"}|SPEAK
            SpeakTool|{"skip":true}|SKIP
            SubmitVoteTool|{"targetId":"p_2"}|VOTE
            SubmitVoteTool|{"abstain":true}|VOTE
            KillPlayerTool|{"targetId":"p_2"}|WOLF_KILL
            KillPlayerTool|{"empty":true}|WOLF_KILL
            UseSkillTool|{"skill":"SEER_INSPECT","targetId":"p_2"}|SEER_INSPECT
            UseSkillTool|{"skill":"SEER_SKIP"}|SKIP
            UseSkillTool|{"skill":"WITCH_SAVE","targetId":"p_2"}|WITCH_SAVE
            UseSkillTool|{"skill":"WITCH_POISON","targetId":"p_2"}|WITCH_POISON
            UseSkillTool|{"skill":"WITCH_PASS"}|WITCH_PASS
            UseSkillTool|{"skill":"HUNTER_SHOOT","targetId":"p_2"}|HUNTER_SHOOT
            UseSkillTool|{"skill":"HUNTER_GIVE_UP"}|HUNTER_SHOOT
            """)
    void mapsEveryActionVariant(String tool, String arguments, String action) {
        var prepared = ModelToolCodec.prepare(new LlmClient.ToolCall("c", tool, arguments),
                FakeAgentView.of("VILLAGER"), ToolGatewayConfig.DEFAULT);
        assertTrue(prepared.valid(), () -> String.valueOf(prepared.rejection()));
        assertEquals(action, prepared.proposal().actionType());
        assertTrue(prepared.proposalJson().contains(action));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            {"targetId":"p_2","playerId":"p_3"}|TOOL_IDENTITY_MISMATCH
            {"targetId":"p_2","playerId":null}|TOOL_IDENTITY_MISMATCH
            {"targetId":"p_2","unexpected":null}|TOOL_ARGS_INVALID
            {"targetId":"p_2","unexpected":1}|TOOL_ARGS_INVALID
            {"targetId":"p_2","targetId":"p_3"}|TOOL_ARGS_INVALID
            {"targetId":"p_2"} trailing|TOOL_ARGS_INVALID
            {"targetId":"p_2","targetSeatNo":3}|TOOL_ARGS_INVALID
            {"targetId":"p_2","abstain":true}|TOOL_ARGS_INVALID
            {"targetId":"p_2","confidence":2}|TOOL_ARGS_INVALID
            {"abstain":false}|TOOL_ARGS_INVALID
            {"targetId":"missing"}|ACTION_INVALID_TARGET
            """)
    void validatesRawArgumentsBeforeNormalization(String arguments, String code) {
        var prepared = ModelToolCodec.prepare(new LlmClient.ToolCall("c", "SubmitVoteTool", arguments),
                FakeAgentView.of("VILLAGER"), ToolGatewayConfig.DEFAULT);
        assertFalse(prepared.valid());
        assertEquals(code, prepared.rejection().code());
        assertNull(prepared.proposal());
    }

    @Test
    @SuppressWarnings("unchecked")
    void exportsSchemasAndRoleSpecificSkills() {
        var specs = ModelToolCodec.specifications("WITCH", "NIGHT_WITCH", ToolGatewayConfig.DEFAULT);
        assertEquals(List.of("QueryGameStateTool", "QueryHistoryTool", "UseSkillTool"),
                specs.stream().map(s -> s.name()).toList());
        var skill = specs.get(2).jsonSchema();
        Map<String, Object> properties = (Map<String, Object>) skill.get("properties");
        assertFalse(properties.containsKey("playerId"));
        assertFalse(properties.containsKey("actionType"));
        assertEquals(List.of("WITCH_SAVE", "WITCH_POISON", "WITCH_PASS"),
                ((Map<String, Object>) properties.get("skill")).get("enum"));
        assertEquals(false, skill.get("additionalProperties"));
        assertTrue(properties.containsKey("confidence"));
    }

    @Test
    void nativeReadUsesVisibilityAndHasNoSideEffects() {
        GatewayHarness harness = new GatewayHarness();
        int seat = harness.seatOfRole(com.wolfmind.domain.game.model.Role.VILLAGER);
        var gateway = harness.gateway();
        int before = harness.eventCount();
        var read = gateway.executeRead(harness.session(seat),
                new LlmClient.ToolCall("c", "QueryGameStateTool", "{\"scope\":\"PUBLIC\"}"));
        assertTrue(read.result().ok());
        assertNull(read.authorizedView().me().role());
        assertTrue(read.authorizedView().other().stream().allMatch(s -> s.role() == null));
        var history = gateway.executeRead(harness.session(seat),
                new LlmClient.ToolCall("h", "QueryHistoryTool", "{\"limit\":100}"));
        assertTrue(history.result().ok());
        assertFalse(history.content().contains("WOLF_KILL_NOMINATED"));
        assertFalse(history.content().contains("PLAYER_KILLED"));
        assertEquals(before, harness.eventCount());
        var denied = gateway.executeRead(harness.session(seat),
                new LlmClient.ToolCall("d", "QueryGameStateTool", "{\"scope\":\"SELF\",\"gameId\":\"other\"}"));
        assertFalse(denied.result().ok());
        assertEquals(before, harness.eventCount());
    }
}
