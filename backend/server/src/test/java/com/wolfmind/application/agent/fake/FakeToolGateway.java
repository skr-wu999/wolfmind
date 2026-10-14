package com.wolfmind.application.agent.fake;

import com.wolfmind.application.agent.model.ActionProposal;
import com.wolfmind.application.agent.model.AgentView;
import com.wolfmind.application.agent.model.Candidate;
import com.wolfmind.application.agent.model.ToolSession;
import com.wolfmind.application.agent.tool.ToolGatewayPort;

import java.util.ArrayList;
import java.util.List;

/**
 * 工具网关的假实现：按脚本允许或拒绝，并记录调用次数与提交内容。
 *
 * <p>两条它必须保住的性质（否则单测会给出错误信心）：
 * <ul>
 *   <li><b>拒绝不产生事件</b>：被拒绝时不增加事件计数；</li>
 *   <li><b>只读不产生事件</b>：查询类调用前后事件计数不变。</li>
 * </ul>
 */
public final class FakeToolGateway implements ToolGatewayPort {

    private final List<String> submittedTools = new ArrayList<>();
    private final List<ActionProposal> submittedProposals = new ArrayList<>();
    private final List<ToolSession> sessions = new ArrayList<>();
    private int eventCount;
    private int version = 10;
    private String denialCode;
    private String denialTool;
    private final java.util.Set<String> invalidTargets = new java.util.HashSet<>();
    private boolean queryHistoryDenied;
    private int queryStateCalls;
    private int queryHistoryCalls;

    /** 让下一次（及之后）的提交被拒绝。 */
    public FakeToolGateway denying(String tool, String code) {
        this.denialTool = tool;
        this.denialCode = code;
        return this;
    }

    /** 指向这些目标的提交一律以 {@code ACTION_INVALID_TARGET} 拒绝（例如夜里刚死、尚未公布的座位）。 */
    public FakeToolGateway rejectingTargets(String... targetIds) {
        this.invalidTargets.addAll(List.of(targetIds));
        return this;
    }

    public FakeToolGateway allowing() {
        this.denialTool = null;
        this.denialCode = null;
        return this;
    }

    public FakeToolGateway denyingHistory() {
        this.queryHistoryDenied = true;
        return this;
    }

    public List<String> submittedTools() {
        return List.copyOf(submittedTools);
    }

    public List<ActionProposal> submittedProposals() {
        return List.copyOf(submittedProposals);
    }

    public List<ToolSession> sessions() {
        return List.copyOf(sessions);
    }

    public int eventCount() {
        return eventCount;
    }

    public int version() {
        return version;
    }

    public int queryStateCalls() {
        return queryStateCalls;
    }

    public int queryHistoryCalls() {
        return queryHistoryCalls;
    }

    @Override
    public ToolResult queryGameState(ToolSession session, String scope) {
        queryStateCalls++;
        return ToolResult.Ok.of("QueryGameStateTool", List.of(), List.of(), version);
    }

    @Override
    public ToolResult queryHistory(
            ToolSession session, Integer fromVersion, Integer toVersion, int limit) {
        queryHistoryCalls++;
        if (queryHistoryDenied) {
            return ToolResult.Denied.of("QueryHistoryTool", "BUDGET_EXCEEDED", "超过工具调用预算");
        }
        return ToolResult.Ok.of("QueryHistoryTool", List.of(), List.of(), version);
    }

    @Override
    public ToolResult submit(ToolSession session, String toolName, ActionProposal proposal) {
        sessions.add(session);
        if (denialCode != null && (denialTool == null || denialTool.equals(toolName))) {
            return ToolResult.Denied.of(toolName, denialCode, "被网关拒绝");
        }
        if (proposal.targetId() != null && invalidTargets.contains(proposal.targetId())) {
            return ToolResult.Denied.of(toolName, "ACTION_INVALID_TARGET", "目标已死亡");
        }
        submittedTools.add(toolName);
        submittedProposals.add(proposal);
        eventCount++;
        version++;
        return ToolResult.Ok.of(toolName, List.of("ev_" + version), List.of("SCRIPTED_EVENT"), version);
    }

    @Override
    public List<String> availableTools(String role, String phase) {
        List<String> tools = new ArrayList<>(List.of("QueryGameStateTool", "QueryHistoryTool"));
        switch (phase) {
            case "DAY_DISCUSS", "LAST_WORDS" -> tools.add("SpeakTool");
            case "DAY_VOTE" -> tools.add("SubmitVoteTool");
            case "NIGHT_WOLF" -> {
                if ("WEREWOLF".equals(role)) {
                    tools.add("KillPlayerTool");
                }
            }
            case "NIGHT_SEER", "NIGHT_WITCH", "HUNTER_SHOOT" -> tools.add("UseSkillTool");
            default -> {
                // 其它阶段只有只读工具
            }
        }
        return List.copyOf(tools);
    }

    @Override
    public List<String> allowedActionTypes(String role, String phase) {
        List<String> actions = new ArrayList<>();
        switch (phase) {
            case "DAY_DISCUSS", "LAST_WORDS" -> actions.add("SPEAK");
            case "DAY_VOTE" -> actions.add("VOTE");
            case "NIGHT_WOLF" -> {
                if ("WEREWOLF".equals(role)) {
                    actions.add("WOLF_KILL");
                }
            }
            case "NIGHT_SEER" -> {
                if ("SEER".equals(role)) {
                    actions.add("SEER_INSPECT");
                }
            }
            case "NIGHT_WITCH" -> {
                if ("WITCH".equals(role)) {
                    actions.add("WITCH_SAVE");
                    actions.add("WITCH_POISON");
                    actions.add("WITCH_PASS");
                }
            }
            case "HUNTER_SHOOT" -> {
                if ("HUNTER".equals(role)) {
                    actions.add("HUNTER_SHOOT");
                }
            }
            default -> {
                // 无可用动作
            }
        }
        actions.add("SKIP");
        return List.copyOf(actions);
    }
}
