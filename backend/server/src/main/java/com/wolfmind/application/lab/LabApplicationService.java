package com.wolfmind.application.lab;

import com.wolfmind.application.port.TimePort;
import com.wolfmind.domain.game.model.GameException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 实验室写用例（api-spec.md §8）：保存配置、创建批次。
 *
 * <p><b>这里只建"批次记录"，不创建对局。</b>对局的创建与推进由 {@link LabBatchRunner} 按并发上限
 * 逐步做——一个 50 局的批次如果在这里同步开 50 局，请求会阻塞几分钟，而客户端拿不到
 * {@code 202 { batchId }}。把"要跑什么"与"正在跑什么"分开，也让批次在进程重启后可以续跑：
 * 记录里已有 {@code total} 与已完成的对局，运行器接着开剩下的即可。
 *
 * <p><b>并发上限是服务端配置，不是请求参数。</b>{@code maxConcurrentGames} 取请求与配置的
 * 较小值，超过配置值直接 {@code 429 RATE_LIMITED}（api-spec.md §8 与 §12）。
 */
public final class LabApplicationService {

    /** 单批最多局数。比"不限"小，是因为比对胜负需要每局都真的跑完。 */
    public static final int MAX_GAMES_PER_BATCH = 50;

    private final LabStorePort store;
    private final TimePort time;
    private final int serverMaxConcurrentGames;
    private final AtomicLong sequence = new AtomicLong();

    public LabApplicationService(
            LabStorePort store, TimePort time, int serverMaxConcurrentGames) {
        this.store = Objects.requireNonNull(store, "store");
        this.time = Objects.requireNonNull(time, "time");
        this.serverMaxConcurrentGames = Math.max(1, serverMaxConcurrentGames);
    }

    /** 服务端并发上限（{@code lab.maxConcurrentGames}），在响应里回给客户端。 */
    public int serverMaxConcurrentGames() {
        return serverMaxConcurrentGames;
    }

    /**
     * 保存一份 Agent 配置。
     *
     * @param body    api-spec.md §8 的字段；未知字段被忽略而不是报错——
     *                客户端版本领先于服务端时，拒绝整个请求会让页面完全不可用
     * @param ownerId 当前账号
     */
    public LabAgentConfig saveConfig(Map<String, Object> body, String ownerId) {
        Map<String, String> errors = LabAgentConfig.validate(body);
        if (!errors.isEmpty()) {
            throw validation(errors);
        }
        long now = time.nowMillis();
        String name = text(body, "name");
        String existingId = text(body, "agentConfigId");
        LabAgentConfig existing = existingId == null ? null : store.config(existingId).orElse(null);
        if (existing != null && !Objects.equals(existing.ownerId(), ownerId)) {
            // 别人的配置不能改：实验室配置是账号资产，覆盖别人的等于改掉他做过的实验条件。
            throw GameException.Factory.forbidden("配置 " + existingId + " 属于其他账号，不能修改");
        }
        LabAgentConfig config = new LabAgentConfig(
                existing != null ? existing.agentConfigId() : newId("ac", ownerId),
                ownerId,
                name,
                text(body, "role"),
                text(body, "model"),
                number(body, "temperature"),
                text(body, "strategyProfile"),
                text(body, "persona") == null ? "" : text(body, "persona"),
                text(body, "memoryMode") == null ? "short" : text(body, "memoryMode"),
                text(body, "promptTemplateId") == null ? "default" : text(body, "promptTemplateId"),
                existing != null ? existing.createdAt() : now,
                now);
        return store.saveConfig(config);
    }

    /**
     * 创建批次。
     *
     * @return 新建的批次（调用方回 {@code 202 { batchId }}）
     */
    public LabBatch startBatch(Map<String, Object> body, String ownerId) {
        Map<String, String> errors = new LinkedHashMap<>();
        String name = text(body, "name");
        if (name == null || name.isBlank() || name.length() > 48) {
            errors.put("name", "批次名称需为 1–48 个字符。");
        }
        Integer gameCount = intValue(body, "gameCount");
        if (gameCount == null || gameCount < 1 || gameCount > MAX_GAMES_PER_BATCH) {
            errors.put("gameCount", "对局数需为 1–" + MAX_GAMES_PER_BATCH + " 的整数。");
        }
        List<String> configIds = strings(body, "agentConfigIds");
        if (configIds.isEmpty()) {
            errors.put("agentConfigIds", "至少选择一份已保存的 Agent 配置。");
        }
        Integer concurrency = intValue(body, "maxConcurrentGames");
        if (concurrency != null && (concurrency < 1 || concurrency > serverMaxConcurrentGames)) {
            errors.put("maxConcurrentGames",
                    "并发上限需在 1–" + serverMaxConcurrentGames + " 之间（服务端配置 lab.maxConcurrentGames）。");
        }        if (body != null && body.get("roleComposition") != null) {
            // 与 StartRoomGameApplicationService 同一条裁决：自定义角色配置在契约明确之前不解析。
            // 静默用默认配置开局会让"我明明配了 4 狼"变成一句无凭无据的抱怨。
            errors.put("roleComposition",
                    "当前仅支持经典九人（3 狼 / 3 民 / 1 预 / 1 女 / 1 猎），自定义角色配置尚未支持。");
        }
        if (!errors.isEmpty()) {
            throw validation(errors);
        }
        List<LabAgentConfig> configs = store.configsByIds(configIds);
        if (configs.size() != configIds.size()) {
            List<String> missing = new ArrayList<>(configIds);
            configs.forEach(config -> missing.remove(config.agentConfigId()));
            throw validation(Map.of("agentConfigIds", "以下配置不存在：" + missing));
        }
        List<String> foreign = new ArrayList<>();
        configs.stream()
                .filter(config -> !java.util.Objects.equals(config.ownerId(), ownerId))
                .forEach(config -> foreign.add(config.agentConfigId()));
        if (!foreign.isEmpty()) {
            // 用别人的配置开批次会消耗他的"实验条件"语义，也让"我的比对表"里混进不属于我的行。
            throw validation(Map.of("agentConfigIds", "以下配置属于其他账号：" + foreign));
        }

        int requested = concurrency == null ? serverMaxConcurrentGames : concurrency;
        int busy = store.runningBatches().stream().mapToInt(LabBatch::reservedConcurrency).sum();
        int room = serverMaxConcurrentGames - busy;
        if (room <= 0) {
            // 429 而不是排队：排队会让"这一批要跑多久"无法预估，而实验室的用途是等一个结论。
            // 抛应用层的异常而不是领域异常：这是准入控制，不是游戏规则（api-spec.md §1.2 的分层）。
            throw new LabAdmissionException(
                    "实验室并发已达上限：当前批次已预留 " + busy + " 路并发，服务端上限 "
                            + serverMaxConcurrentGames + " 路（api-spec.md §8）");
        }
        // 申请值大于剩余容量时**按剩余容量授信**，而不是把整个批次拒掉：
        // "现在只能再跑 3 局"是一个有用的答案，"什么都不给你"不是。
        // 同时不能超过这一批自己的局数：3 局的批次占 8 路并发会白占别人的额度。
        int effectiveConcurrency = Math.min(requested, Math.min(gameCount, room));

        long now = time.nowMillis();
        String batchId = newId("lb", ownerId);
        // 批次记录**从空列表开始**：`total` 是计划，`games` 是已经真的开出来的对局。
        // 先塞 N 个占位会立刻满足 allLaunched()，于是运行器永远不会创建任何对局——
        // 而进度接口会显示"2/2 已完成"。计划与事实必须是两个字段。
        LabBatch batch = new LabBatch(
                batchId,
                name,
                ownerId,
                LabBatch.RUNNING,
                gameCount,
                effectiveConcurrency,
                configIds,
                List.of(),
                null,
                now,
                now);
        return store.saveBatch(batch);
    }

    /**
     * 批次标识：{@code lb_{owner}_{millis}_{seq}}。
     *
     * <p>含所有者与进程内序号，是为了让"同一毫秒内的两个请求"也是不同的 id；
     * 不含随机数是为了让日志里能一眼看出归属与时间。
     */
    private String newId(String prefix, String ownerId) {
        String owner = ownerId == null || ownerId.isBlank() ? "anon" : ownerId.replaceAll("[^A-Za-z0-9]", "");
        if (owner.length() > 12) {
            owner = owner.substring(0, 12);
        }
        return prefix + "_" + owner + "_" + time.nowMillis() + "_" + sequence.incrementAndGet();
    }

    private static GameException validation(Map<String, String> errors) {
        // 用 withDetails 而不是自己拼 Detail 列表：它是领域给出的"字段 → 原因"构造入口，
        // 自己拼一遍会在领域改 Detail 形状时无声落后。
        throw GameException.Factory.withDetails(
                com.wolfmind.domain.game.model.DomainErrorCode.VALIDATION_ERROR,
                "实验室请求参数不合法",
                errors);
    }

    private static String text(Map<String, Object> body, String field) {
        Object value = body == null ? null : body.get(field);
        return value instanceof String string && !string.isBlank() ? string.trim() : null;
    }

    private static Integer intValue(Map<String, Object> body, String field) {
        Object value = body == null ? null : body.get(field);
        return value instanceof Number number ? number.intValue() : null;
    }

    private static double number(Map<String, Object> body, String field) {
        Object value = body == null ? null : body.get(field);
        return value instanceof Number n ? n.doubleValue() : 0d;
    }

    private static List<String> strings(Map<String, Object> body, String field) {
        Object value = body == null ? null : body.get(field);
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> result = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item instanceof String string && !string.isBlank()) {
                result.add(string);
            }
        }
        return List.copyOf(result);
    }

    /**
     * 实验室准入被拒（并发达到服务端上限）。
     *
     * <p>它刻意不是领域异常：拒绝的理由是"服务端资源已满"，与游戏规则无关，
     * 因此不能借用任何一个 {@code DomainErrorCode}。接口层把它映射为 {@code 429 RATE_LIMITED}
     * （api-spec.md §1.2 里那个码属于接口层）。
     */
    public static final class LabAdmissionException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public LabAdmissionException(String message) {
            super(message);
        }
    }
}
