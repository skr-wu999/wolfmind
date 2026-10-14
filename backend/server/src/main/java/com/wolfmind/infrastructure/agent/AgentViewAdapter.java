package com.wolfmind.infrastructure.agent;

import com.wolfmind.application.agent.model.AgentView;
import com.wolfmind.application.agent.view.AgentViewQueryPort;
import com.wolfmind.application.port.GameViewQueryPort;
import com.wolfmind.domain.game.event.EventEnvelope;
import com.wolfmind.domain.game.view.GameViewSnapshot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link AgentViewQueryPort} 的基础设施实现：把领域投影"翻译"成 Agent 形状。
 *
 * <p>它存在的唯一理由是 DEP-7：{@code application.agent} 不能引用 {@code domain.game.model}，
 * 而构造 {@code AgentView} 必须读 {@code GameViewSnapshot}（含领域枚举）。翻译放在
 * {@code infrastructure.agent}，Agent 侧只拿到字符串表达的角色与阶段。
 *
 * <p><b>不自己判断可见性</b>（AP-6）：谁能看到谁的 {@code role} 由 {@code GameViewProjector}
 * 决定，哪些事件可见由 {@code VisibilityFilter} 决定。本类只做三件事：
 * 取"该座位可见的投影与事件"、把字段改名成 Agent 形状、把事件摘要成
 * {@code AgentView.PrivateFact} 的封闭词表。
 *
 * <p><b>窗口取舍</b>：单次回放上限 500 条（api-spec §6），因此事件从<b>最新往前</b>取
 * （{@code from = version - 499}）而不是从第 1 条开始——私有事实（查验结果、用药记录）
 * 越近越可能被用到，而被截断的旧事实最多让模型"忘了很久以前的一步"，不会让它看到不该看的。
 * Phase 3 的单局事件数远小于 500，这条取舍只在长局里生效。
 *
 * <p><b>{@code NIGHT_KILL} 是规则要求的定向披露</b>：狼刀定案是 {@code WOLF} 可见性事件，
 * 女巫（村民阵营）按可见性矩阵本来就看不到；但"解药只能救当夜被刀者"
 * （{@code rules.witchSaveTargetMustBeKilled}，domain-model.md §6.1 V7）要求她知道刀口，
 * 否则解药永远用不出去。因此这里<b>只对女巫座位</b>、<b>只取当前轮的
 * {@code WOLF_KILL_SELECTED}</b>补一条 {@code NIGHT_KILL} 事实。这不是可见性漏洞：
 * 披露范围被限制在一个座位、一条事实、一个轮次，且不经过任何通用读取路径。
 */
public final class AgentViewAdapter implements AgentViewQueryPort {

    /** 单次回放窗口：与 {@code GameViewQueryPort.replay} 的文档上限 500 一致。 */
    public static final int REPLAY_WINDOW = 500;

    private final GameViewQueryPort query;

    public AgentViewAdapter(GameViewQueryPort query) {
        if (query == null) {
            throw new IllegalArgumentException("AgentViewAdapter 需要 GameViewQueryPort");
        }
        this.query = query;
    }

    @Override
    public AgentView viewFor(String gameId, String playerId) {
        GameViewSnapshot snapshot = read(gameId, playerId);
        if (snapshot == null || snapshot.me() == null) {
            // 座位不属于本局（或对局不存在）：端口契约允许返回 null，运行时据此判定身份不成立
            return null;
        }
        List<EventEnvelope> events = visibleEvents(gameId, snapshot, playerId);
        List<AgentView.PrivateFact> facts = new ArrayList<>(factsFrom(snapshot, events));
        if ("WITCH".equals(roleName(snapshot))) {
            facts.addAll(nightKillFacts(gameId, snapshot));
        }
        return build(snapshot, List.copyOf(facts), events);
    }

    @Override
    public boolean isSeatInGame(String gameId, String playerId) {
        return viewFor(gameId, playerId) != null;
    }

    // ---------------------------------------------------------------- 读取

    /**
     * 读取该座位的投影。
     *
     * <p>狼人座位要读两次：第一次只知道"我是这个座位"，投影会遮住队友；把"我是狼人"作为
     * {@code wolfPlayerIds} 再读一次，投影才会放出队友角色（这与
     * {@code InMemoryGameViewQuery.contextFor} 的做法一致，没有第二份判定）。
     *
     * <p>对局不存在时 {@code GameViewQueryPort} 会抛领域异常；这里一律按"没有可用视图"处理并
     * 返回 {@code null}。理由：端口契约明确允许返回空，而为了 catch 一个异常类型再多 import 一个
     * {@code domain.game.model} 类型，会让"本包只碰读取者身份"这条纪律多一个例外。
     */
    private GameViewSnapshot read(String gameId, String playerId) {
        try {
            GameViewSnapshot first = query.view(gameId, ViewerContexts.identity(playerId));
            if (first == null || first.me() == null) {
                return first;
            }
            if (!"WEREWOLF".equals(first.me().role() == null ? null : first.me().role().name())) {
                return first;
            }
            return query.view(gameId, ViewerContexts.forSeat(first, playerId));
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    /**
     * 该座位可见的事件（从最新往前取，见类注释的窗口取舍）。
     *
     * <p>读取者上下文放在方法体内用 {@code var} 承接：{@code ViewerContext} 是本包刻意不命名的
     * 领域类型（见 {@link ViewerContexts}），写成方法参数就必须写它的类型名。
     */
    private List<EventEnvelope> visibleEvents(String gameId, GameViewSnapshot snapshot, String playerId) {
        var viewer = ViewerContexts.forSeat(snapshot, playerId);
        return query.replay(
                gameId, viewer, Math.max(1, snapshot.version() - REPLAY_WINDOW + 1),
                Integer.MAX_VALUE, REPLAY_WINDOW);
    }

    // ---------------------------------------------------------------- 私有事实

    /** 该座位可见的私有事实（封闭词表，未知 kind 不进结果）。 */
    private List<AgentView.PrivateFact> factsFrom(GameViewSnapshot snapshot, List<EventEnvelope> events) {
        String myPlayerId = snapshot.me().playerId().value();
        int mySeat = snapshot.me().seatNo();
        Map<String, AgentView.PrivateFact> bySeatAndKind = new LinkedHashMap<>();

        for (EventEnvelope envelope : events) {
            switch (envelope.type()) {
                case "SEER_INSPECTED" -> {
                    if (mySeat == intOf(envelope.payload().get("seerSeatNo"), -1)) {
                        put(bySeatAndKind, AgentView.PrivateFact.of(
                                AgentView.PrivateFact.FactKind.SEER_INSPECT,
                                intOf(envelope.payload().get("targetSeatNo"), -1),
                                String.valueOf(envelope.payload().get("result")),
                                envelope.round()));
                    }
                }
                case "WITCH_SAVED" -> {
                    if (myPlayerId.equals(String.valueOf(envelope.payload().get("witchId")))) {
                        put(bySeatAndKind, AgentView.PrivateFact.of(
                                AgentView.PrivateFact.FactKind.WITCH_SAVED,
                                intOf(envelope.payload().get("targetSeatNo"), -1),
                                "SAVED",
                                envelope.round()));
                    }
                }
                case "WITCH_POISONED" -> {
                    if (myPlayerId.equals(String.valueOf(envelope.payload().get("witchId")))) {
                        put(bySeatAndKind, AgentView.PrivateFact.of(
                                AgentView.PrivateFact.FactKind.WITCH_POISONED,
                                intOf(envelope.payload().get("targetSeatNo"), -1),
                                "POISONED",
                                envelope.round()));
                    }
                }
                default -> {
                    // 狼队友不从这里取：ROLE_ASSIGNED 是 PRIVATE，狼人看不到队友那一条（见下）
                }
            }
        }

        // 狼队友以"投影已经给出的角色"为准：可见性矩阵的"狼人可见队友"行由 GameViewProjector
        // 实现，狼人的 players[] 里队友 role=WEREWOLF，村民的 players[] 里一律为 null。
        // 如果拿事件当来源，狼人一条 WOLF_TEAMMATE 都拿不到——那是把"事件可见性"当成了
        // "角色可见性"，两者在 PRIVATE 与 WOLF 的分类下并不等价。
        for (GameViewSnapshot.Participant participant : snapshot.players()) {
            if (participant.seatNo() == mySeat || participant.role() == null) {
                continue;
            }
            if ("WEREWOLF".equals(participant.role().name())) {
                put(bySeatAndKind, AgentView.PrivateFact.of(
                        AgentView.PrivateFact.FactKind.WOLF_TEAMMATE,
                        participant.seatNo(),
                        "WEREWOLF",
                        1));
            }
        }
        return List.copyOf(bySeatAndKind.values());
    }

    /**
     * 女巫的当夜刀口（定向披露，见类注释）。取当前轮最后一条 {@code WOLF_KILL_SELECTED}：
     * 空刀没有可救的对象，因此不产生事实。
     */
    private List<AgentView.PrivateFact> nightKillFacts(String gameId, GameViewSnapshot snapshot) {
        int round = snapshot.round();
        AgentView.PrivateFact latest = null;
        for (EventEnvelope envelope : godViewEvents(gameId, snapshot.version())) {
            if (!"WOLF_KILL_SELECTED".equals(envelope.type()) || envelope.round() != round) {
                continue;
            }
            int target = intOf(envelope.payload().get("targetSeatNo"), -1);
            if (target < 1) {
                continue;
            }
            latest = AgentView.PrivateFact.of(
                    AgentView.PrivateFact.FactKind.NIGHT_KILL, target, "KILLED", round);
        }
        return latest == null ? List.of() : List.of(latest);
    }

    /** 上帝视角只在这一处、只为 {@code WOLF_KILL_SELECTED} 使用（见类注释的定向披露说明）。 */
    private List<EventEnvelope> godViewEvents(String gameId, int version) {
        var god = ViewerContexts.nightKillReader();
        try {
            return query.replay(
                    gameId, god, Math.max(1, version - REPLAY_WINDOW + 1),
                    Integer.MAX_VALUE, REPLAY_WINDOW);
        } catch (RuntimeException unavailable) {
            return List.of();
        }
    }

    /** 同一 (座位, kind) 只保留最后一条：重复的用药/查验记录被后一条覆盖，而不是堆成两份。 */
    private static void put(Map<String, AgentView.PrivateFact> target, AgentView.PrivateFact fact) {
        if (fact.targetSeat() >= 1) {
            target.put(fact.targetSeat() + ":" + fact.kind(), fact);
        }
    }

    // ---------------------------------------------------------------- 组装

    private static AgentView build(
            GameViewSnapshot snapshot, List<AgentView.PrivateFact> facts, List<EventEnvelope> events) {
        List<AgentView.SeatView> others = new ArrayList<>();
        for (GameViewSnapshot.Participant participant : snapshot.players()) {
            if (participant.playerId().equals(snapshot.me().playerId())) {
                continue;
            }
            others.add(new AgentView.SeatView(
                    participant.playerId().value(),
                    participant.seatNo(),
                    participant.alive(),
                    participant.role() == null ? null : participant.role().name(),
                    participant.camp() == null ? null : participant.camp().name(),
                    participant.controllerType() == null ? null : participant.controllerType().name()));
        }

        List<AgentView.SpeechView> speeches = new ArrayList<>();
        for (EventEnvelope envelope : events) {
            if (!"PLAYER_SPOKE".equals(envelope.type())) {
                continue;
            }
            speeches.add(new AgentView.SpeechView(
                    intOf(envelope.payload().get("seatNo"), 0),
                    envelope.round(),
                    String.valueOf(envelope.payload().get("phase")),
                    intOf(envelope.payload().get("turnIndex"), 0),
                    String.valueOf(envelope.payload().get("content"))));
        }

        List<AgentView.VoteView> votes = snapshot.votes().stream()
                .map(vote -> new AgentView.VoteView(vote.voterSeat(), vote.targetSeatNo(), vote.abstain()))
                .toList();
        Map<Integer, String> ways = deathWays(snapshot, events);
        String fallbackWay = snapshot.version() <= REPLAY_WINDOW
                ? AgentView.DeathView.NIGHT
                : AgentView.DeathView.UNKNOWN;
        List<AgentView.DeathView> deaths = snapshot.deaths().stream()
                .map(death -> new AgentView.DeathView(
                        death.seatNo(), death.round(), ways.getOrDefault(death.seatNo(), fallbackWay)))
                .toList();

        GameViewSnapshot.CurrentTurn turn = snapshot.currentTurn();
        AgentView.CurrentTurn currentTurn = turn == null ? null : new AgentView.CurrentTurn(
                turn.playerId().value(), turn.seatNo(), turn.phase().name(), turn.turnIndex(), turn.deadline());

        GameViewSnapshot.Participant me = snapshot.me();
        var skills = me.skillState();
        AgentView.Self self = new AgentView.Self(
                me.playerId().value(),
                me.seatNo(),
                me.role() == null ? null : me.role().name(),
                me.camp() == null ? null : me.camp().name(),
                me.alive(),
                me.controllerType() == null ? null : me.controllerType().name(),
                skills == null
                        ? new AgentView.SkillView(false, false, false)
                        : new AgentView.SkillView(
                                skills.antidoteAvailable(), skills.poisonAvailable(), skills.shotAvailable()));

        return new AgentView(
                snapshot.gameId(),
                snapshot.version(),
                snapshot.status().name(),
                snapshot.phase().name(),
                snapshot.round(),
                snapshot.turnIndex(),
                snapshot.turnOrder(),
                currentTurn,
                snapshot.rules(),
                self,
                List.copyOf(others),
                List.copyOf(votes),
                List.copyOf(deaths),
                List.copyOf(speeches),
                facts,
                snapshot.winner() == null ? null : snapshot.winner().name(),
                wolfChat(snapshot.round(), events));
    }

    /**
     * 本轮狼队夜聊。{@code WOLF_CHAT_SENT} 是 {@code WOLF} 事件，{@code events} 已按读取者过滤，
     * 村民阵营在这里永远拿到空列表；只取本轮，是因为刀人决策只需要今晚的讨论。
     */
    private static List<AgentView.WolfChatView> wolfChat(int round, List<EventEnvelope> events) {
        List<AgentView.WolfChatView> chat = new ArrayList<>();
        for (EventEnvelope envelope : events) {
            if ("WOLF_CHAT_SENT".equals(envelope.type()) && envelope.round() == round) {
                chat.add(new AgentView.WolfChatView(
                        intOf(envelope.payload().get("wolfSeatNo"), 0),
                        envelope.round(),
                        String.valueOf(envelope.payload().get("content"))));
            }
        }
        return List.copyOf(chat);
    }

    /**
     * 出局方式只从公开事件取：放逐看 {@code VOTE_TALLY_RESOLVED(result=EXILE)}，枪击看
     * {@code HUNTER_SHOT}。死因（狼刀 / 毒药）在 {@code JUDGE} 事件里，这里拿不到也不需要。
     */
    private static Map<Integer, String> deathWays(GameViewSnapshot snapshot, List<EventEnvelope> events) {
        Map<String, Integer> seatByPlayer = new LinkedHashMap<>();
        for (GameViewSnapshot.Participant participant : snapshot.players()) {
            seatByPlayer.put(participant.playerId().value(), participant.seatNo());
        }
        Map<Integer, String> ways = new LinkedHashMap<>();
        for (EventEnvelope envelope : events) {
            switch (envelope.type()) {
                case "VOTE_TALLY_RESOLVED" -> {
                    Integer seat = seatByPlayer.get(String.valueOf(envelope.payload().get("exiledPlayerId")));
                    if ("EXILE".equals(envelope.payload().get("result")) && seat != null) {
                        ways.put(seat, AgentView.DeathView.EXILE);
                    }
                }
                case "HUNTER_SHOT" -> {
                    int seat = intOf(envelope.payload().get("targetSeatNo"), -1);
                    if (seat >= 1) {
                        ways.put(seat, AgentView.DeathView.HUNTER_SHOT);
                    }
                }
                default -> {
                }
            }
        }
        return ways;
    }

    private static String roleName(GameViewSnapshot snapshot) {
        return snapshot.me() == null || snapshot.me().role() == null
                ? null
                : snapshot.me().role().name();
    }

    private static int intOf(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    /** 便于测试与诊断：某座位可见的事件类型集合（不含 payload）。 */
    public Set<String> visibleEventTypes(String gameId, String playerId) {
        GameViewSnapshot snapshot = read(gameId, playerId);
        if (snapshot == null || snapshot.me() == null) {
            return Set.of();
        }
        Set<String> types = new LinkedHashSet<>();
        for (EventEnvelope envelope : visibleEvents(gameId, snapshot, playerId)) {
            types.add(envelope.type());
        }
        return Set.copyOf(types);
    }
}
