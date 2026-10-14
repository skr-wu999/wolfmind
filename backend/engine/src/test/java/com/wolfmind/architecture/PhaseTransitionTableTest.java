package com.wolfmind.architecture;

import com.wolfmind.domain.game.model.GamePhase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 阶段转换表与文档逐条对照（docs/architecture/ddd-design.md §3.2）。
 *
 * <p>§3.2 原文："阶段转换表（{@code GamePhase} 允许的后继，实现时作为常量表校验）"。
 *
 * <p>期望值**直接从文档的表格里解析**，而不是在测试里再抄一遍：这样文档更新后测试自动跟随，
 * 也避免"测试抄错"变成第二个真相来源。
 *
 * <p>{@code HUNTER_SHOOT} 的后继在文档中是动态的（回到 {@code resumePhase}），因此它的行不参与
 * 精确比对，另由 {@link #hunterShootReturnsToResumePhaseOnly()} 单独约束。
 */
class PhaseTransitionTableTest {

    /**
     * 文档表格里没有列出、但实现允许的后继：**已确认的偏差清单**。
     *
     * <p>当前为空集：`LAST_WORDS -> NIGHT_WOLF` 曾经是唯一的偏差（§3.2 的状态机图有这条边，
     * 表格漏写，导致"放逐某人 → 遗言结束 → 下一轮"的主流程抛表外跳转异常），
     * 现在 ddd-design.md §3.2 的表格已补齐 `LAST_WORDS` 行的 `NIGHT_WOLF` / `GAME_OVER`，
     * 文档与实现精确一致，因此清单收敛为空。
     *
     * <p>保留这个结构而不是删掉：任何**新的**未登记跳转都会让
     * {@link #documentedTransitionsAreExactlyImplemented()} 变红，而"确实需要但文档还没写"的边
     * 必须显式登记在这里，不能靠放宽断言蒙过去。
     */
    private static final Map<GamePhase, Set<GamePhase>> ACKNOWLEDGED_EXTRA_EDGES = Map.of();

    /** 合法的 {@code resumePhase} 取值：死亡的结算点原本将要进入的阶段（§3.2）。 */
    private static final Set<GamePhase> LEGAL_RESUME_PHASES = Set.of(
            GamePhase.NIGHT_WOLF, GamePhase.NIGHT_SEER, GamePhase.NIGHT_WITCH, GamePhase.NIGHT_SETTLE,
            GamePhase.DAY_ANNOUNCE, GamePhase.LAST_WORDS, GamePhase.DAY_DISCUSS, GamePhase.DAY_VOTE,
            GamePhase.VOTE_SETTLE, GamePhase.GAME_OVER);

    /** 从 ddd-design.md §3.2 的转换表里解析"当前阶段 -> 允许后继"。 */
    private static Map<GamePhase, Set<GamePhase>> documentedTable() {
        Path doc = TestPaths.backendRoot().getParent().resolve("docs/architecture/ddd-design.md");
        assertTrue(Files.isRegularFile(doc), "找不到设计文档，转换表校验无法进行：" + doc);

        List<String> lines;
        try {
            lines = Files.readAllLines(doc);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }

        int headerIndex = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains("阶段转换表")) {
                headerIndex = i;
                break;
            }
        }
        assertTrue(headerIndex >= 0, "ddd-design.md 里找不到「阶段转换表」小节（契约可能被移动或改名）");

        Pattern row = Pattern.compile("^\\|\\s*`([A-Z_]+)`\\s*\\|\\s*(.*?)\\s*\\|.*$");
        Map<GamePhase, Set<GamePhase>> table = new EnumMap<>(GamePhase.class);
        boolean tableStarted = false;
        for (int i = headerIndex + 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!tableStarted) {
                // 「阶段转换表（…）：」与表头之间隔着空行与说明文字，先找到表格的第一行
                if (line.startsWith("|")) {
                    tableStarted = true;
                } else {
                    continue;
                }
            } else if (!line.startsWith("|")) {
                break;
            }
            Matcher matcher = row.matcher(line);
            if (!matcher.matches()) {
                continue;
            }
            String fromName = matcher.group(1);
            String successorColumn = matcher.group(2);
            GamePhase from;
            try {
                from = GamePhase.valueOf(fromName);
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            if (successorColumn.contains("resumePhase")) {
                continue;
            }
            Set<GamePhase> successors = new LinkedHashSet<>();
            Matcher names = Pattern.compile("`([A-Z_]+)`").matcher(successorColumn);
            while (names.find()) {
                try {
                    successors.add(GamePhase.valueOf(names.group(1)));
                } catch (IllegalArgumentException ignored) {
                    // 列里的其它反引号内容（如动作名）不是阶段
                }
            }
            table.put(from, Set.copyOf(successors));
        }

        assertEquals(11, table.size(),
                "ddd-design.md §3.2 的转换表应能解析出 11 行（12 个阶段中 HUNTER_SHOOT 的后继是动态的）；"
                        + "解析结果 " + table.keySet());
        return table;
    }

    @Test
    @DisplayName("阶段枚举恰为文档列出的 12 个阶段")
    void phaseEnumMatchesDocumentedSet() {
        Set<String> expected = Set.of(
                "GAME_INIT", "NIGHT_WOLF", "NIGHT_SEER", "NIGHT_WITCH", "NIGHT_SETTLE",
                "DAY_ANNOUNCE", "LAST_WORDS", "DAY_DISCUSS", "DAY_VOTE", "VOTE_SETTLE",
                "HUNTER_SHOOT", "GAME_OVER");
        Set<String> actual = new LinkedHashSet<>();
        for (GamePhase phase : GamePhase.values()) {
            actual.add(phase.name());
        }
        assertEquals(expected, actual,
                "ddd-design.md §3.2 与 domain-model.md §4 的阶段表共 12 个阶段，不多不少");
    }

    @Test
    @DisplayName("§3.2 转换表：文档列出的每条后继都必须实现，未登记的额外后继必须为空")
    void documentedTransitionsAreExactlyImplemented() {
        Map<GamePhase, Set<GamePhase>> documented = documentedTable();

        List<String> missing = new ArrayList<>();
        List<String> unacknowledgedExtras = new ArrayList<>();

        for (GamePhase from : GamePhase.values()) {
            if (from == GamePhase.HUNTER_SHOOT) {
                // 该行的后继在文档里是 resumePhase（动态），不参与精确比对；见
                // hunterShootReturnsToResumePhaseOnly()
                continue;
            }
            Set<GamePhase> expected = documented.getOrDefault(from, Set.of());
            Set<GamePhase> actual = from.successors();

            for (GamePhase to : expected) {
                if (!actual.contains(to)) {
                    missing.add(from + " -> " + to);
                }
            }
            for (GamePhase to : actual) {
                if (!expected.contains(to)
                        && !ACKNOWLEDGED_EXTRA_EDGES.getOrDefault(from, Set.of()).contains(to)) {
                    unacknowledgedExtras.add(from + " -> " + to);
                }
            }
        }

        assertTrue(missing.isEmpty(),
                "ddd-design.md §3.2：文档列出的合法跳转实现里缺失 " + missing
                        + "（表内转换被拒绝会让合法流程走不通）");
        assertTrue(unacknowledgedExtras.isEmpty(),
                "ddd-design.md §3.2：出现文档没写、也不在已确认偏差清单里的跳转 " + unacknowledgedExtras
                        + "（转换表是封闭集合，未登记的边必须先补文档再实现）");
    }

    @Test
    @DisplayName("§3.2 转换表：DAY_DISCUSS 的允许后继只有 DAY_DISCUSS 与 DAY_VOTE")
    void dayDiscussHasNoDocumentedHunterShootEdge() {
        Set<GamePhase> actual = GamePhase.DAY_DISCUSS.successors();

        assertTrue(actual.containsAll(Set.of(GamePhase.DAY_DISCUSS, GamePhase.DAY_VOTE)),
                "ddd-design.md §3.2：DAY_DISCUSS 必须允许 DAY_DISCUSS 与 DAY_VOTE，实际 " + actual);
        assertFalse(actual.contains(GamePhase.HUNTER_SHOOT),
                "ddd-design.md §3.2 的转换表中 DAY_DISCUSS 的允许后继只有 DAY_DISCUSS 与 DAY_VOTE；"
                        + "HUNTER_SHOOT 只由结算点插入（NIGHT_SETTLE / DAY_ANNOUNCE / LAST_WORDS / VOTE_SETTLE），"
                        + "讨论阶段不产生死亡，因此 DAY_DISCUSS -> HUNTER_SHOOT 属于表外跳转");
    }

    @Test
    @DisplayName("已确认偏差清单必须是最小集：文档补齐后应自动收敛为空")
    void acknowledgedExtraEdgesAreStillNeeded() {
        for (Map.Entry<GamePhase, Set<GamePhase>> entry : ACKNOWLEDGED_EXTRA_EDGES.entrySet()) {
            for (GamePhase to : entry.getValue()) {
                assertTrue(entry.getKey().allowsNext(to),
                        "已确认偏差 " + entry.getKey() + " -> " + to + " 已不在实现里，"
                                + "请把这条从 ACKNOWLEDGED_EXTRA_EDGES 删除，保持清单最小");
            }
        }
    }

    @Test
    @DisplayName("HUNTER_SHOOT 的后继只能落在合法 resumePhase 内，且不得回到 GAME_INIT 或自身")
    void hunterShootReturnsToResumePhaseOnly() {
        Set<GamePhase> successors = GamePhase.HUNTER_SHOOT.successors();
        assertFalse(successors.isEmpty(),
                "ddd-design.md §3.2：HUNTER_SHOOT 开枪后必须回到 resumePhase，后继不能为空");
        assertFalse(successors.contains(GamePhase.GAME_INIT),
                "ddd-design.md §3.2：GAME_INIT 只能由 start 离开，不能作为 resumePhase");

        for (GamePhase to : successors) {
            assertTrue(LEGAL_RESUME_PHASES.contains(to),
                    "ddd-design.md §3.2：HUNTER_SHOOT 的后继必须是原本将要进入的阶段（resumePhase），"
                            + to + " 不在合法 resumePhase 集合内");
        }
        for (GamePhase resume : Set.of(GamePhase.DAY_ANNOUNCE, GamePhase.LAST_WORDS,
                GamePhase.DAY_DISCUSS, GamePhase.DAY_VOTE, GamePhase.NIGHT_WOLF, GamePhase.GAME_OVER)) {
            assertTrue(successors.contains(resume),
                    "ddd-design.md §3.2：HUNTER_SHOOT 必须能回到 resumePhase=" + resume
                            + "（该阶段是某个结算点的后继，必须能成为返回点）");
        }
    }

    @Test
    @DisplayName("GAME_OVER 是终态：无后继，不接受行动，且任何阶段都不得跳回 GAME_INIT")
    void gameOverIsTerminal() {
        assertTrue(GamePhase.GAME_OVER.successors().isEmpty(),
                "ddd-design.md §3.2：GAME_OVER 的后继为无（终态）");
        assertTrue(GamePhase.GAME_OVER.isTerminal(),
                "ddd-design.md §3.2：GAME_OVER 是终态");
        assertFalse(GamePhase.GAME_OVER.acceptsActions(),
                "ddd-design.md §4.1 INV-12：GAME_OVER 之后不接受任何行动");
        for (GamePhase phase : GamePhase.values()) {
            if (phase != GamePhase.GAME_OVER) {
                assertFalse(phase.successors().contains(GamePhase.GAME_INIT),
                        "ddd-design.md §3.2：GAME_INIT 只能由 start 进入，任何阶段都不得跳回它；违规阶段=" + phase);
            }
        }
    }
}
