package com.wolfmind.infrastructure.memory;

import com.wolfmind.domain.game.event.WolfKillSelectedEvent;
import com.wolfmind.domain.game.model.Camp;
import com.wolfmind.domain.game.model.ControllerType;
import com.wolfmind.domain.game.model.DeathCause;
import com.wolfmind.domain.game.model.DeathRecord;
import com.wolfmind.domain.game.model.GameId;
import com.wolfmind.domain.game.model.GamePhase;
import com.wolfmind.domain.game.model.GameRules;
import com.wolfmind.domain.game.model.GameSnapshot;
import com.wolfmind.domain.game.model.GameStatus;
import com.wolfmind.domain.game.model.PlayerController;
import com.wolfmind.domain.game.model.PlayerId;
import com.wolfmind.domain.game.model.PlayerSnapshot;
import com.wolfmind.domain.game.model.Role;
import com.wolfmind.domain.game.model.RoleComposition;
import com.wolfmind.domain.game.model.SeatNo;
import com.wolfmind.domain.game.model.SkillState;
import com.wolfmind.domain.game.model.Turn;
import com.wolfmind.domain.game.model.Vote;
import com.wolfmind.domain.game.model.VoteTieRule;
import com.wolfmind.domain.game.model.WinCondition;
import com.wolfmind.domain.game.model.WolfKillTieBreak;
import com.wolfmind.infrastructure.json.Json;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.CRC32;

/**
 * {@link GameSnapshot} 的确定性序列化：快照的状态文本与 checksum。
 *
 * <p>为什么不用 Java 序列化：快照要跨进程、跨版本、跨存储（Redis String 与 SQLite
 * {@code game_history.content}）读取，{@code serialVersionUID} 一变就全读不出来。JSON 文本
 * 与 event-design.md §8 的字段清单一一对应，结构变更可被审查。
 *
 * <p>字段命名与类型：
 * <ul>
 *   <li>枚举一律写名称字符串，不写序号：序号会随枚举声明顺序变化；</li>
 *   <li>座位号写整数，玩家引用写 {@code playerId} 字符串；</li>
 *   <li>{@code votes} 是数组而非对象（键是座位号整数，JSON 对象键只能是字符串，
 *       用数组避免"字符串键 ↔ 整数键"的隐式转换）；</li>
 *   <li>可选字段显式写 {@code null}，不用"缺字段"表达：缺字段与 null 在跨版本读取时含义不同；</li>
 *   <li>集合按座位号排序后输出，保证同一状态每次序列化得到逐字节相同的文本。</li>
 * </ul>
 *
 * <p>checksum 用 {@link CRC32} 计算，与 redis-schema.md §7.1 一致，只用于检测损坏，不用于安全用途。
 */
public final class GameSnapshotCodec {

    private GameSnapshotCodec() {}

    /** 把快照序列化为单行 JSON 文本。 */
    public static String encode(GameSnapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("snapshot 不能为空");
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("skillStateSchemaVersion", 2);
        root.put("gameId", snapshot.gameId().value());
        root.put("version", snapshot.version());
        root.put("status", snapshot.status().name());
        root.put("phase", snapshot.phase().name());
        root.put("round", snapshot.round());
        root.put("roleComposition", encodeComposition(snapshot.roleComposition()));
        root.put("rules", encodeRules(snapshot.rules()));
        root.put("roomId", snapshot.roomId());
        root.put("players", snapshot.players().stream().map(GameSnapshotCodec::encodePlayer).toList());
        root.put("turnOrder", snapshot.turnOrder());
        root.put("turnIndex", snapshot.turnIndex());
        root.put("previousDiscussionFirst", snapshot.previousDiscussionFirst());
        root.put("currentTurn", encodeTurn(snapshot.currentTurn()));
        root.put("wolfKillTarget", snapshot.wolfKillTarget());
        root.put("wolfNominations",
                snapshot.wolfNominations().stream().map(GameSnapshotCodec::encodeNomination).toList());
        root.put("witchSaved", snapshot.witchSaved());
        root.put("witchPoisoned", snapshot.witchPoisoned());
        root.put("witchPassed", snapshot.witchPassed());
        root.put("inspectedTarget", snapshot.inspectedTarget());
        root.put("inspectResult", snapshot.inspectResult() == null ? null : snapshot.inspectResult().name());
        root.put("votes", encodeVotes(snapshot.votes()));
        root.put("deaths", snapshot.deaths().stream().map(GameSnapshotCodec::encodeDeath).toList());
        root.put("resumePhase", snapshot.resumePhase() == null ? null : snapshot.resumePhase().name());
        root.put("pendingAfterLastWords",
                snapshot.pendingAfterLastWords() == null ? null : snapshot.pendingAfterLastWords().name());
        root.put("pendingLastWords", snapshot.pendingLastWords());
        root.put("lastWordsIndex", snapshot.lastWordsIndex());
        root.put("pendingHunter", snapshot.pendingHunter());
        root.put("pendingShotVictim", snapshot.pendingShotVictim());
        root.put("winner", snapshot.winner() == null ? null : snapshot.winner().name());
        root.put("finishReason", snapshot.finishReason());
        root.put("wolfChatCounts", encodeWolfChatCounts(snapshot.wolfChatCounts()));
        root.put("stateVersion", snapshot.stateVersion());
        return Json.write(root);
    }

    /** Legacy snapshots did not consume witch potions and must be rebuilt from committed events. */
    public static boolean hasCurrentSkillState(String state) {
        Object version = Json.readObject(state).get("skillStateSchemaVersion");
        return version instanceof Number number && number.doubleValue() == 2;
    }

    /** 解析快照状态文本；字段缺失或类型不符时抛 {@link SnapshotFormatException}。 */
    public static GameSnapshot decode(String state) {
        Map<String, Object> root = Json.readObject(state);
        try {
            int version = integer(root, "version");
            return new GameSnapshot(
                    GameId.of(string(root, "gameId")),
                    version,
                    GameStatus.valueOf(string(root, "status")),
                    GamePhase.valueOf(string(root, "phase")),
                    integer(root, "round"),
                    decodeComposition(object(root, "roleComposition")),
                    decodeRules(object(root, "rules")),
                    optionalString(root, "roomId"),
                    decodePlayers(array(root, "players")),
                    integers(array(root, "turnOrder")),
                    integer(root, "turnIndex"),
                    optionalInteger(root, "previousDiscussionFirst"),
                    decodeTurn(optionalObject(root, "currentTurn")),
                    optionalInteger(root, "wolfKillTarget"),
                    decodeNominations(optionalArray(root, "wolfNominations")),
                    optionalInteger(root, "witchSaved"),
                    optionalInteger(root, "witchPoisoned"),
                    bool(root, "witchPassed"),
                    optionalInteger(root, "inspectedTarget"),
                    optionalCamp(root, "inspectResult"),
                    decodeVotes(optionalArray(root, "votes")),
                    decodeDeaths(array(root, "deaths")),
                    optionalPhase(root, "resumePhase"),
                    optionalPhase(root, "pendingAfterLastWords"),
                    integers(optionalArray(root, "pendingLastWords")),
                    integer(root, "lastWordsIndex"),
                    optionalInteger(root, "pendingHunter"),
                    optionalInteger(root, "pendingShotVictim"),
                    optionalCamp(root, "winner"),
                    optionalString(root, "finishReason"),
                    decodeWolfChatCounts(optionalArray(root, "wolfChatCounts")),
                    // 2026-10-13 前的快照没有夜聊：stateVersion 取 version，即不放宽版本校验
                    root.get("stateVersion") == null ? version : integer(root, "stateVersion"));
        } catch (SnapshotFormatException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new SnapshotFormatException("快照状态文本无法解析为 GameSnapshot：" + error.getMessage(), error);
        }
    }

    /**
     * 快照校验和：对状态文本的 UTF-8 字节做 CRC32，取十六进制小写串。
     *
     * <p>校验对象是"序列化后的文本"而不是对象本身：恢复路径拿到的是文本，
     * 只有对同一份字节计算，才能发现存储层损坏（截断、脏写）。
     */
    public static String checksum(String state) {
        if (state == null) {
            throw new IllegalArgumentException("state 不能为空");
        }
        CRC32 crc = new CRC32();
        crc.update(state.getBytes(StandardCharsets.UTF_8));
        return Long.toHexString(crc.getValue());
    }

    /** 本局规则参数的下发形式；键名与 api-spec.md §5.2 的 {@code rules} 及 domain-model.md §3 一致。 */
    public static Map<String, Object> encodeRules(GameRules rules) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("speechMaxLength", rules.speechMaxLength());
        map.put("turnDeadlineMs", rules.turnDeadlineMs());
        map.put("agentTurnDeadlineMs", rules.agentTurnDeadlineMs());
        map.put("voteDeadlineMs", rules.voteDeadlineMs());
        map.put("firstNightLastWords", rules.firstNightLastWords());
        map.put("laterNightLastWords", rules.laterNightLastWords());
        map.put("exileLastWords", rules.exileLastWords());
        map.put("dayShotLastWords", rules.dayShotLastWords());
        map.put("witchSelfSave", rules.witchSelfSave());
        map.put("witchSelfSaveFirstNightOnly", rules.witchSelfSaveFirstNightOnly());
        map.put("witchSaveTargetMustBeKilled", rules.witchSaveTargetMustBeKilled());
        map.put("allowWolfEmptyKill", rules.allowWolfEmptyKill());
        map.put("wolfKillTieBreak", rules.wolfKillTieBreak().name());
        map.put("voteTieRule", rules.voteTieRule().name());
        map.put("hunterCanShootWhenPoisoned", rules.hunterCanShootWhenPoisoned());
        map.put("winCondition", rules.winCondition().name());
        map.put("abstainAllowed", rules.abstainAllowed());
        map.put("stalledRoundsLimit", rules.stalledRoundsLimit());
        return map;
    }

    private static Map<String, Object> encodeComposition(RoleComposition composition) {
        Map<String, Object> map = new LinkedHashMap<>();
        composition.counts().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> map.put(entry.getKey().name(), entry.getValue()));
        return map;
    }

    private static Map<String, Object> encodePlayer(PlayerSnapshot player) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("playerId", player.playerId().value());
        map.put("seatNo", player.seatNo().value());
        map.put("role", player.role() == null ? null : player.role().name());
        map.put("camp", player.camp() == null ? null : player.camp().name());
        map.put("alive", player.alive());
        map.put("ready", player.ready());
        map.put("controllerType", player.controller().type().name());
        map.put("controllerRefId", player.controller().refId());
        map.put("skillState", encodeSkillState(player.skillState()));
        return map;
    }

    private static Map<String, Object> encodeSkillState(SkillState skillState) {
        if (skillState == null) {
            return null;
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("antidoteAvailable", skillState.antidoteAvailable());
        map.put("poisonAvailable", skillState.poisonAvailable());
        map.put("shotAvailable", skillState.shotAvailable());
        return map;
    }

    private static Map<String, Object> encodeTurn(Turn turn) {
        if (turn == null) {
            return null;
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("playerId", turn.playerId().value());
        map.put("seatNo", turn.seatNo().value());
        map.put("phase", turn.phase().name());
        map.put("turnIndex", turn.turnIndex());
        map.put("deadline", turn.deadline());
        return map;
    }

    private static Map<String, Object> encodeNomination(WolfKillSelectedEvent.Nomination nomination) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("wolfSeatNo", nomination.wolfSeatNo());
        map.put("targetSeatNo", nomination.targetSeatNo());
        return map;
    }

    private static List<Map<String, Object>> encodeVotes(Map<Integer, Vote> votes) {
        return new TreeMap<>(votes).entrySet().stream().map(entry -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("voterSeat", entry.getKey());
            map.put("targetSeat", entry.getValue().targetSeat());
            map.put("abstain", entry.getValue().abstain());
            return map;
        }).toList();
    }

    private static List<Map<String, Object>> encodeWolfChatCounts(Map<Integer, Integer> counts) {
        return new TreeMap<>(counts).entrySet().stream().map(entry -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("wolfSeatNo", entry.getKey());
            map.put("count", entry.getValue());
            return map;
        }).toList();
    }

    private static Map<Integer, Integer> decodeWolfChatCounts(List<Object> list) {
        Map<Integer, Integer> counts = new TreeMap<>();
        for (Object item : list) {
            Map<String, Object> map = asObject(item, "wolfChatCounts[]");
            counts.put(
                    asInt(map.get("wolfSeatNo"), "wolfChatCounts[].wolfSeatNo"),
                    asInt(map.get("count"), "wolfChatCounts[].count"));
        }
        return counts;
    }

    private static Map<String, Object> encodeDeath(DeathRecord death) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("playerId", death.playerId().value());
        map.put("seatNo", death.seatNo().value());
        map.put("causes", death.causes().stream().map(Enum::name).sorted().toList());
        map.put("primaryCause", death.primaryCause().name());
        map.put("round", death.round());
        map.put("announced", death.announced());
        return map;
    }

    private static RoleComposition decodeComposition(Map<String, Object> map) {
        Map<Role, Integer> counts = new EnumMap<>(Role.class);
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            counts.put(Role.valueOf(entry.getKey()), asInt(entry.getValue(), "roleComposition." + entry.getKey()));
        }
        return RoleComposition.of(counts);
    }

    private static GameRules decodeRules(Map<String, Object> map) {
        return new GameRules(
                asInt(map.get("speechMaxLength"), "rules.speechMaxLength"),
                asLong(map.get("turnDeadlineMs"), "rules.turnDeadlineMs"),
                asLong(map.get("agentTurnDeadlineMs"), "rules.agentTurnDeadlineMs"),
                asLong(map.get("voteDeadlineMs"), "rules.voteDeadlineMs"),
                asBool(map.get("firstNightLastWords"), "rules.firstNightLastWords"),
                asBool(map.get("laterNightLastWords"), "rules.laterNightLastWords"),
                asBool(map.get("exileLastWords"), "rules.exileLastWords"),
                // 2026-10-12 前的快照没有该键，按当时的行为解码
                map.get("dayShotLastWords") != null && asBool(map.get("dayShotLastWords"), "rules.dayShotLastWords"),
                asBool(map.get("witchSelfSave"), "rules.witchSelfSave"),
                // 2026-10-13 前的快照没有该键：当时 witchSelfSave=true 即全程可自救
                map.get("witchSelfSaveFirstNightOnly") != null
                        && asBool(map.get("witchSelfSaveFirstNightOnly"), "rules.witchSelfSaveFirstNightOnly"),
                asBool(map.get("witchSaveTargetMustBeKilled"), "rules.witchSaveTargetMustBeKilled"),
                asBool(map.get("allowWolfEmptyKill"), "rules.allowWolfEmptyKill"),
                WolfKillTieBreak.valueOf(asString(map.get("wolfKillTieBreak"), "rules.wolfKillTieBreak")),
                VoteTieRule.valueOf(asString(map.get("voteTieRule"), "rules.voteTieRule")),
                asBool(map.get("hunterCanShootWhenPoisoned"), "rules.hunterCanShootWhenPoisoned"),
                WinCondition.valueOf(asString(map.get("winCondition"), "rules.winCondition")),
                asBool(map.get("abstainAllowed"), "rules.abstainAllowed"),
                map.get("stalledRoundsLimit") == null ? 0 : asInt(map.get("stalledRoundsLimit"), "rules.stalledRoundsLimit"));
    }

    private static List<PlayerSnapshot> decodePlayers(List<Object> list) {
        List<PlayerSnapshot> players = new ArrayList<>(list.size());
        for (Object item : list) {
            Map<String, Object> map = asObject(item, "players[]");
            players.add(new PlayerSnapshot(
                    PlayerId.of(asString(map.get("playerId"), "players[].playerId")),
                    SeatNo.of(asInt(map.get("seatNo"), "players[].seatNo")),
                    map.get("role") == null ? null : Role.valueOf(asString(map.get("role"), "players[].role")),
                    map.get("camp") == null ? null : Camp.valueOf(asString(map.get("camp"), "players[].camp")),
                    asBool(map.get("alive"), "players[].alive"),
                    new PlayerController(
                            ControllerType.valueOf(asString(map.get("controllerType"), "players[].controllerType")),
                            asString(map.get("controllerRefId"), "players[].controllerRefId")),
                    asBool(map.get("ready"), "players[].ready"),
                    decodeSkillState(map.get("skillState"))));
        }
        return players;
    }

    private static SkillState decodeSkillState(Object value) {
        if (value == null) {
            return null;
        }
        Map<String, Object> map = asObject(value, "skillState");
        return new SkillState(
                asBool(map.get("antidoteAvailable"), "skillState.antidoteAvailable"),
                asBool(map.get("poisonAvailable"), "skillState.poisonAvailable"),
                asBool(map.get("shotAvailable"), "skillState.shotAvailable"));
    }

    private static Turn decodeTurn(Map<String, Object> map) {
        if (map == null) {
            return null;
        }
        return new Turn(
                PlayerId.of(asString(map.get("playerId"), "currentTurn.playerId")),
                SeatNo.of(asInt(map.get("seatNo"), "currentTurn.seatNo")),
                GamePhase.valueOf(asString(map.get("phase"), "currentTurn.phase")),
                asInt(map.get("turnIndex"), "currentTurn.turnIndex"),
                asLong(map.get("deadline"), "currentTurn.deadline"));
    }

    private static List<WolfKillSelectedEvent.Nomination> decodeNominations(List<Object> list) {
        List<WolfKillSelectedEvent.Nomination> nominations = new ArrayList<>(list.size());
        for (Object item : list) {
            Map<String, Object> map = asObject(item, "wolfNominations[]");
            nominations.add(new WolfKillSelectedEvent.Nomination(
                    asInt(map.get("wolfSeatNo"), "wolfNominations[].wolfSeatNo"),
                    optionalIntegerValue(map.get("targetSeatNo"))));
        }
        return nominations;
    }

    private static Map<Integer, Vote> decodeVotes(List<Object> list) {
        if (list.isEmpty()) {
            return Map.of();
        }
        Map<Integer, Vote> votes = new TreeMap<>();
        for (Object item : list) {
            Map<String, Object> map = asObject(item, "votes[]");
            int voterSeat = asInt(map.get("voterSeat"), "votes[].voterSeat");
            votes.put(voterSeat, new Vote(
                    voterSeat,
                    optionalIntegerValue(map.get("targetSeat")),
                    asBool(map.get("abstain"), "votes[].abstain")));
        }
        return votes;
    }

    private static List<DeathRecord> decodeDeaths(List<Object> list) {
        List<DeathRecord> deaths = new ArrayList<>(list.size());
        for (Object item : list) {
            Map<String, Object> map = asObject(item, "deaths[]");
            Set<DeathCause> causes = EnumSet.noneOf(DeathCause.class);
            for (Object cause : asList(map.get("causes"), "deaths[].causes")) {
                causes.add(DeathCause.valueOf(asString(cause, "deaths[].causes[]")));
            }
            deaths.add(new DeathRecord(
                    PlayerId.of(asString(map.get("playerId"), "deaths[].playerId")),
                    SeatNo.of(asInt(map.get("seatNo"), "deaths[].seatNo")),
                    causes,
                    DeathCause.valueOf(asString(map.get("primaryCause"), "deaths[].primaryCause")),
                    asInt(map.get("round"), "deaths[].round"),
                    asBool(map.get("announced"), "deaths[].announced")));
        }
        return deaths;
    }

    // ---- 读取辅助：每个字段都显式检查，缺字段与类型不符必须失败而不是兜默认值 ----

    private static String string(Map<String, Object> root, String field) {
        return asString(root.get(field), field);
    }

    private static String optionalString(Map<String, Object> root, String field) {
        Object value = root.get(field);
        return value == null ? null : asString(value, field);
    }

    private static int integer(Map<String, Object> root, String field) {
        return asInt(root.get(field), field);
    }

    private static boolean bool(Map<String, Object> root, String field) {
        return asBool(root.get(field), field);
    }

    private static Map<String, Object> object(Map<String, Object> root, String field) {
        return asObject(root.get(field), field);
    }

    private static Map<String, Object> optionalObject(Map<String, Object> root, String field) {
        Object value = root.get(field);
        return value == null ? null : asObject(value, field);
    }

    private static List<Object> array(Map<String, Object> root, String field) {
        return asList(root.get(field), field);
    }

    private static List<Object> optionalArray(Map<String, Object> root, String field) {
        Object value = root.get(field);
        return value == null ? List.of() : asList(value, field);
    }

    private static List<Integer> integers(List<Object> list) {
        List<Integer> result = new ArrayList<>(list.size());
        for (Object item : list) {
            result.add(asInt(item, "座位号数组元素"));
        }
        return result;
    }

    private static Integer optionalInteger(Map<String, Object> root, String field) {
        return optionalIntegerValue(root.get(field));
    }

    private static Integer optionalIntegerValue(Object value) {
        return value == null ? null : asInt(value, "可选整数字段");
    }

    private static Camp optionalCamp(Map<String, Object> root, String field) {
        Object value = root.get(field);
        return value == null ? null : Camp.valueOf(asString(value, field));
    }

    private static GamePhase optionalPhase(Map<String, Object> root, String field) {
        Object value = root.get(field);
        return value == null ? null : GamePhase.valueOf(asString(value, field));
    }

    private static String asString(Object value, String field) {
        if (value instanceof String text) {
            return text;
        }
        throw new SnapshotFormatException("字段 " + field + " 期望字符串，实际为 " + Json.typeName(value));
    }

    private static int asInt(Object value, String field) {
        if (value instanceof Long number) {
            return Math.toIntExact(number);
        }
        throw new SnapshotFormatException("字段 " + field + " 期望整数，实际为 " + Json.typeName(value));
    }

    private static long asLong(Object value, String field) {
        if (value instanceof Long number) {
            return number;
        }
        throw new SnapshotFormatException("字段 " + field + " 期望整数，实际为 " + Json.typeName(value));
    }

    private static boolean asBool(Object value, String field) {
        if (value instanceof Boolean flag) {
            return flag;
        }
        throw new SnapshotFormatException("字段 " + field + " 期望布尔值，实际为 " + Json.typeName(value));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asObject(Object value, String field) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new SnapshotFormatException("字段 " + field + " 期望对象，实际为 " + Json.typeName(value));
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object value, String field) {
        if (value instanceof List<?> list) {
            return (List<Object>) list;
        }
        throw new SnapshotFormatException("字段 " + field + " 期望数组，实际为 " + Json.typeName(value));
    }

    /** 快照文本无法按契约解析。它与领域拒绝、与存储不可用都不是同一类问题。 */
    public static class SnapshotFormatException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public SnapshotFormatException(String message) {
            super(message);
        }

        public SnapshotFormatException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
