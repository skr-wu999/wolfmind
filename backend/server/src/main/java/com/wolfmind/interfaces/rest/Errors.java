package com.wolfmind.interfaces.rest;

import com.wolfmind.domain.game.model.DomainErrorCode;
import com.wolfmind.domain.game.model.GameException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 错误响应的唯一构造点（api-spec.md 1.2）。
 *
 * <p>两张映射表集中在这里，不允许各控制器各自判断 HTTP 状态：同一份契约有两处实现，
 * 迟早会出现"同一个码在 A 接口是 403、在 B 接口是 400"。
 *
 * <p><b>未登记的码抛 IllegalStateException 而不是兜底成 400/500。</b>
 * 新加一个领域错误码却忘了登记，是缺陷而不是"未知情况"；让它以 500 的形式在测试里立刻显形，
 * 比静默把一次真实拒绝伪装成成功或客户端错误要好。
 */
final class Errors {

    private Errors() {}

    /** 接口层专属码（领域不产生的那些）。 */
    enum Code {
        AUTH_REQUIRED,
        AUTH_EXPIRED,
        FORBIDDEN,
        NOT_FOUND,
        RATE_LIMITED,
        INTERNAL_ERROR,
        NOT_IMPLEMENTED
    }

    /**
     * 错误响应体。
     *
     * <p>前四个字段是 api-spec.md 1.1 的最小集；后三个是服务端附加的诊断信息，
     * 客户端可以忽略，但日志与告警需要。
     */
    record ApiError(
            String code,
            String message,
            List<Map<String, Object>> details,
            String traceId,
            int status,
            String category,
            boolean retryable) {}

    private static final Map<DomainErrorCode, Integer> STATUS_BY_DOMAIN_CODE = Map.ofEntries(
            Map.entry(DomainErrorCode.VALIDATION_ERROR, 400),
            Map.entry(DomainErrorCode.ACTION_INVALID_TARGET, 400),
            Map.entry(DomainErrorCode.ROLE_COMPOSITION_INVALID, 400),
            Map.entry(DomainErrorCode.RULES_INVALID, 400),
            Map.entry(DomainErrorCode.SPEECH_INVALID, 400),
            Map.entry(DomainErrorCode.ACTION_NOT_ALLOWED, 403),
            Map.entry(DomainErrorCode.SEAT_NOT_YOURS, 403),
            Map.entry(DomainErrorCode.SKILL_UNAVAILABLE, 403),
            Map.entry(DomainErrorCode.GAME_NOT_RUNNING, 404),
            // 认证失败：凭据缺失或无效。它必须放在**领域码**表里而不是只靠接口层的
            // Code.AUTH_REQUIRED——"登录失败"发生在应用层，抛的是领域异常。
            Map.entry(DomainErrorCode.AUTH_REQUIRED, 401),
            Map.entry(DomainErrorCode.GAME_VERSION_CONFLICT, 409),
            Map.entry(DomainErrorCode.TURN_NOT_YOURS, 409),
            Map.entry(DomainErrorCode.GAME_BUSY, 409),
            Map.entry(DomainErrorCode.ACTION_ALREADY_SUBMITTED, 409),
            Map.entry(DomainErrorCode.GAME_ALREADY_FINISHED, 409),
            Map.entry(DomainErrorCode.GAME_ALREADY_STARTED, 409),
            Map.entry(DomainErrorCode.SNAPSHOT_CHECKSUM_MISMATCH, 500),
            Map.entry(DomainErrorCode.REPLAY_OUT_OF_ORDER, 500),
            Map.entry(DomainErrorCode.REPLAY_UNKNOWN_EVENT_TYPE, 500),
            Map.entry(DomainErrorCode.INTERNAL_ERROR, 500),
            Map.entry(DomainErrorCode.ROOM_NOT_FOUND, 404),
            Map.entry(DomainErrorCode.USER_NOT_FOUND, 404),
            Map.entry(DomainErrorCode.ROOM_FULL, 409),
            Map.entry(DomainErrorCode.ALREADY_SEATED, 409),
            Map.entry(DomainErrorCode.NOT_READY, 409),
            Map.entry(DomainErrorCode.USERNAME_TAKEN, 409),
            Map.entry(DomainErrorCode.NOT_SEATED, 403),
            Map.entry(DomainErrorCode.ROOM_CLOSED, 409),
            Map.entry(DomainErrorCode.GAME_RECOVERING, 503),
            Map.entry(DomainErrorCode.SERVICE_DEGRADED, 503));

    private static final Map<Code, Integer> STATUS_BY_CODE = Map.of(
            Code.AUTH_REQUIRED, 401,
            Code.AUTH_EXPIRED, 401,
            Code.FORBIDDEN, 403,
            Code.NOT_FOUND, 404,
            Code.RATE_LIMITED, 429,
            Code.INTERNAL_ERROR, 500,
            Code.NOT_IMPLEMENTED, 501);

    /** api-spec.md 1.2：可重试的码，客户端据 retryable 决定退避还是放弃。 */
    private static final Map<Code, Boolean> RETRYABLE_BY_CODE = Map.of(
            Code.RATE_LIMITED, true);

    private static final Map<DomainErrorCode, Boolean> RETRYABLE_BY_DOMAIN_CODE = Map.of(
            DomainErrorCode.GAME_BUSY, true,
            DomainErrorCode.GAME_VERSION_CONFLICT, true,
            DomainErrorCode.GAME_RECOVERING, true,
            DomainErrorCode.SERVICE_DEGRADED, true);

    /** 领域拒绝码 -> HTTP 状态。 */
    static int statusOf(DomainErrorCode code) {
        Integer status = STATUS_BY_DOMAIN_CODE.get(code);
        if (status == null) {
            throw new IllegalStateException("领域错误码 " + code + " 未在 api-spec.md 1.2 映射表中登记");
        }
        return status;
    }

    /** 接口层专属码 -> HTTP 状态。 */
    static int statusOf(Code code) {
        Integer status = STATUS_BY_CODE.get(code);
        if (status == null) {
            throw new IllegalStateException("接口层错误码 " + code + " 未登记 HTTP 状态");
        }
        return status;
    }

    /**
     * 由领域拒绝构造响应。
     *
     * <p>details 直接来自异常的明细列表，不重新编造：领域已经给出了"哪个字段、为什么"，
     * 接口层再翻译一次只会引入不一致。
     */
    static ApiError of(GameException error, String traceId) {
        List<Map<String, Object>> details = new ArrayList<>();
        for (GameException.Detail detail : error.details()) {
            details.add(Map.of("field", detail.field(), "reason", detail.reason()));
        }
        return new ApiError(
                error.code().name(),
                error.getMessage(),
                details,
                traceId,
                statusOf(error.code()),
                categoryOf(error.code()),
                RETRYABLE_BY_DOMAIN_CODE.getOrDefault(error.code(), false));
    }

    static ApiError of(Code code, String message, String traceId, List<Map<String, Object>> details) {
        return new ApiError(
                code.name(),
                message,
                List.copyOf(details),
                traceId,
                statusOf(code),
                categoryOf(code),
                RETRYABLE_BY_CODE.getOrDefault(code, false));
    }

    /** 请求形态错误：复用领域码名，语义与领域同名（api-spec.md 1.2）。 */
    static ApiError badRequest(String message, String traceId, List<Map<String, Object>> details) {
        return new ApiError(
                DomainErrorCode.VALIDATION_ERROR.name(),
                message,
                List.copyOf(details),
                traceId,
                400,
                "REQUEST",
                false);
    }

    /** 未预期异常的响应：不泄漏堆栈与内部类型名。 */
    static ApiError internal(String traceId) {
        return new ApiError(
                Code.INTERNAL_ERROR.name(),
                "服务端内部错误，请携带 traceId 上报",
                List.of(),
                traceId,
                500,
                "SERVER",
                false);
    }

    private static String categoryOf(DomainErrorCode code) {
        return switch (code) {
            case VALIDATION_ERROR, ACTION_INVALID_TARGET, ROLE_COMPOSITION_INVALID,
                 RULES_INVALID, SPEECH_INVALID -> "REQUEST";
            case ACTION_NOT_ALLOWED, SEAT_NOT_YOURS, SKILL_UNAVAILABLE, NOT_SEATED -> "AUTHORIZATION";
            case AUTH_REQUIRED -> "AUTHENTICATION";
            case GAME_NOT_RUNNING, ROOM_NOT_FOUND, USER_NOT_FOUND -> "NOT_FOUND";
            case GAME_VERSION_CONFLICT, TURN_NOT_YOURS, GAME_BUSY, ACTION_ALREADY_SUBMITTED,
                 GAME_ALREADY_FINISHED, GAME_ALREADY_STARTED,
                 ROOM_FULL, ALREADY_SEATED, NOT_READY, ROOM_CLOSED, USERNAME_TAKEN -> "CONFLICT";
            case GAME_RECOVERING, SERVICE_DEGRADED -> "AVAILABILITY";
            default -> "SERVER";
        };
    }

    private static String categoryOf(Code code) {
        return switch (code) {
            case AUTH_REQUIRED, AUTH_EXPIRED -> "AUTHENTICATION";
            case FORBIDDEN -> "AUTHORIZATION";
            case NOT_FOUND -> "NOT_FOUND";
            case RATE_LIMITED -> "AVAILABILITY";
            case NOT_IMPLEMENTED -> "VERSION_GAP";
            case INTERNAL_ERROR -> "SERVER";
        };
    }
}