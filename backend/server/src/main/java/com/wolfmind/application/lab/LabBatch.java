package com.wolfmind.application.lab;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
/**
 * 一个批量实验（api-spec.md §8 的 {@code /lab/batches}）。
 *
 * <p><b>"没有任何特殊通道"在这里的落地方式</b>：批量对局就是普通对局——同样的
 * {@code StartGameApplicationService}、同样的事件流、同样的规则校验、同样由应用层回合调度推进。
 * 批次只多做两件事：记录 {@code gameId → 角色 → 配置} 的绑定，以及汇总进度。
 * 一旦为实验室开一条捷径（比如跳过校验或直接写状态），实验结论就不再对真人对局有效。
 *
 * <p>进度**不在这里累加**，而是每轮从对局仓储重新读出来（{@link #withGames}）。
 * 计数式进度在进程重启后会归零或翻倍，而重读是幂等的。
 */
public record LabBatch(
        String batchId,
        String name,
        String ownerId,
        String status,
        int total,
        int maxConcurrentGames,
        List<String> agentConfigIds,
        List<GameRef> games,
        String error,
        long createdAt,
        long updatedAt) {

    /** 批次状态：{@code RUNNING} 表示还有对局未启动或未结束。 */
    public static final String RUNNING = "RUNNING";
    public static final String COMPLETED = "COMPLETED";
    public static final String FAILED = "FAILED";

    /** 单个对局在批次里的状态。 */
    public static final String GAME_PENDING = "PENDING";
    public static final String GAME_RUNNING = "RUNNING";
    public static final String GAME_FINISHED = "FINISHED";
    public static final String GAME_ABORTED = "ABORTED";
    public static final String GAME_FAILED = "FAILED";

    public LabBatch {
        agentConfigIds = List.copyOf(agentConfigIds == null ? List.of() : agentConfigIds);
        games = List.copyOf(games == null ? List.of() : games);
    }

    /** 已结束（含中止与失败）的对局数——这就是响应里的 {@code completed}。 */
    public int completed() {
        return (int) games.stream().filter(GameRef::terminal).count();
    }

    /** 已创建但尚未结束的对局数；用于判断能否再启动一局。 */
    public int inFlight() {
        return games.size() - completed();
    }

    /** 下一个要创建的对局序号；{@code games} 只装真的开出来的对局，因此它就是列表长度。 */
    public int nextIndex() {
        return games.size();
    }

    /**
     * 这个批次向服务端**预留**的并发额度。
     *
     * <p>用"已开出的对局数"当占用量是不够的：一个刚创建、还一局都没开的批次会显示占 0，
     * 于是可以无限地再创建批次——而它们一旦开始推进就会一起抢占同一个上限。
     * 预留额度取"批次并发上限"与"还没跑完的局数"的较小值：3 局的批次不可能占到 8 路的并发。
     */
    public int reservedConcurrency() {
        return Math.max(0, Math.min(maxConcurrentGames, total - completed()));
    }

    public boolean allLaunched() {
        return games.size() >= total;
    }

    /** 全部对局都到终态。 */
    public boolean settled() {
        return completed() == total && allLaunched();
    }

    public LabBatch withGames(List<GameRef> updated, long updatedAt) {
        return new LabBatch(batchId, name, ownerId, status, total, maxConcurrentGames,
                agentConfigIds, updated, error, createdAt, updatedAt);
    }

    public LabBatch withStatus(String newStatus, String newError, long updatedAt) {
        return new LabBatch(batchId, name, ownerId, newStatus, total, maxConcurrentGames,
                agentConfigIds, games, newError, createdAt, updatedAt);
    }

    /**
     * 一局实验对局。
     *
     * @param roleAssignments 角色 → 实际生效的实验室配置。未匹配到实验室配置的角色不在其中
     *                        （它们走规则基线，不属于任何比对对象）
     * @param winner          胜方阵营（{@code WOLF} / {@code VILLAGE}）；未结束为 {@code null}
     */
    public record GameRef(
            int index,
            String gameId,
            String status,
            String winner,
            Integer rounds,
            Map<String, AssignedConfig> roleAssignments) {

        public GameRef {
            roleAssignments = Map.copyOf(roleAssignments == null ? Map.of() : roleAssignments);
        }

        public boolean terminal() {
            return GAME_FINISHED.equals(status) || GAME_ABORTED.equals(status) || GAME_FAILED.equals(status);
        }

        public GameRef withOutcome(String newStatus, String newWinner, Integer newRounds) {
            return new GameRef(index, gameId, newStatus, newWinner, newRounds, roleAssignments);
        }
    }

    /**
     * 一次"配置坐到某个座位"的绑定。
     *
     * <p>它同时回答两个问题：这份配置这一局扮演什么角色（比对维度），
     * 以及它坐在哪个座位（胜负要从座位阵营推）。只记 {@code role → configId} 是不够的：
     * 那样"这份配置赢了吗"还得再反查一次角色表。
     *
     * @param camp 该角色所属阵营，开局即确定（{@code WEREWOLF} → {@code WOLF}，其余 → {@code VILLAGE}）
     */
    public record AssignedConfig(String agentConfigId, int seatNo, String camp) {}

    /**
     * 批次级统计（api-spec.md §8 的 {@code statistics}）。
     *
     * <p>它只统计**已结束**的对局，并显式给出分母：拿 3 局的结果去对比 50 局的计划，
     * 会让人以为差距已经显著。
     */
    public Map<String, Object> statistics() {
        int finished = 0;
        int aborted = 0;
        int wolfWins = 0;
        int villageWins = 0;
        int roundsTotal = 0;
        for (GameRef game : games) {
            if (GAME_FINISHED.equals(game.status())) {
                finished++;
                if ("WOLF".equals(game.winner())) {
                    wolfWins++;
                } else if ("VILLAGE".equals(game.winner())) {
                    villageWins++;
                }
                if (game.rounds() != null) {
                    roundsTotal += game.rounds();
                }
            } else if (GAME_ABORTED.equals(game.status()) || GAME_FAILED.equals(game.status())) {
                aborted++;
            }
        }
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("settled", finished + aborted);
        stats.put("finished", finished);
        stats.put("aborted", aborted);
        stats.put("wolfWins", wolfWins);
        stats.put("villageWins", villageWins);
        stats.put("averageRounds", finished == 0 ? null : round2((double) roundsTotal / finished));
        stats.put("configs", agentConfigIds);
        return stats;
    }

    private static double round2(double value) {
        return Math.round(value * 100d) / 100d;
    }
}
