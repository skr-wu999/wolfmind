package com.wolfmind.application.agent.tool;

import com.wolfmind.application.agent.model.ActionProposal;
import com.wolfmind.application.agent.model.AgentView;
import com.wolfmind.application.agent.model.Candidate;
import com.wolfmind.application.agent.model.ToolSession;
import com.wolfmind.application.agent.llm.LlmClient;

import java.util.List;
import java.util.Map;

/**
 * 工具网关端口：Agent 系统与 Game 限界上下文之间的防腐层（ACL），也是**唯一写路径**
 * （tool-gateway.md §1、§10）。
 *
 * <p>它是端口而不是工具类，因为 Phase 3/4 是同进程直接调用，Phase 5 换成"消费组 + 调用通道"
 * 时只替换实现，Agent 侧与领域侧代码都不改。
 *
 * <p>三条不能绕过的东西：
 * <ol>
 *   <li>身份由 {@link ToolSession} 注入，模型无法指定 {@code gameId} / {@code playerId}（G3）；</li>
 *   <li>拒绝是结构化结果而不是异常，供策略层下一轮修正（§5）；</li>
 *   <li>网关**不含规则**：它只判断"这个工具在这个阶段对这个角色是否开放"，
 *       不判断"这一票是否算数"——后者的唯一实现是领域（§9）。</li>
 * </ol>
 */
public interface ToolGatewayPort {

    /**
     * 只读：查询对局状态（{@code QueryGameStateTool}）。
     *
     * @param scope {@code SELF} / {@code PUBLIC} / {@code MY_CAMP}；不存在 {@code GOD}
     */
    ToolResult queryGameState(ToolSession session, String scope);

    /**
     * 只读：查询历史事件（{@code QueryHistoryTool}）。
     *
     * @param limit 上限受 {@code gateway.queryHistory.maxLimit} 约束（默认 100）
     */
    ToolResult queryHistory(ToolSession session, Integer fromVersion, Integer toVersion, int limit);

    /**
     * 写：提交动作提案。
     *
     * <p>成功只表示"命令被领域接受且事件已写入"；最终状态以事件为准，工具永远不直接改状态。
     *
     * @param toolName 工具名（{@code SpeakTool} / {@code SubmitVoteTool} / {@code KillPlayerTool} /
     *                 {@code UseSkillTool}），动作类型由提案携带
     */
    ToolResult submit(ToolSession session, String toolName, ActionProposal proposal);

    /** 本回合该角色该阶段可用的工具白名单；同时用于 prompt 的 availableTools 与网关校验（同源）。 */
    List<String> availableTools(String role, String phase);

    /** 该角色该阶段允许提交的动作类型集合；策略层的候选生成与 OutputValidator 共用它。 */
    List<String> allowedActionTypes(String role, String phase);

    default List<LlmClient.LlmRequest.ToolSpec> toolSpecifications(String role, String phase) { return List.of(); }

    /** 原始参数校验与行动归一化；此入口不得提交行动。 */
    default PreparedCall prepareCall(ToolSession session, LlmClient.ToolCall call) {
        return new PreparedCall(null, null, ToolResult.Denied.of(call.name(), "TOOL_UNKNOWN", "不支持原生工具调用"));
    }

    default ModelReadResult executeRead(ToolSession session, LlmClient.ToolCall call) {
        return new ModelReadResult(ToolResult.Denied.of(call.name(), "TOOL_UNKNOWN", "不支持原生查询"),
                "{\"ok\":false,\"code\":\"TOOL_UNKNOWN\"}", null);
    }

    record PreparedCall(ActionProposal proposal, String proposalJson, ToolResult.Denied rejection) {
        public boolean valid() { return rejection == null; }
    }

    record ModelReadResult(ToolResult result, String content, AgentView authorizedView) {}

    /**
     * 工具调用结果。
     *
     * <p>成功与拒绝都是正常返回，{@code ok()} 区分二者；只有基础设施故障才抛异常。
     */
    sealed interface ToolResult permits ToolResult.Ok, ToolResult.Denied {

        boolean ok();

        /** 便于测试与 Trace 的一行摘要。 */
        String describe();

        /** 成功。 */
        record Ok(
                String tool,
                List<String> eventIds,
                List<String> eventTypes,
                int version,
                Map<String, Object> payload) implements ToolResult {

            public Ok {
                eventIds = List.copyOf(eventIds);
                eventTypes = List.copyOf(eventTypes);
                payload = Map.copyOf(payload);
            }

            public static Ok of(String tool, List<String> eventIds, List<String> eventTypes, int version) {
                return new Ok(tool, eventIds, eventTypes, version, Map.of());
            }

            @Override
            public boolean ok() {
                return true;
            }

            @Override
            public String describe() {
                return "ALLOWED " + tool + " version=" + version + " events=" + eventTypes;
            }
        }

        /**
         * 结构化拒绝（tool-gateway.md §5）。
         *
         * @param code                与 REST 同一套错误码枚举
         * @param message             人类可读原因（可进 repair prompt）
         * @param retryable           是否可原样重试
         * @param allowedAlternatives 现在还能做什么：直接拼进 repair 节点的 prompt
         */
        record Denied(
                String tool,
                String code,
                String message,
                boolean retryable,
                List<Candidate> allowedAlternatives) implements ToolResult {

            public Denied {
                allowedAlternatives = List.copyOf(allowedAlternatives);
            }

            public static Denied of(String tool, String code, String message) {
                return new Denied(tool, code, message, false, List.of());
            }

            @Override
            public boolean ok() {
                return false;
            }

            @Override
            public String describe() {
                return "DENIED " + tool + " code=" + code + " retryable=" + retryable;
            }
        }
    }

    /** 只读结果的便捷形状：查询类工具返回视图或事件列表。 */
    record QueryOutcome(AgentView view, List<Map<String, Object>> events) {

        public QueryOutcome {
            events = List.copyOf(events);
        }

        public static QueryOutcome of(AgentView view) {
            return new QueryOutcome(view, List.of());
        }

        public static QueryOutcome of(List<Map<String, Object>> events) {
            return new QueryOutcome(null, events);
        }
    }
}
