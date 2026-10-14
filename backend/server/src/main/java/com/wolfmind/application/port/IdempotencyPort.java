package com.wolfmind.application.port;

import java.util.Optional;

/**
 * 幂等键端口（api-spec.md §1、event-design.md §10）。
 *
 * <p>命令层幂等：同一 {@code (gameId, idempotencyKey)} 重复提交返回首次结果，不产生新事件。
 * 它与领域内的"本阶段是否已提交"（V6）是两层防护：即使幂等键丢失，领域判定也不会重复计票。
 *
 * <p>幂等记录必须与事件写入同一批次生效（system-design.md §5），否则崩溃会留下
 * "事件已写入但幂等记录丢失"的窗口。
 */
public interface IdempotencyPort {

    /** 查询已记录的结果；未命中返回空。 */
    Optional<RecordedResult> find(String gameId, String idempotencyKey);

    /** 记录结果，与事件写入同批生效。 */
    void record(String gameId, String idempotencyKey, RecordedResult result);

    /** 首次执行的完整结果，重复提交时原样返回。 */
    record RecordedResult(
            String gameId,
            String idempotencyKey,
            int version,
            java.util.List<String> eventIds,
            java.util.List<String> eventTypes,
            String resultDigest) {

        public RecordedResult {
            eventIds = java.util.List.copyOf(eventIds);
            eventTypes = java.util.List.copyOf(eventTypes);
        }
    }
}
