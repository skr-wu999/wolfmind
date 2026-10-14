package com.wolfmind.agent.e2e;

import com.wolfmind.application.agent.AgentRuntime;
import com.wolfmind.application.agent.model.AgentConfig;
import com.wolfmind.application.agent.trace.AgentTracePort;
import com.wolfmind.domain.game.event.EventEnvelope;
import com.wolfmind.domain.game.model.Camp;
import com.wolfmind.domain.game.model.GameSnapshot;
import com.wolfmind.domain.game.model.PlayerSnapshot;
import com.wolfmind.infrastructure.agent.wiring.InfrastructureGameHarness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 九人 AI 对局实跑报告生成器。
 *
 * <p><b>它是报告工具，不是回归测试。</b>存在的理由：{@code NineAiGameE2ETest} 只断言
 * "9 个 AI 能跑到终局"，而要看懂一局到底发生了什么（谁发牌成什么角色、每晚狼刀谁、
 * 预言家验了谁、女巫救/毒了谁、白天谁被放逐、9 个座位各行动了几次），必须把事件流
 * 与决策轨迹渲染成人能读的东西。断言只保留"这一局真的结束了"这一条，其余内容全部输出成文件。
 *
 * <p><b>为什么用规则基线</b>：本环境没有模型密钥（{@code wolfmind-llm.properties} 不存在、
 * {@code WOLFMIND_LLM_*} 未设置），而 {@code NineAiLiveGame} 在无密钥时**拒绝运行**。
 * 规则基线仍然走完全相同的生产链路（AIControllerAdapter → AgentRuntime → 工具网关 G1..G13
 * → SubmitActionApplicationService → Game.handle），只有"决定动作"的那一步由确定性规则
 * 而不是模型完成，因此"9 个座位自动完成一整局"这件事可以被真实观察到。
 *
 * <p><b>信息边界</b>：报告写在对局终局之后，角色与夜间选择此时按可见性规则已可披露
 * （{@code EventEnvelope.visibleAfterGame}）。报告不包含任何进行中的隐藏信息，
 * 因为它在 {@code GAME_FINISHED} 之后才生成。
 */
class NineAiGameReport {

    /** 与 {@code NineAiGameE2ETest} 相同的受控时钟锚点：让对局可复现。 */
    private static final long CLOCK_START = 1_730_000_000_000L;
    private static final long SEED = 20240501L;
    private static final int MAX_STEPS = 400;
    private static final String GAME_ID = "g_report_nine_ai";

    /** 输出路径：与仓库既有的实跑产物同放 backend 目录，便于随日志一起审阅。 */
    private static final Path OUTPUT = Path.of("phase9-nine-ai-match-report.md");

    /** 事件类型 → 中文名 + 是否值得打印细节。 */
    private static final Map<String, String> EVENT_LABEL = Map.ofEntries(
            Map.entry("GAME_STARTED", "开局（发牌 + 规则冻结）"),
            Map.entry("ROLE_ASSIGNED", "角色分配"),
            Map.entry("PHASE_CHANGED", "阶段跳转"),
            Map.entry("TURN_GRANTED", "发放回合"),
            Map.entry("WOLF_KILL_NOMINATED", "狼人提名"),
            Map.entry("WOLF_KILL_SELECTED", "狼刀确定"),
            Map.entry("SEER_INSPECTED", "预言家查验"),
            Map.entry("SEER_SKIPPED", "预言家未查验"),
            Map.entry("WITCH_SAVED", "女巫用解药"),
            Map.entry("WITCH_POISONED", "女巫用毒药"),
            Map.entry("WITCH_PASSED", "女巫不用药"),
            Map.entry("DEATH_ANNOUNCED", "死讯公布"),
            Map.entry("PLAYER_KILLED", "玩家出局"),
            Map.entry("PLAYER_SPOKE", "发言"),
            Map.entry("SPEECH_SKIPPED", "放弃发言"),
            Map.entry("PLAYER_VOTED", "投票"),
            Map.entry("VOTE_TALLY_RESOLVED", "计票结果"),
            Map.entry("HUNTER_SHOT", "猎人开枪"),
            Map.entry("HUNTER_SKIPPED", "猎人不开枪"),
            Map.entry("GAME_FINISHED", "对局结束"),
            Map.entry("GAME_ABORTED", "对局中止"));

    @Test
    @DisplayName("实跑一局 9 AI 对局并生成可读报告")
    void runOneNineAiMatchAndWriteReport() throws IOException {
        InfrastructureGameHarness harness = new InfrastructureGameHarness(CLOCK_START);
        NineAiGameRunner runner = NineAiGameRunner.startRuleBaseline(harness, GAME_ID, SEED);
        int steps = runner.runUntilFinished(MAX_STEPS);

        GameSnapshot finalSnapshot = runner.snapshot();
        assertEquals("FINISHED", finalSnapshot.status().name(),
                "本报告只对已终局的对局有意义；实际 status=" + finalSnapshot.status()
                        + " phase=" + finalSnapshot.phase() + " steps=" + steps);
        assertTrue(runner.everySeatHasTrace(), "9 个座位都必须被真正驱动过");

        String report = render(runner, finalSnapshot, steps);
        Files.writeString(OUTPUT, report, StandardCharsets.UTF_8);

        // 报告必须可读且包含关键小节，否则"生成了文件"并不等于"看到了对局"。
        assertTrue(report.contains("## 座位与角色"), "报告缺少座位小节");
        assertTrue(report.contains("## 事件时间线"), "报告缺少时间线小节");
        assertTrue(report.contains("## 每个座位的决策轨迹"), "报告缺少决策轨迹小节");
        System.out.println("报告已写入 " + OUTPUT.toAbsolutePath());
    }

    // ------------------------------------------------------------------ 渲染

    private static String render(NineAiGameRunner runner, GameSnapshot snapshot, int steps) {
        String newline = System.lineSeparator();
        StringBuilder builder = new StringBuilder(64 * 1024);

        builder.append("# 九人 AI 对局实跑报告").append(newline).append(newline);
        builder.append("> 由 `NineAiGameReport` 生成，用于人工核对“9 个 AI 座位自动完成一整局”。")
                .append(newline).append(newline);

        builder.append("## 对局概要").append(newline).append(newline);
        builder.append("| 项 | 值 |").append(newline);
        builder.append("| --- | --- |").append(newline);
        row(builder, "对局标识", GAME_ID);
        row(builder, "角色分配种子", String.valueOf(SEED));
        row(builder, "决策来源", "规则基线（确定性规则，不调用模型）");
        row(builder, "驱动步数", String.valueOf(steps));
        row(builder, "聚合版本 / 事件条数", snapshot.version() + " / " + runner.eventStream().size());
        row(builder, "轮次", String.valueOf(snapshot.round()));
        row(builder, "获胜阵营", campLabel(snapshot.winner()));
        row(builder, "终局阶段", snapshot.phase().name());
        row(builder, "AI 回合数", String.valueOf(runner.agentTurns()));
        row(builder, "其中规则兜底", String.valueOf(runner.fallbackTurns()));
        row(builder, "失败回合", String.valueOf(runner.failedTurns()));
        row(builder, "超时跳过", String.valueOf(runner.timeoutSkips()));
        row(builder, "阶段推进", String.valueOf(runner.stageAdvanceSteps()));
        row(builder, "在岗 Agent", String.valueOf(runner.activeInstanceCount()));
        builder.append(newline);

        builder.append("## 座位与角色").append(newline).append(newline);
        builder.append("> 对局已终局，角色按披露策略公开。").append(newline).append(newline);
        builder.append("| 座位 | 角色 | 阵营 | 终局状态 | 决策轨迹条数 |").append(newline);
        builder.append("| --- | --- | --- | --- | --- |").append(newline);
        for (PlayerSnapshot player : snapshot.players()) {
            String playerId = InfrastructureGameHarness.playerIdForSeat(player.seatNo().value());
            int traces = runner.trace().traces(GAME_ID, playerId).size();
            builder.append("| ").append(player.seatNo().value())
                    .append(" | ").append(roleLabel(player.role()))
                    .append(" | ").append(campLabel(player.role().camp()))
                    .append(" | ").append(player.alive() ? "存活" : "出局")
                    .append(" | ").append(traces)
                    .append(" |").append(newline);
        }
        builder.append(newline);

        builder.append("## 事件时间线").append(newline).append(newline);
        builder.append("> 只保留可读的关键事件；`GAME_STARTED` / `TURN_GRANTED` 等噪声事件折叠为计数。")
                .append(newline).append(newline);
        builder.append("| # | 轮 | 阶段 | 事件 | 谁 | 细节 |").append(newline);
        builder.append("| --- | --- | --- | --- | --- | --- |").append(newline);
        for (EventEnvelope envelope : runner.eventStream()) {
            String detail = detailOf(envelope);
            if (detail == null) {
                continue;
            }
            builder.append("| ").append(envelope.version())
                    .append(" | ").append(envelope.round())
                    .append(" | ").append(envelope.phase())
                    .append(" | ").append(label(envelope.type()))
                    .append(" | ").append(envelope.actorId())
                    .append(" | ").append(escape(detail))
                    .append(" |").append(newline);
        }
        builder.append(newline);

        builder.append("事件计数").append(newline).append(newline);
        for (Map.Entry<String, Long> entry : counts(runner).entrySet()) {
            builder.append("- `").append(entry.getKey()).append("` × ").append(entry.getValue()).append(newline);
        }
        builder.append(newline);

        builder.append("## 每个座位的决策轨迹").append(newline).append(newline);
        builder.append("> 轨迹写入 `game:{id}:agent:{playerId}`，**绝不进入对局事件流**（agent-runtime.md §12）。")
                .append(newline).append(newline);
        for (int seat = 1; seat <= 9; seat++) {
            String playerId = InfrastructureGameHarness.playerIdForSeat(seat);
            List<AgentTracePort.AgentTraceEvent> traces = runner.trace().traces(GAME_ID, playerId);
            builder.append("### 座位 ").append(seat).append("（").append(playerId).append("）")
                    .append(newline).append(newline);
            builder.append("- 轨迹条数：").append(traces.size()).append(newline);
            builder.append("- 事件类型：");
            Map<String, Long> byType = traces.stream().collect(Collectors.groupingBy(
                    trace -> trace.type().name(), LinkedHashMap::new, Collectors.counting()));
            builder.append(byType.isEmpty() ? "（无）" : byType.toString()).append(newline);
            builder.append("- 最后一次完成回合：");
            builder.append(traces.stream()
                    .filter(trace -> trace.type() == AgentTracePort.TraceEventType.AGENT_TURN_FINISHED)
                    .reduce((first, second) -> second)
                    .map(trace -> "round=" + trace.round() + " phase=" + trace.phase()
                            + " version=" + trace.version() + " payload=" + trace.payload())
                    .orElse("（无）"));
            builder.append(newline).append(newline);
        }

        builder.append("## 复现方式").append(newline).append(newline);
        builder.append("```powershell").append(newline);
        builder.append("cd backend").append(newline);
        builder.append("mvn -o -B -pl server -am test -Dtest=NineAiGameReport "
                + "-DfailIfNoSpecifiedTests=false").append(newline);
        builder.append("```").append(newline).append(newline);
        builder.append("真实模型版本（需要 `WOLFMIND_LLM_*` 或 `wolfmind-llm.properties`）：")
                .append(newline).append(newline);
        builder.append("```powershell").append(newline);
        builder.append("mvn -o -pl server -am test-compile").append(newline);
        builder.append("java -cp \"server/target/test-classes;server/target/classes;engine/target/classes;$env:CLASSPATH\" "
                + "com.wolfmind.agent.e2e.NineAiLiveGame").append(newline);
        builder.append("```").append(newline);

        return builder.toString();
    }

    /** 事件细节；返回 {@code null} 表示该事件不进时间线。 */
    private static String detailOf(EventEnvelope envelope) {
        Map<String, Object> payload = envelope.payload();
        return switch (envelope.type()) {
            case "ROLE_ASSIGNED" -> roleLabel(text(payload.get("role"))) + "（"
                    + campLabel(text(payload.get("camp"))) + "），座位 " + payload.get("seatNo");
            case "PHASE_CHANGED" -> payload.get("from") + " → " + payload.get("to")
                    + "（第 " + payload.get("round") + " 轮）";
            case "WOLF_KILL_NOMINATED" -> "狼 " + payload.get("wolfSeatNo") + " 号提名 "
                    + target(payload.get("targetSeatNo")) + (Boolean.TRUE.equals(payload.get("emptyKill"))
                    ? "（空刀）" : "");
            case "WOLF_KILL_SELECTED" -> "本夜刀口：" + target(payload.get("targetSeatNo"));
            case "SEER_INSPECTED" -> "验 " + target(payload.get("targetSeatNo"))
                    + " → " + ("WEREWOLF".equals(text(payload.get("result"))) ? "狼人" : "好人");
            case "SEER_SKIPPED" -> "未查验，原因 " + payload.get("reason");
            case "WITCH_SAVED" -> "救下 " + target(payload.get("targetSeatNo"));
            case "WITCH_POISONED" -> "毒杀 " + target(payload.get("targetSeatNo"));
            case "WITCH_PASSED" -> "不用药，原因 " + payload.get("reason");
            case "DEATH_ANNOUNCED" -> "公布死讯：" + seats(payload.get("deaths"));
            case "PLAYER_KILLED" -> "出局，死因 " + payload.get("primaryCause");
            case "PLAYER_SPOKE" -> "发言 " + lengthOf(payload.get("content")) + " 字："
                    + excerpt(text(payload.get("content")));
            case "SPEECH_SKIPPED" -> "放弃发言，原因 " + payload.get("reason");
            case "PLAYER_VOTED" -> Boolean.TRUE.equals(payload.get("abstain"))
                    ? "弃权"
                    : "投 " + target(payload.get("targetSeatNo"));
            case "VOTE_TALLY_RESOLVED" -> "计票 " + payload.get("result")
                    + "，票型 " + tally(payload.get("tally"))
                    + (payload.get("tieSeats") == null ? "" : "，平票座位 " + payload.get("tieSeats"));
            case "HUNTER_SHOT" -> "开枪带走 " + target(payload.get("targetSeatNo"));
            case "HUNTER_SKIPPED" -> "不开枪，原因 " + payload.get("reason");
            case "GAME_FINISHED" -> campLabel(text(payload.get("winner")))
                    + " 获胜，原因 " + payload.get("reason")
                    + "，共 " + payload.get("rounds") + " 轮，存活座位 " + payload.get("survivors");
            case "GAME_ABORTED" -> "中止：" + payload.get("reason") + "（版本 " + payload.get("atVersion") + "）";
            default -> null;
        };
    }

    private static Map<String, Long> counts(NineAiGameRunner runner) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String type : runner.eventTypes()) {
            counts.merge(type, 1L, Long::sum);
        }
        return counts;
    }

    // ------------------------------------------------------------------ 小工具

    private static void row(StringBuilder builder, String key, String value) {
        builder.append("| ").append(key).append(" | ").append(escape(value)).append(" |")
                .append(System.lineSeparator());
    }

    private static String label(String type) {
        return EVENT_LABEL.getOrDefault(type, type) + "（`" + type + "`）";
    }

    private static String text(Object value) {
        return value == null ? "-" : String.valueOf(value);
    }

    private static String target(Object seatNo) {
        return seatNo == null ? "不指定" : seatNo + " 号";
    }

    private static String lengthOf(Object content) {
        return content == null ? "0" : String.valueOf(String.valueOf(content).length());
    }

    private static String excerpt(String content) {
        if (content == null || content.isBlank()) {
            return "（空）";
        }
        String flat = content.replaceAll("\\s+", " ").trim();
        return flat.length() <= 40 ? flat : flat.substring(0, 40) + "…";
    }

    /** 死讯/票型的嵌套结构渲染；结构未知时退回原始 toString，不让报告因格式变化而失败。 */
    @SuppressWarnings("unchecked")
    private static String seats(Object deaths) {
        if (!(deaths instanceof List<?> list)) {
            return text(deaths);
        }
        List<String> rendered = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> entry = (Map<String, Object>) map;
                rendered.add(entry.get("seatNo") + " 号（" + entry.getOrDefault("causes", entry.get("cause")) + "）");
            } else {
                rendered.add(text(item));
            }
        }
        return String.join("、", rendered);
    }

    @SuppressWarnings("unchecked")
    private static String tally(Object tally) {
        if (!(tally instanceof List<?> list)) {
            return text(tally);
        }
        List<String> rendered = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> entry = (Map<String, Object>) map;
                rendered.add(entry.get("seatNo") + " 号=" + entry.getOrDefault("votes",
                        entry.getOrDefault("count", "?")) + " 票");
            } else {
                rendered.add(text(item));
            }
        }
        return String.join("，", rendered);
    }

    private static String roleLabel(Object role) {
        return switch (text(role)) {
            case "WEREWOLF" -> "狼人";
            case "VILLAGER" -> "村民";
            case "SEER" -> "预言家";
            case "WITCH" -> "女巫";
            case "HUNTER" -> "猎人";
            default -> text(role);
        };
    }

    private static String campLabel(Camp camp) {
        return camp == null ? "未知" : switch (camp) {
            case WOLF -> "狼人阵营";
            case VILLAGE -> "好人阵营";
        };
    }

    private static String campLabel(String camp) {
        return switch (text(camp)) {
            case "WOLF" -> "狼人阵营";
            case "VILLAGE" -> "好人阵营";
            default -> text(camp);
        };
    }

    /** 转义 Markdown 表格里会破坏结构的字符。 */
    private static String escape(String value) {
        if (value == null) {
            return "-";
        }
        return value.replace("|", "\\|").replace("\r", " ").replace("\n", " ");
    }

    /** 便于失败时定位：当前被驱动过的座位。 */
    @SuppressWarnings("unused")
    private static List<Integer> tracedSeats(NineAiGameRunner runner) {
        return Arrays.stream(new int[] {1, 2, 3, 4, 5, 6, 7, 8, 9})
                .filter(seat -> !runner.trace()
                        .traces(GAME_ID, InfrastructureGameHarness.playerIdForSeat(seat)).isEmpty())
                .boxed()
                .toList();
    }

}
