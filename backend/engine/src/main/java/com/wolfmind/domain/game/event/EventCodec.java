package com.wolfmind.domain.game.event;

import com.wolfmind.domain.game.model.Camp;
import com.wolfmind.domain.game.model.ControllerType;
import com.wolfmind.domain.game.model.DeathCause;
import com.wolfmind.domain.game.model.GameException;
import com.wolfmind.domain.game.model.GameId;
import com.wolfmind.domain.game.model.GamePhase;
import com.wolfmind.domain.game.model.GameRules;
import com.wolfmind.domain.game.model.PlayerId;
import com.wolfmind.domain.game.model.Role;
import com.wolfmind.domain.game.model.RoleComposition;
import com.wolfmind.domain.game.model.SeatNo;
import com.wolfmind.domain.game.model.SkipReason;
import com.wolfmind.domain.game.model.TallyResult;
import com.wolfmind.domain.game.model.VoteTieRule;
import com.wolfmind.domain.game.model.WinCondition;
import com.wolfmind.domain.game.model.WitchMode;
import com.wolfmind.domain.game.model.WolfKillTieBreak;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 事件编解码器：{@code payload} 与具体事件记录之间的双向映射。
 *
 * <p>它是 {@code EventEnvelope}（持久化形态）与 {@link DomainEvent}（领域形态）之间的唯一桥梁，
 * 因此：
 * <ul>
 *   <li>恢复路径（快照 + 增量重放）不需要任何反射或第三方序列化框架；</li>
 *   <li>upcaster 只需要在这里为旧 {@code schemaVersion} 补默认值（event-design.md §6）；</li>
 *   <li>编码与解码放在同一个文件里，字段增删不会出现"只改了一边"的漂移。</li>
 * </ul>
 *
 * <p>解码遇到未知类型或缺失必填字段时显式失败，不做"默认值兜底"：默认值会让重放产生与
 * 原始处理不同的状态（event-design.md §2 完整性规则）。
 */
public final class EventCodec {

    private EventCodec() {}

    /** 把载荷编码为事件专属的 {@code Map}（值只用 String/Integer/Long/Boolean/List/Map）。 */
    public static Map<String, Object> encodePayload(DomainEvent event) {
        return event.payload();
    }

    /**
     * 由信封还原领域事件。
     *
     * @param envelope 持久化形态的事件信封
     * @throws GameException {@code REPLAY_UNKNOWN_EVENT_TYPE} 或 {@code VALIDATION_ERROR}
     */
    public static DomainEvent decode(EventEnvelope envelope) {
        EventType type = EventType.fromCode(envelope.type());
        if (!type.isGameEvent()) {
            throw new GameException(
                    com.wolfmind.domain.game.model.DomainErrorCode.REPLAY_UNKNOWN_EVENT_TYPE,
                    "信封 " + envelope.type() + " 不属于 Game 聚合事件流");
        }
        EventMeta meta = new EventMeta(
                envelope.eventId(),
                envelope.version(),
                envelope.occurredAt(),
                phaseOf(envelope.phase()),
                envelope.round(),
                envelope.actorId(),
                envelope.causationId(),
                envelope.correlationId());
        Map<String, Object> payload = envelope.payload();
        GameId gameId = GameId.of(envelope.gameId());

        return switch (type) {
            case GAME_STARTED -> decodeGameStarted(meta, gameId, payload);
            case ROLE_ASSIGNED -> new RoleAssignedEvent(
                    meta,
                    playerId(payload, "playerId"),
                    seatNo(payload, "seatNo"),
                    role(payload, "role"),
                    camp(payload, "camp"));
            case PHASE_CHANGED -> new PhaseChangedEvent(
                    meta,
                    phaseOf(string(payload, "from")),
                    phaseOf(string(payload, "to")),
                    integer(payload, "turnSeq", 0),
                    optionalPhase(payload, "resumePhase"),
                    optionalLong(payload, "deadline"));
            case TURN_GRANTED -> new TurnGrantedEvent(
                    meta,
                    playerId(payload, "playerId"),
                    seatNo(payload, "seatNo"),
                    phaseOf(string(payload, "phase")),
                    integer(payload, "turnIndex", 0),
                    longValue(payload, "deadline"),
                    controllerType(payload, "controllerType"));
            case PLAYER_SPOKE -> new PlayerSpokeEvent(
                    meta,
                    playerId(payload, "playerId"),
                    seatNo(payload, "seatNo"),
                    string(payload, "speechId"),
                    string(payload, "content"),
                    integer(payload, "turnIndex", 0),
                    phaseOf(string(payload, "phase")));
            case SPEECH_SKIPPED -> new SpeechSkippedEvent(
                    meta,
                    playerId(payload, "playerId"),
                    seatNo(payload, "seatNo"),
                    skipReason(payload, "reason"),
                    integer(payload, "turnIndex", 0));
            case PLAYER_VOTED -> new PlayerVotedEvent(
                    meta,
                    playerId(payload, "playerId"),
                    seatNo(payload, "seatNo"),
                    optionalPlayerId(payload, "targetId"),
                    optionalSeatNo(payload, "targetSeatNo"),
                    bool(payload, "abstain"));
            case VOTE_TALLY_RESOLVED -> decodeTally(meta, payload);
            case WOLF_KILL_NOMINATED -> new WolfKillNominatedEvent(
                    meta,
                    playerId(payload, "wolfId"),
                    seatNo(payload, "wolfSeatNo"),
                    optionalPlayerId(payload, "targetId"),
                    optionalSeatNo(payload, "targetSeatNo"),
                    bool(payload, "emptyKill"));
            case WOLF_KILL_SELECTED -> new WolfKillSelectedEvent(
                    meta,
                    optionalPlayerId(payload, "targetId"),
                    optionalSeatNo(payload, "targetSeatNo"),
                    nominations(payload));
            case WOLF_CHAT_SENT -> new WolfChatSentEvent(
                    meta,
                    playerId(payload, "wolfId"),
                    seatNo(payload, "wolfSeatNo"),
                    string(payload, "chatId"),
                    string(payload, "content"));
            case SEER_INSPECTED -> new SeerInspectedEvent(
                    meta,
                    playerId(payload, "seerId"),
                    optionalSeatNo(payload, "seerSeatNo"),
                    playerId(payload, "targetId"),
                    optionalSeatNo(payload, "targetSeatNo"),
                    camp(payload, "result"));
            case SEER_SKIPPED -> new SeerSkippedEvent(
                    meta,
                    playerId(payload, "seerId"),
                    optionalSeatNo(payload, "seatNo"),
                    skipReason(payload, "reason"));
            case WITCH_SAVED -> new WitchSavedEvent(
                    meta,
                    playerId(payload, "witchId"),
                    optionalSeatNo(payload, "witchSeatNo"),
                    playerId(payload, "targetId"),
                    optionalSeatNo(payload, "targetSeatNo"));
            case WITCH_POISONED -> new WitchPoisonedEvent(
                    meta,
                    playerId(payload, "witchId"),
                    optionalSeatNo(payload, "witchSeatNo"),
                    playerId(payload, "targetId"),
                    optionalSeatNo(payload, "targetSeatNo"));
            case WITCH_PASSED -> new WitchPassedEvent(
                    meta,
                    playerId(payload, "witchId"),
                    optionalSeatNo(payload, "witchSeatNo"),
                    optionalSkipReason(payload, "reason"));
            case HUNTER_SHOT -> new HunterShotEvent(
                    meta,
                    playerId(payload, "hunterId"),
                    optionalSeatNo(payload, "hunterSeatNo"),
                    playerId(payload, "targetId"),
                    optionalSeatNo(payload, "targetSeatNo"));
            case HUNTER_SKIPPED -> new HunterSkippedEvent(
                    meta,
                    playerId(payload, "hunterId"),
                    optionalSeatNo(payload, "hunterSeatNo"),
                    skipReason(payload, "reason"));
            case PLAYER_KILLED -> new PlayerKilledEvent(
                    meta,
                    playerId(payload, "playerId"),
                    seatNo(payload, "seatNo"),
                    deathCauses(payload, "causes"),
                    optionalDeathCause(payload, "primaryCause"));
            case DEATH_ANNOUNCED -> new DeathAnnouncedEvent(meta, deathEntries(payload));
            case GAME_FINISHED -> new GameFinishedEvent(
                    meta,
                    camp(payload, "winner"),
                    string(payload, "reason"),
                    integer(payload, "rounds", meta.round()),
                    seatNoList(payload, "survivors"),
                    playerIdList(payload, "mvpCandidates"));
            case GAME_ABORTED -> new GameAbortedEvent(
                    meta, string(payload, "reason"), integer(payload, "atVersion", meta.version()));
            case GAME_RECOVERED -> new GameRecoveredEvent(
                    meta,
                    integer(payload, "snapshotVersion", 0),
                    integer(payload, "replayedTo", meta.version()),
                    string(payload, "source"));
            case PLAYER_CONTROLLER_CHANGED -> new PlayerControllerChangedEvent(
                    meta,
                    optionalString(payload, "roomId"),
                    playerId(payload, "playerId"),
                    seatNo(payload, "seatNo"),
                    optionalControllerType(payload, "fromType"),
                    optionalString(payload, "fromRefId"),
                    controllerType(payload, "toType"),
                    string(payload, "refId"));
            default -> throw new GameException(
                    com.wolfmind.domain.game.model.DomainErrorCode.REPLAY_UNKNOWN_EVENT_TYPE,
                    "Game 事件流不支持的事件类型：" + type.code());
        };
    }

    // ------------------------------------------------------------------
    // 复合字段
    // ------------------------------------------------------------------

    private static GameStartedEvent decodeGameStarted(
            EventMeta meta, GameId gameId, Map<String, Object> payload) {
        List<GameStartedEvent.SeatSpec> seats = new ArrayList<>();
        for (Object raw : list(payload, "seats")) {
            Map<String, Object> item = asMap(raw);
            seats.add(new GameStartedEvent.SeatSpec(
                    integer(item, "seatNo", 0),
                    string(item, "playerId"),
                    string(item, "controllerType"),
                    string(item, "refId")));
        }
        return new GameStartedEvent(
                meta,
                gameId,
                string(payload, "roomId"),
                seats,
                roleComposition(payload),
                rules(payload),
                intList(payload, "turnOrder"),
                integer(payload, "round", 1));
    }

    private static VoteTallyResolvedEvent decodeTally(EventMeta meta, Map<String, Object> payload) {
        List<VoteTallyResolvedEvent.TallyEntry> tally = new ArrayList<>();
        for (Object raw : list(payload, "tally")) {
            Map<String, Object> item = asMap(raw);
            tally.add(new VoteTallyResolvedEvent.TallyEntry(
                    integer(item, "seatNo", 0), integer(item, "votes", 0)));
        }
        return new VoteTallyResolvedEvent(
                meta,
                TallyResult.Result.valueOf(string(payload, "result")),
                tally,
                integer(payload, "maxVotes", 0),
                optionalPlayerId(payload, "exiledPlayerId"),
                intList(payload, "tieSeats"));
    }

    private static List<WolfKillSelectedEvent.Nomination> nominations(Map<String, Object> payload) {
        List<WolfKillSelectedEvent.Nomination> list = new ArrayList<>();
        for (Object raw : list(payload, "nominations")) {
            Map<String, Object> item = asMap(raw);
            list.add(new WolfKillSelectedEvent.Nomination(
                    integer(item, "wolfSeatNo", 0), optionalInteger(item, "targetSeatNo")));
        }
        return list;
    }

    private static List<DeathAnnouncedEvent.DeathEntry> deathEntries(Map<String, Object> payload) {
        List<DeathAnnouncedEvent.DeathEntry> list = new ArrayList<>();
        for (Object raw : list(payload, "deaths")) {
            Map<String, Object> item = asMap(raw);
            list.add(new DeathAnnouncedEvent.DeathEntry(
                    playerId(item, "playerId"), seatNo(item, "seatNo")));
        }
        return list;
    }

    private static RoleComposition roleComposition(Map<String, Object> payload) {
        Map<String, Object> raw = asMap(payload.get("roleComposition"));
        Map<Role, Integer> counts = new EnumMap<>(Role.class);
        raw.forEach((key, value) -> counts.put(Role.valueOf(key), asInt(value)));
        return new RoleComposition(counts);
    }

    private static GameRules rules(Map<String, Object> payload) {
        Map<String, Object> raw = asMap(payload.get("rules"));
        return new GameRules(
                integer(raw, "speechMaxLength", GameRules.DEFAULT.speechMaxLength()),
                longValue(raw, "turnDeadlineMs"),
                longValue(raw, "agentTurnDeadlineMs"),
                longValue(raw, "voteDeadlineMs"),
                bool(raw, "firstNightLastWords"),
                bool(raw, "laterNightLastWords"),
                bool(raw, "exileLastWords"),
                // 2026-10-12 新增；此前的对局按当时的行为（白天被枪杀按夜间死亡规则）重放
                bool(raw, "dayShotLastWords", false),
                bool(raw, "witchSelfSave"),
                // 2026-10-13 新增；此前的对局 witchSelfSave=true 即全程可自救，按当时的行为重放
                bool(raw, "witchSelfSaveFirstNightOnly", false),
                bool(raw, "witchSaveTargetMustBeKilled"),
                bool(raw, "allowWolfEmptyKill"),
                WolfKillTieBreak.valueOf(string(raw, "wolfKillTieBreak")),
                VoteTieRule.valueOf(string(raw, "voteTieRule")),
                bool(raw, "hunterCanShootWhenPoisoned"),
                WinCondition.valueOf(string(raw, "winCondition")),
                bool(raw, "abstainAllowed"),
                // 2026-10-12 新增；此前的对局没有停滞中止，按 0（不启用）重放
                integer(raw, "stalledRoundsLimit", 0));
    }

    // ------------------------------------------------------------------
    // 标量读取：缺失必填字段一律显式失败
    // ------------------------------------------------------------------

    private static Object require(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value == null) {
            throw new GameException(
                    com.wolfmind.domain.game.model.DomainErrorCode.VALIDATION_ERROR,
                    "事件载荷缺少必填字段 " + key);
        }
        return value;
    }

    private static String string(Map<String, Object> payload, String key) {
        return String.valueOf(require(payload, key));
    }

    private static String optionalString(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static boolean bool(Map<String, Object> payload, String key) {
        Object value = require(payload, key);
        if (value instanceof Boolean flag) {
            return flag;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private static boolean bool(Map<String, Object> payload, String key, boolean fallback) {
        return payload.get(key) == null ? fallback : bool(payload, key);
    }

    private static int integer(Map<String, Object> payload, String key, int fallback) {
        Object value = payload.get(key);
        return value == null ? fallback : asInt(value);
    }

    private static Integer optionalInteger(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value == null ? null : asInt(value);
    }

    private static long longValue(Map<String, Object> payload, String key) {
        Object value = require(payload, key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    private static Long optionalLong(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    private static int asInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }

    private static PlayerId playerId(Map<String, Object> payload, String key) {
        return PlayerId.of(string(payload, key));
    }

    private static PlayerId optionalPlayerId(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value == null ? null : PlayerId.of(String.valueOf(value));
    }

    private static SeatNo seatNo(Map<String, Object> payload, String key) {
        return SeatNo.of(asInt(require(payload, key)));
    }

    private static SeatNo optionalSeatNo(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value == null ? null : SeatNo.of(asInt(value));
    }

    private static Role role(Map<String, Object> payload, String key) {
        return Role.valueOf(string(payload, key));
    }

    private static Camp camp(Map<String, Object> payload, String key) {
        return Camp.valueOf(string(payload, key));
    }

    private static GamePhase phaseOf(String code) {
        try {
            return GamePhase.valueOf(code);
        } catch (IllegalArgumentException exception) {
            throw new GameException(
                    com.wolfmind.domain.game.model.DomainErrorCode.REPLAY_UNKNOWN_EVENT_TYPE,
                    "未知阶段：" + code);
        }
    }

    private static GamePhase optionalPhase(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value == null ? null : phaseOf(String.valueOf(value));
    }

    private static ControllerType controllerType(Map<String, Object> payload, String key) {
        return ControllerType.valueOf(string(payload, key));
    }

    private static ControllerType optionalControllerType(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value == null ? null : ControllerType.valueOf(String.valueOf(value));
    }

    private static SkipReason skipReason(Map<String, Object> payload, String key) {
        return SkipReason.valueOf(string(payload, key));
    }

    private static SkipReason optionalSkipReason(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value == null ? null : SkipReason.valueOf(String.valueOf(value));
    }

    private static List<DeathCause> deathCauses(Map<String, Object> payload, String key) {
        List<DeathCause> causes = new ArrayList<>();
        for (Object raw : list(payload, key)) {
            causes.add(DeathCause.valueOf(String.valueOf(raw)));
        }
        return causes;
    }

    private static DeathCause optionalDeathCause(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value == null ? null : DeathCause.valueOf(String.valueOf(value));
    }

    private static List<Integer> intList(Map<String, Object> payload, String key) {
        List<Integer> values = new ArrayList<>();
        Object raw = payload.get(key);
        if (raw == null) {
            return values;
        }
        for (Object item : asList(raw)) {
            values.add(asInt(item));
        }
        return values;
    }

    private static List<SeatNo> seatNoList(Map<String, Object> payload, String key) {
        List<SeatNo> values = new ArrayList<>();
        Object raw = payload.get(key);
        if (raw == null) {
            return values;
        }
        for (Object item : asList(raw)) {
            values.add(SeatNo.of(asInt(item)));
        }
        return values;
    }

    private static List<PlayerId> playerIdList(Map<String, Object> payload, String key) {
        List<PlayerId> values = new ArrayList<>();
        Object raw = payload.get(key);
        if (raw == null) {
            return values;
        }
        for (Object item : asList(raw)) {
            values.add(PlayerId.of(String.valueOf(item)));
        }
        return values;
    }

    private static List<?> list(Map<String, Object> payload, String key) {
        Object value = require(payload, key);
        if (value instanceof List<?> items) {
            return items;
        }
        throw new GameException(
                com.wolfmind.domain.game.model.DomainErrorCode.VALIDATION_ERROR,
                "事件载荷字段 " + key + " 不是数组");
    }

    private static List<?> asList(Object value) {
        if (value instanceof List<?> items) {
            return items;
        }
        throw new GameException(
                com.wolfmind.domain.game.model.DomainErrorCode.VALIDATION_ERROR,
                "事件载荷字段不是数组：" + value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new GameException(
                com.wolfmind.domain.game.model.DomainErrorCode.VALIDATION_ERROR,
                "事件载荷字段不是对象：" + value);
    }

    /** 便于测试：把事件与版本一起编码成信封形态。 */
    public static Map<String, Object> envelopeFields(DomainEvent event) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("eventId", event.eventId());
        fields.put("version", event.version());
        fields.put("type", event.type().code());
        fields.put("schemaVersion", event.schemaVersion());
        fields.put("occurredAt", event.occurredAt());
        fields.put("phase", event.phase().name());
        fields.put("round", event.round());
        fields.put("actorId", event.actorId());
        fields.put("visibility", event.visibility().name());
        fields.put("causationId", event.causationId());
        fields.put("correlationId", event.correlationId());
        fields.put("payload", event.payload());
        return fields;
    }

    /** 便于实验室与调试：把 {@link WitchMode} 映射为行动类型字符串。 */
    public static String actionTypeOf(WitchMode mode) {
        return switch (mode) {
            case SAVE -> "WITCH_SAVE";
            case POISON -> "WITCH_POISON";
            case PASS -> "WITCH_PASS";
        };
    }
}
