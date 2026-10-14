package com.wolfmind.application.agent.runtime;

import com.wolfmind.application.agent.fake.FakeAgentView;
import com.wolfmind.application.agent.model.ActionProposal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SpeechPlanTest {
    @ParameterizedTest
    @ValueSource(strings = {
            "{\"stance\":\"OBSERVE\",\"stance\":\"SUPPORT\",\"purpose\":\"CAUTION\",\"evidenceIds\":[]}",
            "{\"stance\":\"OBSERVE\",\"purpose\":\"CAUTION\",\"evidenceIds\":[]} {}",
            "{\"stance\":\"SUSPECT\",\"purpose\":\"CAUTION\",\"evidenceIds\":[]}",
            "{\"stance\":\"OBSERVE\",\"focusSeat\":100,\"purpose\":\"CAUTION\",\"evidenceIds\":[]}",
            "{\"stance\":\"OBSERVE\",\"focusSeat\":2.5,\"purpose\":\"CAUTION\",\"evidenceIds\":[]}",
            "{\"stance\":\"OBSERVE\",\"purpose\":\"CAUTION\",\"evidenceIds\":[\"public-1\",\"public-1\"]}",
            "{\"stance\":\"OBSERVE\",\"purpose\":\"CAUTION\",\"evidenceIds\":[],\"notes\":\"private\"}",
            "{\"stance\":\"VOTE\",\"purpose\":\"CAUTION\",\"evidenceIds\":[]}",
            "null"
    })
    void rejectsMalformedUnknownOrUnauthorizedFields(String text) {
        var error = assertThrows(IllegalArgumentException.class,
                () -> SpeechPlan.parse(text, FakeAgentView.of("VILLAGER"), Map.of("public-1", "public evidence")));
        assertEquals("SPEECH_PLAN_INVALID", error.getMessage());
    }

    @Test void publicViewRemovesRoleAndSkillAndPrivateFacts() {
        var original = FakeAgentView.seerWithInspection(6, "WOLF");
        var view = SpeechPlan.publicView(original);
        assertTrue(view.privateEvents().isEmpty()); assertTrue(view.wolfChat().isEmpty());
        assertEquals("UNKNOWN", view.me().role()); assertEquals("UNKNOWN", view.me().camp());
        assertTrue(view.other().stream().allMatch(s -> s.role() == null && s.camp() == null));
        assertTrue(SpeechPlan.publicEvidence(view).isEmpty());
    }

    @Test void evidenceTextComesFromServerAndFocusCannotChangeToGameTarget() {
        var plan = SpeechPlan.parse("{\"stance\":\"SUSPECT\",\"focusSeat\":6,\"purpose\":\"CAUTION\",\"evidenceIds\":[\"public-1\"]}",
                FakeAgentView.of("VILLAGER"), Map.of("public-1", "6号公开发言"));
        assertTrue(plan.render(Map.of("public-1", "6号公开发言")).contains("6号公开发言"));
        assertFalse(plan.matches(ActionProposal.of("VOTE", "p_6", 6)));
        assertFalse(plan.matches(new ActionProposal("SPEAK", null, null, "我怀疑16号", 0.5, "TEST", null)));
        assertTrue(plan.matches(new ActionProposal("SPEAK", null, null, "我怀疑 6 号", 0.5, "TEST", null)));
    }

    @Test void simpleNegationDoesNotReverseTheIntendedStance() {
        var suspect = new SpeechPlan(SpeechPlan.Stance.SUSPECT, 6, SpeechPlan.Purpose.CAUTION, List.of());
        var support = new SpeechPlan(SpeechPlan.Stance.SUPPORT, 6, SpeechPlan.Purpose.CAUTION, List.of());
        assertTrue(suspect.matches(new ActionProposal("SPEAK", null, null, "我不相信6号", 0.5, "TEST", null)));
        assertTrue(support.matches(new ActionProposal("SPEAK", null, null, "我不怀疑6号", 0.5, "TEST", null)));
        assertFalse(suspect.matches(new ActionProposal("SPEAK", null, null, "我相信6号", 0.5, "TEST", null)));
        assertFalse(support.matches(new ActionProposal("SPEAK", null, null, "我不相信6号", 0.5, "TEST", null)));
    }
}
