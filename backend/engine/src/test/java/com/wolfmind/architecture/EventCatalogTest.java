package com.wolfmind.architecture;

import com.wolfmind.domain.game.event.DeathAnnouncedEvent;
import com.wolfmind.domain.game.event.DomainEvent;
import com.wolfmind.domain.game.event.EventEnvelope;
import com.wolfmind.domain.game.event.EventType;
import com.wolfmind.domain.game.event.PlayerVotedEvent;
import com.wolfmind.domain.game.event.WolfKillNominatedEvent;
import com.wolfmind.domain.game.event.WolfKillSelectedEvent;
import com.wolfmind.domain.game.model.GameException;
import com.wolfmind.domain.game.model.TallyResult;
import com.wolfmind.domain.game.model.Visibility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 事件目录一致性（docs/architecture/event-design.md §7）。
 *
 * <p>§7 的事件表是 {@code type} 与 {@code visibility} 的**唯一契约**；§14 校验清单第 1 条要求
 * "每条 {@code type} 在 §7 目录中有定义，且 {@code visibility} 与目录一致"。本测试把该表写成代码期望，
 * 逐条对照 {@link EventType} 与各事件记录类。
 */
class EventCatalogTest {

    /**
     * 一条目录项：契约字符串、文档声明的可见性、文档 payload 列中的必需键、用于运行时校验的实例。
     *
     * <p>文档允许为 {@code null} 的载荷变体（空刀、弃刀提名、弃权票、平安夜）在
     * {@link #documentedNullablePayloadVariantsMustSerialize()} 中单独断言。
     */
    private record CatalogEntry(
            String code,
            Visibility visibility,
            List<String> requiredKeys,
            DomainEvent sample) {}

    /** event-design.md §7.1 Room 聚合事件（第三方或本模块尚未实现记录类，只校验目录一致）。 */
    private static final List<String> ROOM_TYPES = List.of(
            "ROOM_CREATED", "PLAYER_SEATED", "PLAYER_LEFT", "PLAYER_READY", "ROOM_STARTED",
            "PLAYER_CONTROLLER_CHANGED", "ROOM_CLOSED");

    /** event-design.md §7.3 User 聚合事件。 */
    private static final List<String> USER_TYPES = List.of(
            "USER_REGISTERED", "USER_LOGGED_IN", "USER_LOGGED_OUT");

    /** event-design.md §7.2 Game 聚合事件：type → (visibility, payload 必需键, 可空键, 实例)。 */
    private static List<CatalogEntry> gameCatalog() {
        List<CatalogEntry> entries = new ArrayList<>();
        entries.add(new CatalogEntry("GAME_STARTED", Visibility.PUBLIC,
                List.of("gameId", "roomId", "seats", "roleComposition", "rules", "turnOrder", "round"),
                EventFixtures.gameStarted()));
        entries.add(new CatalogEntry("ROLE_ASSIGNED", Visibility.PRIVATE,
                List.of("playerId", "seatNo", "role", "camp"),
                EventFixtures.roleAssigned()));
        entries.add(new CatalogEntry("PHASE_CHANGED", Visibility.PUBLIC,
                List.of("from", "to", "round", "turnSeq"),
                EventFixtures.phaseChanged()));
        entries.add(new CatalogEntry("TURN_GRANTED", Visibility.PUBLIC,
                List.of("playerId", "seatNo", "phase", "turnIndex", "deadline"),
                EventFixtures.turnGranted()));
        entries.add(new CatalogEntry("WOLF_KILL_NOMINATED", Visibility.WOLF,
                List.of("wolfId", "targetId", "targetSeatNo"),
                EventFixtures.wolfKillNominated()));
        entries.add(new CatalogEntry("WOLF_KILL_SELECTED", Visibility.WOLF,
                List.of("targetId", "targetSeatNo", "nominations"),
                EventFixtures.wolfKillSelected()));
        entries.add(new CatalogEntry("WOLF_CHAT_SENT", Visibility.WOLF,
                List.of("wolfId", "wolfSeatNo", "chatId", "content"),
                EventFixtures.wolfChatSent()));
        entries.add(new CatalogEntry("SEER_INSPECTED", Visibility.PRIVATE,
                List.of("seerId", "targetId", "result"),
                EventFixtures.seerInspected()));
        entries.add(new CatalogEntry("SEER_SKIPPED", Visibility.PRIVATE,
                List.of("seerId", "reason"),
                EventFixtures.seerSkipped()));
        entries.add(new CatalogEntry("WITCH_SAVED", Visibility.PRIVATE,
                List.of("witchId", "targetId"),
                EventFixtures.witchSaved()));
        entries.add(new CatalogEntry("WITCH_POISONED", Visibility.PRIVATE,
                List.of("witchId", "targetId"),
                EventFixtures.witchPoisoned()));
        entries.add(new CatalogEntry("WITCH_PASSED", Visibility.PRIVATE,
                List.of("witchId"),
                EventFixtures.witchPassed()));
        entries.add(new CatalogEntry("HUNTER_SHOT", Visibility.PUBLIC,
                List.of("hunterId", "targetId", "targetSeatNo"),
                EventFixtures.hunterShot()));
        entries.add(new CatalogEntry("HUNTER_SKIPPED", Visibility.PUBLIC,
                List.of("hunterId", "reason"),
                EventFixtures.hunterSkipped()));
        entries.add(new CatalogEntry("PLAYER_SPOKE", Visibility.PUBLIC,
                List.of("playerId", "seatNo", "speechId", "content", "turnIndex", "phase"),
                EventFixtures.playerSpoke()));
        entries.add(new CatalogEntry("SPEECH_SKIPPED", Visibility.PUBLIC,
                List.of("playerId", "seatNo", "reason"),
                EventFixtures.speechSkipped()));
        entries.add(new CatalogEntry("PLAYER_VOTED", Visibility.PUBLIC,
                List.of("playerId", "seatNo", "targetId", "targetSeatNo", "abstain"),
                EventFixtures.playerVoted()));
        entries.add(new CatalogEntry("VOTE_TALLY_RESOLVED", Visibility.PUBLIC,
                List.of("tally", "maxVotes", "result"),
                EventFixtures.voteTallyResolved()));
        entries.add(new CatalogEntry("PLAYER_KILLED", Visibility.JUDGE,
                List.of("playerId", "seatNo", "causes", "primaryCause", "round"),
                EventFixtures.playerKilled()));
        entries.add(new CatalogEntry("DEATH_ANNOUNCED", Visibility.PUBLIC,
                List.of("round", "deaths"),
                EventFixtures.deathAnnounced()));
        entries.add(new CatalogEntry("GAME_FINISHED", Visibility.PUBLIC,
                List.of("winner", "reason", "rounds", "survivors"),
                EventFixtures.gameFinished()));
        entries.add(new CatalogEntry("GAME_ABORTED", Visibility.PUBLIC,
                List.of("reason", "atVersion"),
                EventFixtures.gameAborted()));
        entries.add(new CatalogEntry("GAME_RECOVERED", Visibility.JUDGE,
                List.of("snapshotVersion", "replayedTo", "source"),
                EventFixtures.gameRecovered()));
        // event-design.md §7.1 把 PLAYER_CONTROLLER_CHANGED 列在 Room 聚合；对局内投影的键集合见 §7.1 payload 列，
        // 本测试只校验"类型存在 + 可见性一致"，payload 差异单独报告。
        entries.add(new CatalogEntry("PLAYER_CONTROLLER_CHANGED", Visibility.PUBLIC,
                List.of("seatNo", "fromType", "toType", "refId"),
                EventFixtures.playerControllerChanged()));
        return entries;
    }

    @Test
    @DisplayName("§7 目录中的每个 type 都在 EventType 中存在，且无重复")
    void everyDocumentedTypeExistsInEnum() {
        Set<String> documented = new LinkedHashSet<>();
        documented.addAll(ROOM_TYPES);
        documented.addAll(USER_TYPES);
        gameCatalog().forEach(entry -> documented.add(entry.code()));

        List<String> missing = documented.stream().filter(code -> !EventType.exists(code)).sorted().toList();
        assertTrue(missing.isEmpty(),
                "event-design.md §14.1：每条 type 必须在 §7 目录中有定义；EventType 缺少：" + missing);

        Set<String> codes = new LinkedHashSet<>();
        for (EventType type : EventType.values()) {
            assertTrue(codes.add(type.code()),
                    "type 是契约字符串，必须唯一；重复定义：" + type.code());
        }

        assertEquals(documented.size(), EventType.values().length,
                "EventType 的取值数应与 §7 目录去重后的条数一致（当前目录 " + documented.size()
                        + " 条）；多出的类型必须先在文档中登记，否则回放与可见性没有契约可依");
    }

    @Test
    @DisplayName("§7.2 每个 Game 事件的 type() 与 visibility() 与目录逐条一致")
    void gameEventTypeAndVisibilityMatchCatalog() {
        for (CatalogEntry entry : gameCatalog()) {
            DomainEvent event = entry.sample();
            assertNotNull(event, entry.code() + " 缺少测试夹具");
            assertEquals(entry.code(), event.type().code(),
                    "event-design.md §7.2：type 字符串必须与目录一致（事件类 " + event.getClass().getSimpleName() + "）");
            assertEquals(entry.visibility(), event.visibility(),
                    "event-design.md §7.2/§14.1：visibility 必须与目录一致（type=" + entry.code() + "）");
            assertEquals(event.type(), EventType.fromCode(entry.code()),
                    "EventType.fromCode 必须能解析目录中的契约字符串：" + entry.code());
        }
    }

    @Test
    @DisplayName("§7.1/§7.3 的 Room 与 User 事件可见性：目录中均为 PUBLIC（Room）与 JUDGE（User）")
    void roomAndUserTypesAreNotGameEvents() {
        for (String code : ROOM_TYPES) {
            assertTrue(EventType.exists(code), "EventType 缺少 Room 事件类型：" + code);
        }
        for (String code : USER_TYPES) {
            assertTrue(EventType.exists(code), "EventType 缺少 User 事件类型：" + code);
        }
    }

    @Test
    @DisplayName("§9 事件类命名：type 转 PascalCase + Event 后缀，且不存在未登记的事件类")
    void eventClassNamesFollowDocumentedMapping() {
        Path eventDir = TestPaths.engineEventSourceDir();
        List<Path> files = TestPaths.javaFiles(eventDir);
        assertTrue(files.size() >= 20,
                "必须扫到 domain.game.event 源码（当前 " + files.size() + " 个文件），否则命名规则未生效");

        Set<String> documentedCodes = new LinkedHashSet<>();
        gameCatalog().forEach(entry -> documentedCodes.add(entry.code()));

        List<String> problems = new ArrayList<>();
        for (Path file : files) {
            String fileName = file.getFileName().toString();
            if (!fileName.endsWith("Event.java") || fileName.equals("DomainEvent.java")) {
                continue;
            }
            String className = fileName.substring(0, fileName.length() - ".java".length());
            String expectedCode = toUpperSnake(className.substring(0, className.length() - "Event".length()));
            if (!documentedCodes.contains(expectedCode)) {
                problems.add(fileName + " -> " + expectedCode + "（§7.2 目录中没有该 type）");
            }
        }
        assertTrue(problems.isEmpty(),
                "event-design.md §7 是 type 的唯一定义处、domain-model.md §9 规定类名 = type 转 PascalCase + Event；"
                        + "以下事件类没有对应目录项：" + problems);

        for (CatalogEntry entry : gameCatalog()) {
            String expectedClass = "com.wolfmind.domain.game.event."
                    + toPascalCase(entry.code()) + "Event";
            assertDoesNotThrow(() -> Class.forName(expectedClass),
                    "domain-model.md §9：type " + entry.code() + " 对应的 Java 类应为 " + expectedClass);
        }
    }

    @Test
    @DisplayName("§7.2 payload 必需键齐全（含空刀 / 弃权 / 平安夜变体不抛异常）")
    void payloadKeysMatchCatalog() {
        for (CatalogEntry entry : gameCatalog()) {
            Map<String, Object> payload = assertDoesNotThrow(
                    () -> entry.sample().payload(),
                    "event-design.md §7.2：payload 是该事件专属数据的确定性序列化形式，"
                            + "不得因为文档允许的 null 字段（空刀 targetId、弃权 targetSeatNo）而抛异常；"
                            + "type=" + entry.code());
            for (String key : entry.requiredKeys()) {
                assertTrue(payload.containsKey(key),
                        "event-design.md §7.2：payload 缺少必需键 " + key + "（type=" + entry.code()
                                + "，实际键=" + payload.keySet() + "）");
            }
        }
    }

    @Test
    @DisplayName("§7.2 明确允许为 null 的载荷变体必须可序列化：空刀、弃刀提名、弃权票、平安夜")
    void documentedNullablePayloadVariantsMustSerialize() {
        List<org.junit.jupiter.api.function.Executable> checks = new ArrayList<>();

        // event-design.md §7.2：WOLF_KILL_SELECTED.targetId 为 null 表示空刀，仍然产生事件。
        WolfKillSelectedEvent emptyKill = EventFixtures.wolfKillSelectedEmpty();
        checks.add(() -> {
            Map<String, Object> payload = assertDoesNotThrow(emptyKill::payload,
                    "event-design.md §7.2：WOLF_KILL_SELECTED.targetId 为 null 表示空刀（存活狼人全部弃刀），"
                            + "仍然产生事件，因此 payload() 必须能序列化空刀；抛异常则空刀无法写入事件流");
            assertNull(payload.get("targetId"), "event-design.md §7.2：空刀的 targetId 应为 null");
        });

        // domain-model.md §7：狼人 SkipAction 构成"提名放弃"。
        WolfKillNominatedEvent emptyNomination = EventFixtures.wolfKillNominatedEmpty();
        checks.add(() -> {
            Map<String, Object> payload = assertDoesNotThrow(emptyNomination::payload,
                    "domain-model.md §7（SkipAction | WEREWOLF | NIGHT_WOLF 提名放弃）：弃刀提名事件必须可序列化");
            assertNull(payload.get("targetId"), "domain-model.md §7：弃刀提名的 targetId 应为 null");
        });

        // ddd-design.md §4.1 INV-10：弃权以 abstain = true 且 targetSeat = null 表达。
        PlayerVotedEvent abstain = EventFixtures.playerVotedAbstain();
        checks.add(() -> {
            Map<String, Object> payload = assertDoesNotThrow(abstain::payload,
                    "ddd-design.md §4.1 INV-10：弃权票以 abstain=true 且 targetSeat=null 表达，"
                            + "payload() 必须能序列化弃权票");
            assertNull(payload.get("targetId"), "ddd-design.md §4.1 INV-10：弃权票的 targetId 应为 null");
            assertEquals(Boolean.TRUE, payload.get("abstain"),
                    "ddd-design.md §4.1 INV-10：弃权票的 abstain 必须为 true");
        });

        // event-design.md §7.2：DEATH_ANNOUNCED 的 deaths 为空数组时表示平安夜，仍然产生事件。
        DeathAnnouncedEvent peaceful = EventFixtures.deathAnnouncedPeacefulNight();
        checks.add(() -> {
            Map<String, Object> payload = assertDoesNotThrow(peaceful::payload,
                    "event-design.md §7.2：平安夜（deaths 为空数组）仍然产生 DEATH_ANNOUNCED，payload() 必须可序列化");
            assertEquals(List.of(), payload.get("deaths"),
                    "event-design.md §7.2：平安夜的 deaths 必须为空数组，而不是缺少该键");
        });

        assertAll(checks);
    }

    @Test
    @DisplayName("VOTE_TALLY_RESOLVED.result 取值恰为 EXILE / TIE / ALL_ABSTAIN")
    void tallyResultValuesMatchDocumentedContract() {
        Set<String> names = new LinkedHashSet<>();
        for (TallyResult.Result result : TallyResult.Result.values()) {
            names.add(result.name());
        }
        assertEquals(Set.of("EXILE", "TIE", "ALL_ABSTAIN"), names,
                "event-design.md §7.2：VOTE_TALLY_RESOLVED.result 取值只有 EXILE / TIE / ALL_ABSTAIN 三种");
    }

    @Test
    @DisplayName("§4 可见性取值恰为 PUBLIC / WOLF / PRIVATE / JUDGE")
    void visibilityValuesMatchDocumentedContract() {
        Set<String> names = new LinkedHashSet<>();
        for (Visibility visibility : Visibility.values()) {
            names.add(visibility.name());
        }
        assertEquals(Set.of("PUBLIC", "WOLF", "PRIVATE", "JUDGE"), names,
                "event-design.md §4：visibility 只有 PUBLIC / WOLF / PRIVATE / JUDGE 四种");
    }

    @Test
    @DisplayName("未知事件类型必须显式失败，不做默认值兜底（§2 完整性规则）")
    void unknownEventTypeMustFailExplicitly() {
        GameException exception = assertThrows(GameException.class,
                () -> EventType.fromCode("NO_SUCH_EVENT"),
                "event-design.md §2：信封字段缺失/未知必须显式失败，不允许默认值兜底");
        assertTrue(exception.codeString().contains("REPLAY") || exception.codeString().contains("UNKNOWN"),
                "未知事件类型应映射为重放类错误码，实际为 " + exception.codeString());
    }

    @Test
    @DisplayName("§2 信封完整性：EventEnvelope 必填字段与事件一致且在写入前被校验")
    void envelopeCarriesAllRequiredFields() {
        Map<String, DomainEvent> samples = new LinkedHashMap<>();
        for (CatalogEntry entry : gameCatalog()) {
            samples.put(entry.code(), entry.sample());
        }

        for (Map.Entry<String, DomainEvent> entry : samples.entrySet()) {
            DomainEvent event = entry.getValue();
            EventEnvelope envelope = EventEnvelope.of(event, EventFixtures.GAME_ID, event.version());
            assertEquals(event.eventId(), envelope.eventId(), "§2：eventId 来自事件");
            assertEquals(EventFixtures.GAME_ID, envelope.gameId(), "§2：gameId 是流键");
            assertEquals(event.version(), envelope.version(), "§2：version 是流内版本，必须来自聚合");
            assertEquals(entry.getKey(), envelope.type(), "§2：type 是契约字符串");
            assertEquals(event.visibility(), envelope.visibility(), "§2：visibility 是事件属性");
            assertEquals(event.occurredAt(), envelope.occurredAt(), "§2：occurredAt 由 ActionContext 注入");
            assertNotNull(envelope.causationId(), "§2：causationId 必填");
            assertNotNull(envelope.correlationId(), "§2：correlationId 必填");
            assertTrue(envelope.payload() != null, "§2：payload 必填，不允许为空");
        }
    }

    private static String toUpperSnake(String camel) {
        return camel.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toUpperCase(java.util.Locale.ROOT);
    }

    private static String toPascalCase(String upperSnake) {
        StringBuilder builder = new StringBuilder();
        for (String part : upperSnake.split("_")) {
            builder.append(Character.toUpperCase(part.charAt(0)))
                    .append(part.substring(1).toLowerCase(java.util.Locale.ROOT));
        }
        return builder.toString();
    }
}
