package com.wolfmind.application.analysis;

import com.wolfmind.application.agent.trace.AgentTracePort;
import com.wolfmind.application.port.GameViewQueryPort;
import com.wolfmind.domain.game.event.EventEnvelope;
import com.wolfmind.domain.game.model.GameStatus;
import com.wolfmind.domain.game.model.ViewerContext;
import com.wolfmind.domain.game.view.GameViewSnapshot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * AI 分析查询用例（api-spec.md §7）。
 *
 * <p><b>只读、不产生事件、不做可见性判断。</b>事件可见性完全交给
 * {@link GameViewQueryPort#replay}（它内部调领域的 {@code VisibilityFilter}），角色披露交给
 * {@link GameViewQueryPort#view}（领域 {@code GameViewProjector}）。本类只回答"这些事件合起来
 * 说明了什么"。任何在这里再写一遍"谁能看到什么"的实现都是 AP-6。
 *
 * <p><b>披露分层（{@code disclosure}）</b>：按 2026-10-12 批准的政策，进行中只公开记录、
 * 赛后完整披露。因此：
 * <ul>
 *   <li>概览 {@code PUBLIC}：只给公开事实；角色与"投票命中"这类由角色派生的指标为 {@code null}；</li>
 *   <li>概览 {@code FULL}：终局后给完整角色与派生的统计；</li>
 *   <li>决策轨迹：仅在 {@code FULL} 时返回。轨迹是最强的事实披露（它包含候选集与拒绝原因），
 *       进行中把它给出去等于把"谁在怀疑谁"公开。</li>
 * </ul>
 */
public final class AnalysisQueryService {

    /** 单次向视图端口请求的事件窗口；与 api-spec.md §6 的 500 上限一致。 */
    private static final int WINDOW = 500;

    /** 概览最多读取的可见事件数。九人局整局约 200 条，两万条是防御性上界而不是预期值。 */
    static final int MAX_EVENTS = 20_000;

    private final GameViewQueryPort viewQuery;
    private final AgentTracePort traces;

    public AnalysisQueryService(GameViewQueryPort viewQuery, AgentTracePort traces) {
        this.viewQuery = Objects.requireNonNull(viewQuery, "viewQuery");
        this.traces = Objects.requireNonNull(traces, "traces");
    }

    /**
     * 对局概览。
     *
     * @param viewerUserId 已认证账号；{@code null} 表示匿名（只看到公开事实）
     */
    public AnalysisSummary summary(String gameId, String viewerUserId) {
        ViewerContext viewer = viewQuery.viewerContextFor(gameId, viewerUserId);
        GameViewSnapshot view = viewQuery.view(gameId, viewer);
        int tail = viewQuery.currentVersion(gameId);
        History history = visibleHistory(gameId, viewer, tail);
        boolean full = isTerminal(view.status());

        Map<String, String> roleByPlayer = new LinkedHashMap<>();
        Map<String, String> campByPlayer = new LinkedHashMap<>();
        for (GameViewSnapshot.Participant participant : view.players()) {
            if (participant.role() != null) {
                roleByPlayer.put(participant.playerId().value(), participant.role().name());
            }
            if (participant.camp() != null) {
                campByPlayer.put(participant.playerId().value(), participant.camp().name());
            }
        }
        boolean rolesKnown = full && view.players().stream()
                .allMatch(participant -> roleByPlayer.containsKey(participant.playerId().value()));

        Tally tally = tallyEvents(history.events());
        int rounds = tally.rounds() > 0 ? tally.rounds() : view.round();

        // 座位 → 阵营：只在角色已披露时才有内容。它是"投票命中"的唯一输入，
        // 而它必须来自视图而不是从 ROLE_ASSIGNED 事件重建（那是 PRIVATE 事件）。
        Map<Integer, String> campBySeat = new LinkedHashMap<>();
        if (rolesKnown) {
            for (GameViewSnapshot.Participant participant : view.players()) {
                campBySeat.put(participant.seatNo(),
                        participant.camp() == null ? null : participant.camp().name());
            }
        }

        List<AgentStat> players = new ArrayList<>(view.players().size());
        for (GameViewSnapshot.Participant participant : view.players()) {
            players.add(statOf(participant, tally, roleByPlayer, campByPlayer, campBySeat, rounds, gameId));
        }
        players.sort(Comparator.comparingInt(AgentStat::seatNo));

        return new AnalysisSummary(
                gameId,
                view.status().name(),
                full ? "FULL" : "PUBLIC",
                view.winner() == null ? null : view.winner().name(),
                view.finishReason(),
                rounds,
                tail,
                tally.startedAt(),
                tally.finishedAt(),
                players,
                keyEvents(history.events()),
                history.truncated(),
                full
                        ? "统计来自已归档的对局事件与 AGENT_ 决策轨迹；角色在对局结束后完整披露。"
                        : "对局进行中：只展示公开记录，角色与派生指标在对局结束后披露。");
    }

    /**
     * 单个座位的决策轨迹。
     *
     * @param viewerUserId 已认证账号（接口层已强制登录）
     */
    public AgentAnalysis agentAnalysis(String gameId, String viewerUserId, String playerId) {
        ViewerContext viewer = viewQuery.viewerContextFor(gameId, viewerUserId);
        GameViewSnapshot view = viewQuery.view(gameId, viewer);

        GameViewSnapshot.Participant target = null;
        for (GameViewSnapshot.Participant participant : view.players()) {
            if (participant.playerId().value().equals(playerId)) {
                target = participant;
                break;
            }
        }
        if (target == null) {
            // 座位标识不属于本局：这是请求问题，不是权限问题，接口层据此回 404。
            throw new UnknownSeatException(playerId);
        }

        boolean full = isTerminal(view.status());
        List<AgentTracePort.AgentTraceEvent> raw = full
                ? traces.traces(gameId, playerId)
                : List.of();

        List<AgentAnalysis.DecisionTraceView> mapped = new ArrayList<>(raw.size());
        for (AgentTracePort.AgentTraceEvent event : raw) {
            mapped.add(decisionTrace(event));
        }
        mapped.sort(Comparator.comparingInt(AgentAnalysis.DecisionTraceView::version)
                .thenComparingLong(AgentAnalysis.DecisionTraceView::occurredAt));

        String note = full
                ? (raw.isEmpty()
                        ? "本局没有该座位的决策轨迹（可能是规则基线座位，未启用轨迹审计）。"
                        : "轨迹只包含运行时可观察的事实：候选集与打分、工具调用、token 与耗时、是否兜底；不包含模型私有推理。")
                : "对局结束后才开放决策轨迹：它包含候选集与工具调用，属于最强的事实披露。";

        return new AgentAnalysis(
                gameId,
                playerId,
                target.seatNo(),
                target.role() == null ? null : target.role().name(),
                target.camp() == null ? null : target.camp().name(),
                target.controllerType() == null ? null : target.controllerType().name(),
                target.controllerRefId(),
                full ? "FULL" : "PUBLIC",
                mapped,
                note);
    }

    // ------------------------------------------------------------------ 内部

    private static boolean isTerminal(GameStatus status) {
        return status == GameStatus.FINISHED || status == GameStatus.ABORTED;
    }

    /**
     * 按可见性读取整局事件。
     *
     * <p>窗口按"上一次返回的最大版本 + 1"推进，而不是固定步长：可见性过滤会丢掉
     * WOLF/PRIVATE/JUDGE 事件，固定步长会在私有事件密集的阶段把窗口浪费掉。
     * 窗口内一条可见事件都没有时仍必须前进，否则会死循环。
     */
    private History visibleHistory(String gameId, ViewerContext viewer, int tail) {
        List<EventEnvelope> visible = new ArrayList<>();
        boolean truncated = false;
        int from = 1;
        while (from <= tail) {
            if (visible.size() >= MAX_EVENTS) {
                truncated = true;
                break;
            }
            int to = Math.min(tail, from + WINDOW - 1);
            List<EventEnvelope> batch = viewQuery.replay(gameId, viewer, from, to, WINDOW);
            if (batch.isEmpty()) {
                from = to + 1;
                continue;
            }
            visible.addAll(batch);
            int lastVersion = batch.get(batch.size() - 1).version();
            from = Math.max(lastVersion + 1, to + 1);
        }
        if (visible.size() > MAX_EVENTS) {
            visible = new ArrayList<>(visible.subList(0, MAX_EVENTS));
            truncated = true;
        }
        return new History(List.copyOf(visible), truncated);
    }

    /**
     * 事件 → 统计的折叠。
     *
     * <p>刻意用一次线性扫描而不是给每种事件建一个索引：一局约 200 条事件，
     * 而"同一条事件被两个视图读到不同结果"这类缺陷的成本远高于一次线性扫描。
     */
    private static Tally tallyEvents(List<EventEnvelope> events) {
        Map<String, Integer> speeches = new LinkedHashMap<>();
        Map<String, Integer> votes = new LinkedHashMap<>();
        Map<String, Integer> abstains = new LinkedHashMap<>();
        Map<String, List<Integer>> voteTargets = new LinkedHashMap<>();
        Map<String, Integer> deaths = new LinkedHashMap<>();
        int rounds = 0;
        long startedAt = 0L;
        Long finishedAt = null;
        for (EventEnvelope event : events) {
            Map<String, Object> payload = event.payload();
            switch (event.type()) {
                case "GAME_STARTED" -> startedAt = event.occurredAt();
                case "PLAYER_SPOKE" -> bump(speeches, stringOf(payload.get("playerId")));
                case "PLAYER_VOTED" -> {
                    String actor = stringOf(payload.get("playerId"));
                    boolean abstain = Boolean.TRUE.equals(payload.get("abstain"));
                    if (abstain) {
                        bump(abstains, actor);
                    } else {
                        bump(votes, actor);
                        Integer target = intOf(payload.get("targetSeatNo"));
                        if (target != null) {
                            voteTargets.computeIfAbsent(actor, key -> new ArrayList<>()).add(target);
                        }
                    }
                }
                case "DEATH_ANNOUNCED" -> {
                    if (payload.get("deaths") instanceof List<?> list) {
                        for (Object item : list) {
                            if (item instanceof Map<?, ?> death) {
                                String id = stringOf(death.get("playerId"));
                                if (!deaths.containsKey(id)) {
                                    deaths.put(id, event.round());
                                }
                            }
                        }
                    }
                }
                case "GAME_FINISHED" -> {
                    rounds = intOf(payload.get("rounds")) == null ? event.round() : intOf(payload.get("rounds"));
                    finishedAt = event.occurredAt();
                }
                case "GAME_ABORTED" -> finishedAt = event.occurredAt();
                default -> {
                    // 其余事件不参与统计
                }
            }
        }
        return new Tally(speeches, votes, abstains, voteTargets, deaths, rounds, startedAt, finishedAt);
    }

    private AgentStat statOf(
            GameViewSnapshot.Participant participant,
            Tally tally,
            Map<String, String> roleByPlayer,
            Map<String, String> campByPlayer,
            Map<Integer, String> campBySeat,
            int rounds,
            String gameId) {
        String playerId = participant.playerId().value();
        int voteCount = tally.votes().getOrDefault(playerId, 0);
        int abstainCount = tally.abstains().getOrDefault(playerId, 0);
        List<Integer> targets = tally.voteTargets().getOrDefault(playerId, List.of());

        Integer voteHitCount = null;
        Double voteAccuracy = null;
        if (!campBySeat.isEmpty() && voteCount > 0) {
            int hits = 0;
            for (Integer seat : targets) {
                if ("WOLF".equals(campBySeat.get(seat))) {
                    hits++;
                }
            }
            voteHitCount = hits;
            voteAccuracy = round4((double) hits / voteCount);
        }

        Integer deathRound = tally.deaths().get(playerId);
        int survivedRounds = deathRound == null ? rounds : Math.max(0, deathRound - 1);

        List<AgentTracePort.AgentTraceEvent> seatTraces = traces.traces(gameId, playerId);
        int llmCalls = 0;
        int promptTokens = 0;
        int completionTokens = 0;
        int toolCalls = 0;
        int fallbackTurns = 0;
        long totalLatency = 0L;
        long maxLatency = 0L;
        for (AgentTracePort.AgentTraceEvent trace : seatTraces) {
            switch (trace.type()) {
                case AGENT_LLM_INVOKED -> {
                    llmCalls++;
                    promptTokens += number(trace.payload().get("promptTokens"));
                    completionTokens += number(trace.payload().get("completionTokens"));
                    long latency = number(trace.payload().get("latencyMs"));
                    totalLatency += latency;
                    maxLatency = Math.max(maxLatency, latency);
                }
                case AGENT_TOOL_CALLED -> toolCalls++;
                case AGENT_TURN_FINISHED -> {
                    if (Boolean.TRUE.equals(trace.payload().get("fallbackUsed"))) {
                        fallbackTurns++;
                    }
                }
                default -> {
                    // 其余轨迹类型不进入统计
                }
            }
        }

        return new AgentStat(
                playerId,
                participant.seatNo(),
                participant.controllerType() == null ? null : participant.controllerType().name(),
                participant.controllerRefId(),
                participant.alive(),
                roleByPlayer.get(playerId),
                campByPlayer.get(playerId),
                tally.speeches().getOrDefault(playerId, 0),
                voteCount,
                abstainCount,
                voteHitCount,
                voteAccuracy,
                survivedRounds,
                deathRound,
                seatTraces.size(),
                llmCalls,
                promptTokens,
                completionTokens,
                toolCalls,
                fallbackTurns,
                totalLatency,
                maxLatency,
                List.copyOf(targets));
    }

    /**
     * 关键事件列表。
     *
     * <p>只收"推进了对局"的事件类型（发言、投票、计票、死讯、开枪、阶段跳转、终局）。
     * {@code TURN_GRANTED}、{@code ROLE_ASSIGNED} 一类刻意不收：前者每回合一条会淹没时间轴，
     * 后者是私有事件，放进来等于把角色直接印在公开时间轴上。
     */
    private static List<KeyEventView> keyEvents(List<EventEnvelope> events) {
        Set<String> interesting = Set.of(
                "GAME_STARTED", "PHASE_CHANGED", "PLAYER_SPOKE", "SPEECH_SKIPPED", "PLAYER_VOTED",
                "VOTE_TALLY_RESOLVED", "DEATH_ANNOUNCED", "HUNTER_SHOT", "HUNTER_SKIPPED",
                "GAME_FINISHED", "GAME_ABORTED");
        List<KeyEventView> result = new ArrayList<>();
        for (EventEnvelope event : events) {
            if (!interesting.contains(event.type())) {
                continue;
            }
            result.add(new KeyEventView(
                    event.version(),
                    event.type(),
                    event.phase(),
                    event.round(),
                    event.occurredAt(),
                    seatOf(event),
                    describe(event)));
        }
        return List.copyOf(result);
    }

    /**
     * 关键事件的一句话说明。
     *
     * <p>只使用**公开**字段（座位号、动作类型、原因码）；{@code PLAYER_SPOKE} 只报字数不报正文——
     * 正文已经在回放接口里，摘要区再放一份会让页面在两个地方渲染同一段可能被重写的文本。
     */
    private static String describe(EventEnvelope event) {
        Map<String, Object> payload = event.payload();
        return switch (event.type()) {
            case "GAME_STARTED" -> "对局开始，共 " + sizeOf(payload.get("seats")) + " 个座位";
            case "PHASE_CHANGED" -> "阶段 " + payload.get("from") + " → " + payload.get("to");
            case "PLAYER_SPOKE" -> seatLabel(payload) + "发言（"
                    + lengthOf(payload.get("content")) + " 字）";
            case "SPEECH_SKIPPED" -> seatLabel(payload) + "跳过发言（" + payload.get("reason") + "）";
            case "PLAYER_VOTED" -> Boolean.TRUE.equals(payload.get("abstain"))
                    ? seatLabel(payload) + "弃票"
                    : seatLabel(payload) + "投票给 " + payload.get("targetSeatNo") + " 号";
            case "VOTE_TALLY_RESOLVED" -> tallyLabel(payload);
            case "DEATH_ANNOUNCED" -> deathLabel(payload);
            case "HUNTER_SHOT" -> "猎人 " + payload.get("hunterId") + " 开枪带走 "
                    + payload.get("targetSeatNo") + " 号";
            case "HUNTER_SKIPPED" -> "猎人放弃开枪（" + payload.get("reason") + "）";
            case "GAME_FINISHED" -> payload.get("winner") + " 阵营获胜（" + payload.get("reason")
                    + "），共 " + payload.get("rounds") + " 轮";
            case "GAME_ABORTED" -> "对局中止（" + payload.get("reason") + "）";
            default -> event.type();
        };
    }

    private static String tallyLabel(Map<String, Object> payload) {
        StringBuilder builder = new StringBuilder("计票完成：");
        Object result = payload.get("result");
        builder.append(result);
        if (payload.get("exiledPlayerId") != null) {
            builder.append("，放逐 ").append(payload.get("exiledPlayerId"));
        }
        if (payload.get("tieSeats") instanceof List<?> ties && !ties.isEmpty()) {
            builder.append("，平票座位 ").append(ties);
        }
        return builder.toString();
    }

    private static String deathLabel(Map<String, Object> payload) {
        if (!(payload.get("deaths") instanceof List<?> deaths) || deaths.isEmpty()) {
            return "第 " + payload.get("round") + " 轮：平安夜";
        }
        List<String> seats = new ArrayList<>();
        for (Object item : deaths) {
            if (item instanceof Map<?, ?> death && death.get("seatNo") != null) {
                seats.add(death.get("seatNo") + " 号");
            }
        }
        return "第 " + payload.get("round") + " 轮公布死讯：" + String.join("、", seats);
    }

    private static Integer seatOf(EventEnvelope event) {
        Map<String, Object> payload = event.payload();
        Integer seat = intOf(payload.get("seatNo"));
        if (seat != null) {
            return seat;
        }
        return intOf(payload.get("targetSeatNo"));
    }

    private static String seatLabel(Map<String, Object> payload) {
        Object seat = payload.get("seatNo");
        return seat == null ? "某座位" : seat + " 号";
    }

    private static int sizeOf(Object value) {
        return value instanceof List<?> list ? list.size() : 0;
    }

    private static int lengthOf(Object value) {
        return value instanceof String text ? text.length() : 0;
    }

    private static void bump(Map<String, Integer> counter, String key) {
        if (key != null) {
            counter.merge(key, 1, Integer::sum);
        }
    }

    private static String stringOf(Object value) {
        return value instanceof String text ? text : null;
    }

    private static Integer intOf(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private static int number(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static Double round4(double value) {
        return Math.round(value * 10_000d) / 10_000d;
    }

    private static AgentAnalysis.DecisionTraceView decisionTrace(AgentTracePort.AgentTraceEvent event) {
        Map<String, Object> payload = event.payload();
        String kind = switch (event.type()) {
            case AGENT_TURN_STARTED -> "TURN_STARTED";
            case AGENT_CONTEXT_BUILT -> "CONTEXT";
            case AGENT_STRATEGY_DECIDED -> "STRATEGY";
            case AGENT_LLM_INVOKED -> "LLM";
            case AGENT_TOOL_CALLED -> "TOOL";
            case AGENT_OUTPUT_REJECTED -> "REJECTED";
            case AGENT_TURN_TIMEOUT -> "TIMEOUT";
            case AGENT_TURN_FAILED -> "FAILED";
            case AGENT_TURN_FINISHED -> "FINISHED";
        };
        String chosen = stringOf(payload.get("chosen"));
        return new AgentAnalysis.DecisionTraceView(
                event.playerId() + ':' + event.version() + ':' + kind + ':' + event.occurredAt(),
                kind,
                event.version(),
                event.phase(),
                event.round(),
                event.occurredAt(),
                candidatesOf(payload),
                chosen,
                targetSeatOf(chosen),
                booleanOf(payload.get("submitted")),
                booleanOf(payload.get("fallbackUsed")),
                intOf(payload.get("llmCalls")),
                intOf(payload.get("repairRounds")),
                stringOf(payload.get("model")),
                stringOf(payload.get("provider")),
                intOf(payload.get("promptTokens")),
                intOf(payload.get("completionTokens")),
                longOf(payload.get("latencyMs")),
                stringOf(payload.get("tool")),
                stringOf(payload.get("argsDigest")),
                stringOf(payload.get("decision")),
                firstNonNull(stringOf(payload.get("denialCode")), stringOf(payload.get("code"))),
                stringOf(payload.get("stage")),
                rejectionReason(event),
                isEchoStage(payload) ? stringOf(payload.get("draft")) : null);
    }

    private static boolean isEchoStage(Map<String, Object> payload) {
        Object stage = payload.get("stage");
        return "ECHO_CHECK".equals(stage) || "ECHO_CHECK_REWRITE".equals(stage);
    }

    private static String rejectionReason(AgentTracePort.AgentTraceEvent event) {
        Map<String, Object> payload = event.payload();
        if (!isEchoStage(payload) || !(payload.get("similarity") instanceof Number similarity)) {
            return firstNonNull(stringOf(payload.get("reason")), stringOf(payload.get("kind")));
        }
        Integer seatNo = intOf(payload.get("seatNo"));
        String who = seatNo == null ? "此前" : (("p_" + seatNo).equals(event.playerId()) ? "自己" : " " + seatNo + " 号");
        String text = "与" + who + "第 " + number(payload.get("round")) + " 轮发言相似度 "
                + String.format(java.util.Locale.ROOT, "%.2f", similarity.doubleValue());
        Object kept = payload.get("kept");
        if ("ORIGINAL".equals(kept)) {
            return text + "，提交了首稿";
        }
        if ("REWRITE".equals(kept)) {
            return text + "，提交了改写稿";
        }
        return text;
    }

    /**
     * 候选集：策略层把候选序列化为 {@code "ACTION@seat:score"} 字符串（见
     * {@code AgentWorkflow.describeCandidates}）。这里解析回结构化字段，
     * 是为了让前端不必知道这个字符串格式——那属于服务端内部表示。
     */
    private static List<AgentAnalysis.CandidateView> candidatesOf(Map<String, Object> payload) {
        if (!(payload.get("candidates") instanceof List<?> list)) {
            return List.of();
        }
        List<AgentAnalysis.CandidateView> candidates = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof String text)) {
                continue;
            }
            String action = text;
            Integer seat = null;
            double score = 0d;
            int at = action.indexOf('@');
            if (at >= 0) {
                seat = tryParseInt(action.substring(at + 1, action.indexOf(':', at) < 0
                        ? action.length() : action.indexOf(':', at)));
                action = action.substring(0, at);
            }
            int colon = text.lastIndexOf(':');
            if (colon >= 0) {
                try {
                    score = Double.parseDouble(text.substring(colon + 1));
                } catch (NumberFormatException ignored) {
                    score = 0d;
                }
            }
            candidates.add(new AgentAnalysis.CandidateView(action, seat, score));
        }
        return candidates;
    }

    private static Integer targetSeatOf(String chosen) {
        if (chosen == null) {
            return null;
        }
        int at = chosen.indexOf('@');
        return at < 0 ? null : tryParseInt(chosen.substring(at + 1));
    }

    private static Integer tryParseInt(String text) {
        try {
            return Integer.valueOf(text.trim());
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private static Boolean booleanOf(Object value) {
        return value instanceof Boolean flag ? flag : null;
    }

    private static Long longOf(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private static String firstNonNull(String first, String second) {
        return first != null ? first : second;
    }

    /** 概览读取结果：可见事件 + 是否因上限被截断。 */
    private record History(List<EventEnvelope> events, boolean truncated) {}

    /** 事件扫描的中间结果。 */
    private record Tally(
            Map<String, Integer> speeches,
            Map<String, Integer> votes,
            Map<String, Integer> abstains,
            Map<String, List<Integer>> voteTargets,
            Map<String, Integer> deaths,
            int rounds,
            long startedAt,
            Long finishedAt) {}

    /** 请求的座位不属于该对局。接口层映射为 404，而不是 403——"没有这个座位"不是权限问题。 */
    public static final class UnknownSeatException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public UnknownSeatException(String playerId) {
            super("座位不存在：" + playerId);
        }
    }
}
