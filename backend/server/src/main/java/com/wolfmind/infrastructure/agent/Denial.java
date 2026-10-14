package com.wolfmind.infrastructure.agent;

import com.wolfmind.application.agent.model.Candidate;
import com.wolfmind.application.agent.tool.ToolGatewayPort;

import java.util.List;

/**
 * 一条结构化拒绝（tool-gateway.md §5）。
 *
 * <p>为什么不让各处直接 new {@code ToolResult.Denied}：可重试性由码唯一决定
 * （{@link DenialCodes#retryable(String)}），散落各处就会出现"同一个码在一处标 true、
 * 另一处标 false"，而策略层正是按这个字段决定"原样重试"还是"换动作"。
 *
 * @param code                拒绝码
 * @param message             人类可读原因（可进 repair prompt）
 * @param retryable           是否可原样重试
 * @param allowedAlternatives 现在还能做什么（只在"还能换动作或换目标"的拒绝上填充）
 */
public record Denial(String code, String message, boolean retryable, List<Candidate> alternatives) {

    public Denial {
        alternatives = List.copyOf(alternatives);
    }

    public static Denial of(String code, String message) {
        return new Denial(code, message, DenialCodes.retryable(code), List.of());
    }

    /** 补上"还能做什么"：直接拼进 repair 节点的 prompt（§5）。 */
    public Denial withAlternatives(List<Candidate> candidates) {
        return new Denial(code, message, retryable, candidates == null ? List.of() : candidates);
    }

    public ToolGatewayPort.ToolResult.Denied toResult(String tool) {
        return new ToolGatewayPort.ToolResult.Denied(tool, code, message, retryable, alternatives);
    }
}
