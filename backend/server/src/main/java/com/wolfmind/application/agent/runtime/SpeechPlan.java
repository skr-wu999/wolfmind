package com.wolfmind.application.agent.runtime;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wolfmind.application.agent.llm.LlmClient;
import com.wolfmind.application.agent.model.ActionProposal;
import com.wolfmind.application.agent.model.AgentView;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Internal intent for one speech; evidence text is resolved from the public view, never supplied by the model. */
public record SpeechPlan(Stance stance, Integer focusSeat, Purpose purpose, List<String> evidenceIds) {
    public enum Stance { SUSPECT, SUPPORT, OBSERVE }
    public enum Purpose { REQUEST_EXPLANATION, COMPARE_EVIDENCE, CAUTION, FAREWELL }
    public static final String TOOL_NAME = "PrepareSpeechPlan";
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public SpeechPlan { evidenceIds = List.copyOf(evidenceIds); }

    public static LlmClient.LlmRequest.ToolSpec toolSpec() {
        return new LlmClient.LlmRequest.ToolSpec(TOOL_NAME, "制定内部发言计划，不提交游戏行动",
                Map.of("type", "object", "additionalProperties", false,
                        "required", List.of("stance", "purpose", "evidenceIds"),
                        "properties", Map.of(
                                "stance", Map.of("type", "string", "enum", List.of("SUSPECT", "SUPPORT", "OBSERVE")),
                                "focusSeat", Map.of("type", "integer", "minimum", 1, "maximum", 9),
                                "purpose", Map.of("type", "string", "enum", List.of("REQUEST_EXPLANATION", "COMPARE_EVIDENCE", "CAUTION", "FAREWELL")),
                                "evidenceIds", Map.of("type", "array", "maxItems", 4, "uniqueItems", true,
                                        "items", Map.of("type", "string")))));
    }

    public static SpeechPlan parse(String text, AgentView view, Map<String, String> evidence) {
        try {
            if (text == null || text.length() > 4096) throw new IllegalArgumentException();
            JsonNode node = JSON.readTree(text);
            if (node == null || !node.isObject()) throw new IllegalArgumentException();
            var fields = node.fieldNames();
            while (fields.hasNext()) {
                if (!Set.of("stance", "focusSeat", "purpose", "evidenceIds").contains(fields.next()))
                    throw new IllegalArgumentException();
            }
            if (!node.path("stance").isTextual() || !node.path("purpose").isTextual()) throw new IllegalArgumentException();
            Stance stance = Stance.valueOf(node.get("stance").asText());
            Purpose purpose = Purpose.valueOf(node.get("purpose").asText());
            Integer seat = null;
            if (node.has("focusSeat")) {
                if (!node.get("focusSeat").isIntegralNumber() || !node.get("focusSeat").canConvertToInt())
                    throw new IllegalArgumentException();
                seat = node.get("focusSeat").intValue();
                if (seat < 1 || seat > 9) throw new IllegalArgumentException();
                int focus = seat;
                if (view.other().stream().noneMatch(s -> s.seatNo() == focus) && view.me().seatNo() != focus)
                    throw new IllegalArgumentException();
            }
            if (stance != Stance.OBSERVE && seat == null) throw new IllegalArgumentException();
            JsonNode ids = node.get("evidenceIds");
            if (ids == null || !ids.isArray() || ids.size() > 4) throw new IllegalArgumentException();
            var selected = new java.util.ArrayList<String>();
            for (JsonNode id : ids) {
                if (!id.isTextual() || !evidence.containsKey(id.asText()) || selected.contains(id.asText()))
                    throw new IllegalArgumentException();
                selected.add(id.asText());
            }
            return new SpeechPlan(stance, seat, purpose, selected);
        } catch (Exception invalid) {
            // A fixed error avoids copying private model output into trace or the expression prompt.
            throw new IllegalArgumentException("SPEECH_PLAN_INVALID");
        }
    }

    public static Map<String, String> publicEvidence(AgentView view) {
        var facts = new LinkedHashMap<String, String>();
        view.speeches().stream().skip(Math.max(0, view.speeches().size() - 16)).forEach(s -> facts.put(
                "speech:" + s.round() + ":" + s.phase() + ":" + s.turnIndex() + ":" + s.seatNo(),
                "第" + s.round() + "轮 " + s.seatNo() + "号发言：" + s.content()));
        for (var vote : view.votes()) facts.put("vote:" + view.round() + ":" + vote.voterSeat(),
                vote.voterSeat() + "号" + (vote.abstain() ? "弃权" : "投票给" + vote.targetSeatNo() + "号"));
        for (var death : view.deaths()) facts.put("death:" + death.round() + ":" + death.seatNo(),
                "第" + death.round() + "轮 " + death.seatNo() + "号出局，公开方式：" + death.way());
        return java.util.Collections.unmodifiableMap(facts);
    }

    public String render(Map<String, String> evidence) {
        return "立场=" + stance + ", 关注座位=" + focusSeat + ", 目的=" + purpose
                + "\n允许引用的证据：\n" + evidenceIds.stream().map(id -> id + "=" + evidence.get(id))
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    public boolean matches(ActionProposal proposal) {
        if (proposal == null || !"SPEAK".equals(proposal.actionType()) || proposal.targetId() != null || proposal.targetSeatNo() != null
                || !proposal.hasSpeech()) return false;
        if (focusSeat == null) return true;
        String seat = "(?<![0-9])" + focusSeat + "\\s*号";
        if (!java.util.regex.Pattern.compile(seat).matcher(proposal.speech()).find()) return false;
        String opposite = switch (stance) {
            case SUSPECT -> "(?<!不)(?<!不太)(?<!没)(支持|相信|信任|认可)";
            case SUPPORT -> "(?:(?<!不)(?<!没有)(怀疑|质疑)|不相信|不信任)";
            case OBSERVE -> "(?!)";
        };
        return !java.util.regex.Pattern.compile(opposite + "\\s*" + seat)
                .matcher(proposal.speech()).find();
    }

    public static boolean expressionSafe(String speech) {
        return speech != null && !java.util.regex.Pattern.compile(
                "(我的|我们的)(狼队友|队友)|狼队夜聊|夜间击杀目标|"
                        + "我(?:昨晚|今晚|已经|曾经)?(?:查验|验了|验过|救了|毒了|刀了)|"
                        + "我(?:就)?是(?:狼人|女巫|预言家|猎人)")
                .matcher(speech).find();
    }

    public static AgentView publicView(AgentView view) {
        var me = view.me();
        return new AgentView(view.gameId(), view.version(), view.status(), view.phase(), view.round(), view.turnIndex(),
                view.turnOrder(), view.currentTurn(), view.rules(),
                new AgentView.Self(me.playerId(), me.seatNo(), "UNKNOWN", "UNKNOWN", me.alive(), me.controllerType(),
                        new AgentView.SkillView(false, false, false)),
                view.other().stream().map(s -> new AgentView.SeatView(s.playerId(), s.seatNo(), s.alive(), null, null,
                        s.controllerType())).toList(), view.votes(), view.deaths(), view.speeches(), List.of(), view.winner(), List.of());
    }
}
