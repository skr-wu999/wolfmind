package com.wolfmind.application.game;

import com.wolfmind.domain.game.model.DomainErrorCode;
import com.wolfmind.domain.game.model.GameException;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 提交行动的用例输入（api-spec.md §5.3）。
 *
 * <p>{@code actorId} 是**已鉴权**的操作者身份（真人 {@code userId} / AI {@code agentId} /
 * BOT {@code strategyKey}），由接口层从会话或内部调用凭证取得；请求体里的任何玩家或座位字段都不是
 * 授权证据（AGENTS.md §8）。座位由应用层按 {@code controller.refId} 反查得到，因此本命令不含座位号。
 *
 * @param expectedVersion 客户端看到的状态版本，防"基于过期状态提交"（与幂等键是两回事）
 * @param idempotencyKey  客户端生成的请求唯一键，同时作为事件的 {@code causationId}
 */
public record SubmitActionCommand(
        String gameId,
        String actorId,
        String actionType,
        Map<String, Object> params,
        int expectedVersion,
        String idempotencyKey,
        String correlationId,
        com.wolfmind.application.scheduling.ExecutionFence fence) {

    public SubmitActionCommand(String gameId, String actorId, String actionType, Map<String, Object> params,
                               int expectedVersion, String idempotencyKey, String correlationId) {
        this(gameId, actorId, actionType, params, expectedVersion, idempotencyKey, correlationId, null);
    }

    public SubmitActionCommand {
        gameId = required(gameId, "gameId");
        actorId = required(actorId, "actorId");
        actionType = required(actionType, "actionType");
        idempotencyKey = required(idempotencyKey, "idempotencyKey");
        if (expectedVersion < 0) {
            throw validation("expectedVersion 不能为负");
        }
        // 用可变 Map 承载：JSON 里的显式 null 会被保留为"未提供"，而不是在构造期抛 NPE。
        params = params == null || params.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(params));
        correlationId = correlationId == null || correlationId.isBlank() ? idempotencyKey : correlationId;
    }

    /** 省略 traceId：由幂等键充当关联 id。 */
    public static SubmitActionCommand of(
            String gameId,
            String actorId,
            String actionType,
            Map<String, Object> params,
            int expectedVersion,
            String idempotencyKey) {
        return new SubmitActionCommand(
                gameId, actorId, actionType, params, expectedVersion, idempotencyKey, null);
    }

    /** 事件的 {@code causationId}：一次意图对应一个命令 id，重试时保持不变。 */
    public String commandId() {
        return idempotencyKey;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw validation(field + " 不能为空");
        }
        return value;
    }

    private static GameException validation(String message) {
        return new GameException(DomainErrorCode.VALIDATION_ERROR, message);
    }
}
