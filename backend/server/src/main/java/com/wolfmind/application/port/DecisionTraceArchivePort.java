package com.wolfmind.application.port;

import com.wolfmind.application.agent.trace.AgentTracePort;

import java.util.List;

/**
 * 决策痕迹归档端口（database-schema.md §3.6 的 {@code agent_memory}，{@code memory_type='DECISION_TRACE'}）。
 *
 * <p><b>为什么需要单独一个端口，而不是让分析用例直接读 {@link AgentTracePort}：</b>
 * 轨迹端口是**服务期**存储（进程内，随重启消失），而 AI 分析页要读的可能是几天前的对局。
 * 两者是不同的生命周期，混用会让"重启后分析全空"变成一个看起来像缺陷的正常现象。
 *
 * <p><b>写入失败不得影响对局。</b>归档是真相副本，不是真相（D-1）：调用方必须容忍
 * {@link #archive} 抛异常并继续推进入口路径，而不是把一次归档失败变成一次回合失败。
 *
 * <p><b>隐私底线（database-schema.md AP-D12）</b>：只归档 {@code AGENT_} 轨迹事件的结构化摘要，
 * 不归档完整 prompt、不归档私有事件 payload、不归档口令与令牌。实现在写入前必须再确认一次
 * 类型前缀，而不是信任调用方。
 */
public interface DecisionTraceArchivePort {

    /**
     * 归档一条轨迹事件。
     *
     * @return {@code true} 表示本次确实新增了一行；{@code false} 表示该行已存在（重复投递）
     */
    boolean archive(AgentTracePort.AgentTraceEvent event);

    /**
     * 读取某对局某座位的决策痕迹，按 {@code (version, occurredAt)} 升序。
     *
     * @param playerId 座位标识；{@code null} 表示该对局全部座位
     */
    List<AgentTracePort.AgentTraceEvent> load(String gameId, String playerId);

    /** 某对局已归档的轨迹条数；用于对账与"归档是否覆盖了这次对局"的判断。 */
    int count(String gameId);
}
