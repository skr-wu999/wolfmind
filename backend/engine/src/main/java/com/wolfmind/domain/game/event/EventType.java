package com.wolfmind.domain.game.event;

import com.wolfmind.domain.game.model.GameException;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 事件类型目录（event-design.md §7）。
 *
 * <p>这是领域事件 {@code type} 字符串的唯一来源。前缀约定：Game 聚合用业务名，
 * Agent 决策事件统一 {@code AGENT_} 前缀且**不允许**出现在 {@code game:{id}:events} 中
 * （event-design.md §1、§7.4）。
 */
public enum EventType {

    // ---- Game 聚合（game:{id}:events） ----
    GAME_STARTED("GAME_STARTED"),
    ROLE_ASSIGNED("ROLE_ASSIGNED"),
    PHASE_CHANGED("PHASE_CHANGED"),
    TURN_GRANTED("TURN_GRANTED"),
    PLAYER_SPOKE("PLAYER_SPOKE"),
    SPEECH_SKIPPED("SPEECH_SKIPPED"),
    WOLF_KILL_NOMINATED("WOLF_KILL_NOMINATED"),
    WOLF_KILL_SELECTED("WOLF_KILL_SELECTED"),
    WOLF_CHAT_SENT("WOLF_CHAT_SENT"),
    SEER_INSPECTED("SEER_INSPECTED"),
    SEER_SKIPPED("SEER_SKIPPED"),
    WITCH_SAVED("WITCH_SAVED"),
    WITCH_POISONED("WITCH_POISONED"),
    WITCH_PASSED("WITCH_PASSED"),
    HUNTER_SHOT("HUNTER_SHOT"),
    HUNTER_SKIPPED("HUNTER_SKIPPED"),
    PLAYER_VOTED("PLAYER_VOTED"),
    VOTE_TALLY_RESOLVED("VOTE_TALLY_RESOLVED"),
    PLAYER_KILLED("PLAYER_KILLED"),
    DEATH_ANNOUNCED("DEATH_ANNOUNCED"),
    GAME_FINISHED("GAME_FINISHED"),
    GAME_ABORTED("GAME_ABORTED"),
    GAME_RECOVERED("GAME_RECOVERED"),
    PLAYER_CONTROLLER_CHANGED("PLAYER_CONTROLLER_CHANGED"),

    // ---- Room 聚合（room:{id}:events） ----
    ROOM_CREATED("ROOM_CREATED"),
    PLAYER_SEATED("PLAYER_SEATED"),
    PLAYER_LEFT("PLAYER_LEFT"),
    PLAYER_READY("PLAYER_READY"),
    ROOM_STARTED("ROOM_STARTED"),
    ROOM_CLOSED("ROOM_CLOSED"),

    // ---- User 聚合（user:{id}:events） ----
    USER_REGISTERED("USER_REGISTERED"),
    USER_LOGGED_IN("USER_LOGGED_IN"),
    USER_LOGGED_OUT("USER_LOGGED_OUT");

    private static final Map<String, EventType> BY_CODE = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(EventType::code, Function.identity()));

    /** 不属于 Game 聚合的类型：Room 与 User 各自的事件流。 */
    private static final java.util.Set<EventType> NON_GAME = java.util.EnumSet.of(
            ROOM_CREATED, PLAYER_SEATED, PLAYER_LEFT, PLAYER_READY, ROOM_STARTED, ROOM_CLOSED,
            USER_REGISTERED, USER_LOGGED_IN, USER_LOGGED_OUT);

    private final String code;

    EventType(String code) {
        this.code = code;
    }

    /** 线上契约字符串。 */
    public String code() {
        return code;
    }

    /** 该类型是否属于 Game 聚合的事件流（{@code game:{id}:events}）。 */
    public boolean isGameEvent() {
        return !NON_GAME.contains(this);
    }

    /** 按契约字符串解析；未知类型必须显式失败，不做"默认值兜底"（event-design.md §2）。 */
    public static EventType fromCode(String code) {
        EventType type = BY_CODE.get(code);
        if (type == null) {
            throw GameException.Factory.unknownEventType(code);
        }
        return type;
    }

    public static boolean exists(String code) {
        return BY_CODE.containsKey(code);
    }

    @Override
    public String toString() {
        return code;
    }
}
