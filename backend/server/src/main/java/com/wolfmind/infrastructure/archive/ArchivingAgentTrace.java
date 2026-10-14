package com.wolfmind.infrastructure.archive;

import com.wolfmind.application.agent.trace.AgentTracePort;
import com.wolfmind.application.port.DecisionTraceArchivePort;
import com.wolfmind.application.port.MetricsPort;
import com.wolfmind.infrastructure.memory.InMemoryAgentTrace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/**
 * 决策痕迹的双写装饰器：进程内轨迹 + 持久归档（Phased 5 AI 分析的数据源）。
 *
 * <p><b>为什么是装饰器而不是换掉内存实现：</b>轨迹的读路径有两条——AI 工具与测试读
 * "刚刚这一回合发生了什么"（要求毫秒级、与写入同进程一致），分析页读"上周那局 AI 当时怎么想的"
 * （要求跨重启存活）。两条路径的生命周期不同，用同一个存储会牺牲其中一条。
 *
 * <p><b>归档失败绝不影响对局。</b>{@code record} 在入口路径上被每次工具调用触发：
 * 让它抛异常等于"SQLite 写失败 → 对局停住"，而归档只是真相副本（D-1）。
 * 因此这里把异常收敛成一条 WARN 日志与一个计数。
 *
 * <p><b>读路径的同一条规则：</b>优先返回进程内轨迹（它是本次运行的权威），为空时才回落到归档。
 * 反过来会让"重启前刚跑完的回合"因为归档还没写完而读不到。
 */
public final class ArchivingAgentTrace implements AgentTracePort {

    private static final Logger LOG = LoggerFactory.getLogger(ArchivingAgentTrace.class);

    private final InMemoryAgentTrace live;
    private final DecisionTraceArchivePort archive;
    private final java.util.function.Consumer<String> afterTurn;
    private final MetricsPort metrics;

    public ArchivingAgentTrace(InMemoryAgentTrace live, DecisionTraceArchivePort archive) {
        this(live, archive, ignored -> {});
    }

    public ArchivingAgentTrace(InMemoryAgentTrace live, DecisionTraceArchivePort archive, java.util.function.Consumer<String> afterTurn) {
        this(live, archive, afterTurn, MetricsPort.NOOP);
    }

    public ArchivingAgentTrace(InMemoryAgentTrace live, DecisionTraceArchivePort archive,
            java.util.function.Consumer<String> afterTurn, MetricsPort metrics) {
        this.live = Objects.requireNonNull(live, "live");
        this.archive = archive; // 可为 null：没有持久层时退化成纯进程内轨迹
        this.afterTurn = afterTurn;
        this.metrics = metrics;
        if (archive != null) live.onEviction(gameId -> {
            metrics.counter("wolfmind.agent.trace.archive.dropped-games", 1);
            LOG.warn("决策轨迹待归档缓存超过保留上限，已释放（gameId={}）", gameId);
        });
    }

    @Override
    public void record(AgentTraceEvent event) {
        live.record(event);
        if (archive == null) {
            return;
        }
        try {
            archive.archive(event);
            if (event.wireType().equals("AGENT_TURN_FINISHED")) afterTurn.accept(event.gameId());
        } catch (RuntimeException error) {
            metrics.counter("wolfmind.agent.trace.archive.failures", 1);
            LOG.warn("决策痕迹归档失败，对局继续（traceType={} gameId={} playerId={}）：{}",
                    event.wireType(), event.gameId(), event.playerId(), error.getMessage());
        }
    }

    @Override
    public synchronized void finishGame(String gameId) {
        live.finishGame(gameId);
        if (archive != null) flushGame(gameId);
        updatePending();
    }

    @Override
    public synchronized void maintain() {
        live.maintain();
        if (archive != null) live.finishedGameIds().forEach(this::flushGame);
        updatePending();
    }

    private void flushGame(String gameId) {
        List<AgentTraceEvent> remaining = live.traces(gameId, null);
        try {
            var archived = new java.util.HashSet<Object>();
            archive.load(gameId, null).forEach(event -> archived.add(normalized(event)));
            for (AgentTraceEvent event : remaining) {
                if (!archived.contains(normalized(event))) archive.archive(event);
            }
            if (!remaining.isEmpty()) afterTurn.accept(gameId);
            live.clearGame(gameId);
        } catch (RuntimeException error) {
            metrics.counter("wolfmind.agent.trace.archive.failures", 1);
            LOG.warn("终局决策轨迹归档待重试（gameId={}）：{}", gameId, error.getMessage());
        }
    }

    private static Object normalized(AgentTraceEvent event) {
        return com.wolfmind.infrastructure.json.Json.read(
                com.wolfmind.infrastructure.json.Json.write(InMemoryAgentTrace.sanitize(event)));
    }

    private void updatePending() {
        metrics.gauge("wolfmind.agent.trace.archive.pending-games", archive == null ? 0 : live.finishedGameIds().size());
    }

    @Override
    public List<AgentTraceEvent> traces(String gameId, String playerId) {
        List<AgentTraceEvent> current = live.traces(gameId, playerId);
        if (!current.isEmpty() || archive == null) {
            return current;
        }
        try {
            return archive.load(gameId, playerId);
        } catch (RuntimeException error) {
            LOG.warn("读取归档决策痕迹失败（gameId={} playerId={}）：{}", gameId, playerId, error.getMessage());
            return List.of();
        }
    }
}
