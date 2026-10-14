package com.wolfmind.application.port;

import com.wolfmind.domain.game.model.Game;
import com.wolfmind.domain.game.event.EventEnvelope;

import java.util.List;

/**
 * 对局仓储端口：加载完整聚合 + 提交一次变更。
 *
 * <p>加载语义固定为"最新快照 + 其后增量事件"（event-design.md §11 的恢复重放），
 * 快照缺失或校验失败时退化为从版本 1 全量重放；事件流仍是唯一真相（INV-15）。
 *
 * <p>提交语义固定为"状态变化 + 事件追加 + 幂等记录"同批生效（system-design.md §5）。
 */
public interface GameRepositoryPort {

    /**
     * 加载聚合。
     *
     * @throws com.wolfmind.domain.game.model.GameException
     *         {@code GAME_NOT_RUNNING} 当对局不存在
     */
    LoadedGame load(String gameId);

    /**
     * 提交一次聚合变更。
     *
     * <p>调用方必须已经持有该对局的单写者锁，并且已用 {@code expectedVersion} 做过前置校验。
     * 实现负责：追加事件（连续、无空洞）→ 更新状态投影 → 按需生成快照 → 记录幂等结果。
     * 发布不在此方法内：先持久化后发布由应用服务显式编排。
     *
     * @param expectedVersion 变更前聚合版本
     * @param events          本次产生的全部事件
     * @param snapshotPolicy  是否在本次提交后生成快照
     * @return 提交结果
     */
    CommitResult commit(
            String gameId,
            int expectedVersion,
            List<EventEnvelope> events,
            SnapshotPolicy snapshotPolicy);

    /** 加载结果：聚合与它已应用到的版本。 */
    record LoadedGame(Game game, int version) {

        public LoadedGame {
            if (game == null) {
                throw new IllegalArgumentException("game 不能为空");
            }
        }
    }

    /** 提交结果：写入后的版本与事件 id 列表。 */
    record CommitResult(int version, List<String> eventIds, List<String> eventTypes) {

        public CommitResult {
            eventIds = List.copyOf(eventIds);
            eventTypes = List.copyOf(eventTypes);
        }
    }

    /** 快照策略：快照节奏是配置项，不硬编码到领域（AGENTS.md §7）。 */
    enum SnapshotPolicy {
        /** 按事件间隔（默认每 50 个事件）与终态强制生成。 */
        INTERVAL,
        /** 本次提交不生成快照。 */
        NONE,
        /** 强制生成（终态、恢复后等）。 */
        FORCE
    }
}
