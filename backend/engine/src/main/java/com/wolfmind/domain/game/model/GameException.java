package com.wolfmind.domain.game.model;

import java.util.List;
import java.util.Map;

/**
 * 领域拒绝。
 *
 * <p>被拒绝的命令不产生任何事件，因此 {@code Game#handle} 用抛出本异常表达拒绝，
 * 而不是返回一个"半成功"的结果对象。这与 domain-model.md §2.2 的设计说明一致：
 * 「任何一步失败都返回拒绝结果且不产生事件」。
 */
public final class GameException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final DomainErrorCode code;
    private final List<Detail> details;

    public GameException(DomainErrorCode code, String message) {
        this(code, message, List.of());
    }

    public GameException(DomainErrorCode code, String message, List<Detail> details) {
        super(message);
        this.code = code;
        this.details = List.copyOf(details);
    }

    public static GameException of(DomainErrorCode code, String message, String field, String reason) {
        return new GameException(code, message, List.of(new Detail(field, reason)));
    }

    public DomainErrorCode code() {
        return code;
    }

    /** 结构化细节，对应 api-spec.md §1.1 错误响应的 {@code details[]}。 */
    public List<Detail> details() {
        return details;
    }

    /** 供接口层与测试使用的稳定错误标识。 */
    public String codeString() {
        return code.name();
    }

    public record Detail(String field, String reason) {}

    public static final class Factory {

        private Factory() {}

        public static GameException validation(String message) {
            return new GameException(DomainErrorCode.VALIDATION_ERROR, message);
        }

        public static GameException notYourSeat(String message) {
            return new GameException(DomainErrorCode.SEAT_NOT_YOURS, message);
        }

        public static GameException notAllowed(String message) {
            return new GameException(DomainErrorCode.ACTION_NOT_ALLOWED, message);
        }

        public static GameException invalidTarget(String message) {
            return new GameException(DomainErrorCode.ACTION_INVALID_TARGET, message);
        }

        public static GameException turnNotYours(String message) {
            return new GameException(DomainErrorCode.TURN_NOT_YOURS, message);
        }

        public static GameException forbidden(String message) {
            return new GameException(DomainErrorCode.ACTION_NOT_ALLOWED, message);
        }

        public static GameException idempotent(String message) {
            return new GameException(DomainErrorCode.ACTION_ALREADY_SUBMITTED, message);
        }

        public static GameException versionConflict(int expected, int actual) {
            return new GameException(
                    DomainErrorCode.GAME_VERSION_CONFLICT,
                    "expectedVersion=" + expected + " 但当前版本为 " + actual,
                    List.of(new Detail("expectedVersion", "current=" + actual)));
        }

        public static GameException alreadyFinished() {
            return new GameException(DomainErrorCode.GAME_ALREADY_FINISHED, "对局已结束，不再接受行动");
        }

        public static GameException alreadyStarted() {
            return new GameException(DomainErrorCode.GAME_ALREADY_STARTED, "对局已经开局，角色不可重新分配");
        }

        /** 同一语义的带消息重载：房间与对局共用这个码，消息需要区分上下文。 */
        public static GameException alreadyStarted(String message) {
            return new GameException(DomainErrorCode.GAME_ALREADY_STARTED, message);
        }

        public static GameException notRunning(String status) {
            return new GameException(DomainErrorCode.GAME_NOT_RUNNING, "对局状态为 " + status + "，不接受行动");
        }

        // ---- 房间与用户用例的拒绝工厂（api-spec.md 1.2 的码，Phase 4 补齐） ----

        /**
         * 认证失败（401）。与 {@code forbidden} 的区别是语义而非严重性：
         * 前者表示"你没提供有效凭据"，后者表示"凭据有效但你没这个权限"。
         * 登录失败用前者——它同时避免泄露"这个用户名是否存在"。
         */
        public static GameException authRequired(String message) {
            return new GameException(DomainErrorCode.AUTH_REQUIRED, message);
        }
        public static GameException roomNotFound(String roomId) {
            return new GameException(DomainErrorCode.ROOM_NOT_FOUND, "房间不存在：" + roomId);
        }

        public static GameException roomFull() {
            return new GameException(DomainErrorCode.ROOM_FULL, "房间已满，没有空座");
        }

        public static GameException alreadySeated(String message) {
            return new GameException(DomainErrorCode.ALREADY_SEATED, message);
        }

        public static GameException notSeated(String message) {
            return new GameException(DomainErrorCode.NOT_SEATED, message);
        }

        public static GameException notReady(String message) {
            return new GameException(DomainErrorCode.NOT_READY, message);
        }

        public static GameException roomClosed(String message) {
            return new GameException(DomainErrorCode.ROOM_CLOSED, message);
        }

        public static GameException userNotFound(String userId) {
            return new GameException(DomainErrorCode.USER_NOT_FOUND, "用户不存在：" + userId);
        }

        public static GameException usernameTaken(String username) {
            return new GameException(DomainErrorCode.USERNAME_TAKEN, "用户名已被占用：" + username);
        }

        public static GameException replayOutOfOrder(int expected, int actual) {
            return new GameException(
                    DomainErrorCode.REPLAY_OUT_OF_ORDER,
                    "重放事件版本不连续：期望 " + expected + "，实际 " + actual);
        }

        public static GameException unknownEventType(String type) {
            return new GameException(DomainErrorCode.REPLAY_UNKNOWN_EVENT_TYPE, "未知事件类型：" + type);
        }

        public static GameException compositionInvalid(String message) {
            return new GameException(DomainErrorCode.ROLE_COMPOSITION_INVALID, message);
        }

        public static GameException rulesInvalid(String message) {
            return new GameException(DomainErrorCode.RULES_INVALID, message);
        }

        public static GameException skillUnavailable(String message) {
            return new GameException(DomainErrorCode.SKILL_UNAVAILABLE, message);
        }

        public static GameException speechInvalid(String message) {
            return new GameException(
                    DomainErrorCode.SPEECH_INVALID, message, List.of(new Detail("content", message)));
        }

        public static GameException busy() {
            return new GameException(DomainErrorCode.GAME_BUSY, "对局正在被其他写者处理，请退避重试");
        }

        public static GameException busy(GameId gameId) {
            return new GameException(
                    DomainErrorCode.GAME_BUSY,
                    "对局 " + gameId.value() + " 正在被其他写者处理，请退避重试");
        }

        public static GameException degraded(String message) {
            return new GameException(DomainErrorCode.SERVICE_DEGRADED, message);
        }

        public static GameException recovering(String message) {
            return new GameException(DomainErrorCode.GAME_RECOVERING, message);
        }

        /** 便于在 switch/stream 场景下构造带上下文的细节映射。 */
        public static GameException withDetails(
                DomainErrorCode code, String message, Map<String, String> details) {
            List<Detail> list = details.entrySet().stream()
                    .map(e -> new Detail(e.getKey(), e.getValue()))
                    .toList();
            return new GameException(code, message, list);
        }
    }
}
