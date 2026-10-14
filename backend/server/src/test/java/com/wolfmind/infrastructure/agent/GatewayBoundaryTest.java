package com.wolfmind.infrastructure.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 本包的边界纪律（AGENTS.md §8、DEP-7、tool-gateway.md §13）。
 *
 * <p>两条断言：
 * <ol>
 *   <li>{@code infrastructure.agent} 里引用 {@code domain.game.model} 的文件<b>只有两个</b>，
 *       且各自只用"读取者身份"或"拒绝码"：{@code ViewerContexts}（{@code ViewerContext} /
 *       {@code PlayerId} 是 {@code GameViewQueryPort} 的入参类型）与
 *       {@code DomainRejectionTranslator}（{@code GameException} / {@code DomainErrorCode}）。
 *       任何新增引用都会让这条纪律变松，必须显式修改本测试并说明理由；</li>
 *   <li>网关不持单写者锁（§6）：本包不得出现 {@code GameLockPort} 或锁键。</li>
 * </ol>
 *
 * <p>与 {@code DependencyRulesTest} 的分工：那份测试守的是 {@code application.agent}（DEP-7），
 * 这份守的是端口实现的落点，两者都不允许被绕过。
 */
class GatewayBoundaryTest {

    /** 允许引用 domain.game.model 的文件 → 被允许的类型（注释里写明的白名单）。 */
    private static final Map<String, List<String>> ALLOWED_DOMAIN_MODEL_REFERENCES = Map.of(
            "ViewerContexts.java", List.of("ViewerContext", "PlayerId"),
            "DomainRejectionTranslator.java", List.of("GameException"));

    /**
     * 非"网关本体"的文件：装配点与端到端夹具。
     *
     * <p>它们本来就是"把真实应用层与真实适配器接起来"的代码（{@code Phase3AgentAssembly} 要构造
     * 应用服务，{@code InfrastructureGameHarness} 要构造内存存储与单写者锁），因此
     * "网关不持锁、不暴露仓库句柄、不引用领域模型"这三条只对<b>网关本体</b>成立。
     * 名单是封闭的：新增文件必须显式登记，不能悄悄绕过规则；目录/工具目录等全局断言对所有文件生效。
     */
    private static final Set<String> COMPOSITION_FILES =
            Set.of("Phase3AgentAssembly.java", "InfrastructureGameHarness.java");

    private static boolean isGatewayBody(Path path) {
        return !COMPOSITION_FILES.contains(path.getFileName().toString());
    }

    @Test
    @DisplayName("domain.game.model 只允许出现在两个文件里，且只出现白名单类型")
    void domainModelReferencesAreWhitelisted() {
        java.util.regex.Pattern reference =
                java.util.regex.Pattern.compile("domain\\.game\\.model\\.([A-Za-z0-9_]+)");
        Map<String, List<String>> found = new LinkedHashMap<>();
        for (Path path : sources()) {
            if (!isGatewayBody(path)) {
                continue;
            }
            String code = stripComments(read(path));
            java.util.regex.Matcher matcher = reference.matcher(code);
            List<String> types = new ArrayList<>();
            while (matcher.find()) {
                types.add(matcher.group(1));
            }
            if (!types.isEmpty()) {
                found.put(path.getFileName().toString(), types.stream().distinct().sorted().toList());
            }
        }

        assertEquals(ALLOWED_DOMAIN_MODEL_REFERENCES.keySet(), found.keySet(),
                "引用 domain.game.model 的文件集合发生变化：新增引用必须显式评审并说明理由，"
                        + "读视图请走 AgentViewQueryPort / GameViewQueryPort，拒绝请走 Denial");
        found.forEach((file, types) -> assertEquals(
                ALLOWED_DOMAIN_MODEL_REFERENCES.get(file).stream().sorted().toList(),
                types,
                file + " 引用的领域类型超出白名单"));
    }

    @Test
    @DisplayName("网关不持锁：网关本体不出现 GameLockPort 或 game:{id}:lock")
    void gatewayHoldsNoLock() {
        for (Path path : sources()) {
            if (!isGatewayBody(path)) {
                continue;
            }
            String code = stripComments(read(path));
            assertFalse(code.contains("GameLockPort"),
                    path.getFileName() + " 不得引用单写者锁（tool-gateway.md §6：锁只在应用层）");
            assertFalse(code.contains("game:{id}:lock"),
                    path.getFileName() + " 不得出现锁键");
        }
    }

    @Test
    @DisplayName("网关不暴露仓库与事件存储句柄（§13 禁止事项）")
    void gatewayExposesNoRepositoryOrEventStore() {
        for (Path path : sources()) {
            if (!isGatewayBody(path)) {
                continue;
            }
            String code = stripComments(read(path));
            for (String forbidden : List.of(
                    "GameRepositoryPort", "EventStorePort", "EventPublisherPort", "GameStateCachePort",
                    "SnapshotStorePort", "java.sql", "org.xerial", "redis")) {
                assertFalse(code.contains(forbidden),
                        path.getFileName() + " 不得引用 " + forbidden + "（§13：不暴露任意状态写入）");
            }
        }
    }

    @Test
    @DisplayName("工具目录与拒绝码常量是封闭集合")
    void catalogAndCodesAreClosed() {
        assertEquals(6, ToolCatalog.TOOLS.size(), "§2 的工具清单是六个，未新增工具");
        assertTrue(ToolCatalog.TOOLS.containsAll(List.of(
                "QueryGameStateTool", "QueryHistoryTool", "SpeakTool", "SubmitVoteTool",
                "KillPlayerTool", "UseSkillTool")));
        assertEquals(List.of("SELF", "PUBLIC", "MY_CAMP"), ToolCatalog.SCOPES,
                "§2.1 只有三个 scope，不存在 GOD");
        assertEquals(7, ToolCatalog.SKILLS.size(), "§2.2 的技能清单是七个");
        assertFalse(ToolCatalog.isRegistered("EventWriteTool"));
        assertFalse(ToolCatalog.isRegistered("SqlQueryTool"));
        assertTrue(DenialCodes.all().containsAll(List.of(
                DenialCodes.TOOL_UNKNOWN, DenialCodes.TOOL_ARGS_INVALID,
                DenialCodes.TOOL_IDENTITY_MISMATCH, DenialCodes.GAME_ALREADY_FINISHED,
                DenialCodes.ACTION_NOT_ALLOWED, DenialCodes.TURN_NOT_YOURS,
                DenialCodes.ACTION_INVALID_TARGET, DenialCodes.BUDGET_EXCEEDED)));
    }

    private static List<Path> sources() {
        Path root = backendRoot().resolve("server/src/main/java/com/wolfmind/infrastructure/agent");
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path backendRoot() {
        Path cursor = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (int i = 0; i < 4 && cursor != null; i++) {
            if (Files.isDirectory(cursor.resolve("server/src/main/java"))) {
                return cursor;
            }
            Path nested = cursor.resolve("backend");
            if (Files.isDirectory(nested.resolve("server/src/main/java"))) {
                return nested;
            }
            cursor = cursor.getParent();
        }
        throw new AssertionError("无法定位 backend 目录");
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 注释里提到领域类型名（说明"为什么不用它"）不应算作引用。 */
    private static String stripComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", " ");
    }
}
