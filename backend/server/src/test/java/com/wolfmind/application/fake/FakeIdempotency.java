package com.wolfmind.application.fake;

import com.wolfmind.application.port.IdempotencyPort;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** 假幂等存储：按 {@code (gameId, key)} 记录首次结果。 */
public final class FakeIdempotency implements IdempotencyPort {

    private final Map<String, RecordedResult> recorded = new LinkedHashMap<>();

    @Override
    public Optional<RecordedResult> find(String gameId, String idempotencyKey) {
        return Optional.ofNullable(recorded.get(key(gameId, idempotencyKey)));
    }

    @Override
    public void record(String gameId, String idempotencyKey, RecordedResult result) {
        recorded.put(key(gameId, idempotencyKey), result);
    }

    /** 测试专用：直接放一条首次结果，模拟"这条意图已处理过"。 */
    public void preset(String gameId, String idempotencyKey, RecordedResult result) {
        recorded.put(key(gameId, idempotencyKey), result);
    }

    public boolean has(String gameId, String idempotencyKey) {
        return recorded.containsKey(key(gameId, idempotencyKey));
    }

    public RecordedResult get(String gameId, String idempotencyKey) {
        return recorded.get(key(gameId, idempotencyKey));
    }

    private static String key(String gameId, String idempotencyKey) {
        return gameId + "::" + idempotencyKey;
    }
}
