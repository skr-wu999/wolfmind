package com.wolfmind.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * server 模块侧的依赖规则机器校验（docs/architecture/ddd-design.md §5.3 DEP-1..DEP-7）。
 *
 * <p>engine 侧已有一份同样的规则（{@code com.wolfmind.architecture.DependencyRulesTest}）。这里再放一份的
 * 原因是模块边界：engine 的测试类不会被 server 的 test classpath 看到，而 DEP-5/DEP-7 的判定对象主要在
 * server 模块，规则必须在 server 自己的构建里也能失败。两份实现各自独立读源码，故意不共享代码，
 * 避免"共享工具本身写错导致两边一起放行"。
 *
 * <p>包名前缀按 AGENTS.md §15.1：设计文档写 {@code interface}，Java 包名是 {@code com.wolfmind.interfaces}。
 */
class DependencyRulesTest {

    /** 平台（JDK）包前缀，不受 DEP-5 约束。 */
    private static final List<String> PLATFORM_PREFIXES = List.of(
            "java.", "javax.", "jdk.", "sun.", "com.sun.net.httpserver");

    /** 第三方存储客户端坐标：sqlite-jdbc 与 Redis（Lettuce / Spring Data Redis）。 */
    private static final List<String> THIRD_PARTY_PREFIXES = List.of(
            "org.xerial", "io.lettuce", "org.springframework.data.redis");

    /** domain 下禁止出现的类型与设施（DEP-2）。 */
    private static final List<String> DOMAIN_FORBIDDEN_TOKENS = List.of(
            "java.time.Clock", "java.util.Random", "java.util.UUID", "redis", "sqlite",
            "langchain4j", "langgraph4j", "websocket", "org.slf4j", "java.util.logging");

    private record SourceFile(Path path, String packageName, String code, List<String> imports) {

        boolean inPackage(String prefix) {
            return packageName.equals(prefix) || packageName.startsWith(prefix + ".");
        }

        String shortName() {
            return backendRoot().relativize(path).toString().replace('\\', '/');
        }
    }

    private static Path backendRoot() {
        Path start = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path cursor = start;
        for (int i = 0; i < 4 && cursor != null; i++) {
            if (Files.isDirectory(cursor.resolve("engine/src/main/java"))
                    && Files.isDirectory(cursor.resolve("server/src/main/java"))) {
                return cursor;
            }
            Path nested = cursor.resolve("backend");
            if (Files.isDirectory(nested.resolve("engine/src/main/java"))
                    && Files.isDirectory(nested.resolve("server/src/main/java"))) {
                return nested;
            }
            cursor = cursor.getParent();
        }
        throw new AssertionError("无法从 user.dir=" + start + " 定位 backend 目录，架构规则测试无法执行");
    }

    private static List<Path> javaFiles(Path root) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String stripComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", " ");
    }

    private static List<SourceFile> sources() {
        List<SourceFile> result = new ArrayList<>();
        for (Path root : List.of(
                backendRoot().resolve("engine/src/main/java"),
                backendRoot().resolve("server/src/main/java"))) {
            for (Path path : javaFiles(root)) {
                String raw;
                try {
                    raw = Files.readString(path, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                java.util.regex.Matcher packageMatcher =
                        java.util.regex.Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;").matcher(raw);
                String packageName = packageMatcher.find() ? packageMatcher.group(1) : "";
                java.util.regex.Matcher importMatcher = java.util.regex.Pattern
                        .compile("(?m)^\\s*import\\s+(?:static\\s+)?([\\w.$]+)\\s*;")
                        .matcher(stripComments(raw));
                List<String> imports = new ArrayList<>();
                while (importMatcher.find()) {
                    imports.add(importMatcher.group(1));
                }
                result.add(new SourceFile(path, packageName, stripComments(raw), imports));
            }
        }
        return result;
    }

    private static List<String> violations(
            List<SourceFile> files, Predicate<SourceFile> rule) {
        return files.stream().filter(rule).map(SourceFile::shortName).sorted().toList();
    }

    @Test
    @DisplayName("接口包只保留传输适配，不承载应用用例服务")
    void applicationServicesAreNotPlacedInInterfacePackages() {
        List<String> misplaced = violations(sources(), file -> file.inPackage("com.wolfmind.interfaces")
                && file.path().getFileName().toString().endsWith("ApplicationService.java"));
        assertTrue(misplaced.isEmpty(), "应用服务放错层：" + misplaced);
    }

    @Test
    @DisplayName("扫描器必须真的读到两个模块的源码")
    void scannerMustFindSources() {
        List<SourceFile> files = sources();
        long engine = files.stream().filter(f -> f.inPackage("com.wolfmind.domain")).count();
        long server = files.stream().filter(f -> f.inPackage("com.wolfmind.application")).count()
                + files.stream().filter(f -> f.inPackage("com.wolfmind.infrastructure")).count()
                + files.stream().filter(f -> f.inPackage("com.wolfmind.interfaces")).count();

        assertTrue(engine >= 50, "engine 领域源码应被扫描到，实际 " + engine + " 个文件");
        assertTrue(server >= 20, "server 各层源码应被扫描到，实际 " + server + " 个文件");
    }

    @Test
    @DisplayName("DEP-1: domain 不得依赖 application / infrastructure / interface(s)")
    void dep1() {
        List<String> violations = violations(sources(), f -> f.inPackage("com.wolfmind.domain")
                && (f.imports().stream().anyMatch(i -> i.startsWith("com.wolfmind.application.")
                        || i.startsWith("com.wolfmind.infrastructure.")
                        || i.startsWith("com.wolfmind.interfaces.")
                        || i.startsWith("com.wolfmind.interface."))
                || f.code().contains("com.wolfmind.application.")
                || f.code().contains("com.wolfmind.infrastructure.")
                || f.code().contains("com.wolfmind.interfaces.")));

        assertTrue(violations.isEmpty(),
                "DEP-1（ddd-design.md §5.3）：domain.. 不得依赖 application..、infrastructure..、interfaces..；违规：" + violations);
    }

    @Test
    @DisplayName("DEP-2: domain 不得引用 Redis/SQLite/LLM/WebSocket/日志框架/时钟/随机数/UUID")
    void dep2() {
        List<String> violations = new ArrayList<>();
        for (SourceFile file : sources()) {
            if (!file.inPackage("com.wolfmind.domain")) {
                continue;
            }
            String code = file.code().toLowerCase(Locale.ROOT);
            for (String token : DOMAIN_FORBIDDEN_TOKENS) {
                if (code.contains(token.toLowerCase(Locale.ROOT))) {
                    violations.add(file.shortName() + " -> " + token);
                }
            }
            if (file.code().contains("System.currentTimeMillis") || file.code().contains("Instant.now")) {
                violations.add(file.shortName() + " -> 取当前时间（INV-16）");
            }
        }
        violations.sort(String::compareTo);

        assertTrue(violations.isEmpty(),
                "DEP-2（ddd-design.md §5.3）与 INV-16（§4.2）：domain 只允许 Java 标准库的集合/字符串/数学能力，"
                        + "且不得取时钟；违规：" + violations);
    }

    @Test
    @DisplayName("DEP-3: application.game / application.room 与 application.agent 互不依赖")
    void dep3() {
        List<String> violations = new ArrayList<>();
        for (SourceFile file : sources()) {
            boolean gameOrRoom = file.inPackage("com.wolfmind.application.game")
                    || file.inPackage("com.wolfmind.application.room");
            boolean agent = file.inPackage("com.wolfmind.application.agent");
            if (gameOrRoom && file.code().contains("com.wolfmind.application.agent.")) {
                violations.add(file.shortName() + " -> application.agent");
            }
            if (agent && (file.code().contains("com.wolfmind.application.game.")
                    || file.code().contains("com.wolfmind.application.room."))) {
                violations.add(file.shortName() + " -> application.game/room");
            }
        }
        violations.sort(String::compareTo);

        assertTrue(violations.isEmpty(),
                "DEP-3（ddd-design.md §5.3）：同级切片之间只能通过事件与端口协作；违规：" + violations);
    }

    @Test
    @DisplayName("DEP-4: HTTP 类型只能出现在 interfaces.rest")
    void dep4() {
        List<String> httpMarkers = List.of(
                "com.sun.net.httpserver", "java.net.http", "jakarta.servlet", "javax.servlet",
                "org.springframework.web", "org.springframework.http", "io.netty");
        List<String> violations = new ArrayList<>();
        for (SourceFile file : sources()) {
            if (file.inPackage("com.wolfmind.interfaces.rest") || file.inPackage("com.wolfmind.interface.rest")) {
                continue;
            }
            for (String marker : httpMarkers) {
                if (file.code().contains(marker)) {
                    violations.add(file.shortName() + " -> " + marker);
                }
            }
        }
        violations.sort(String::compareTo);

        assertTrue(violations.isEmpty(),
                "DEP-4（ddd-design.md §5.3）：interface..rest 之外不得出现 HTTP 类型；违规：" + violations);
    }

    @Test
    @DisplayName("DEP-5: 第三方 SDK 只能出现在 infrastructure")
    void dep5() {
        List<String> violations = new ArrayList<>();
        for (SourceFile file : sources()) {
            if (file.inPackage("com.wolfmind.infrastructure")) {
                continue;
            }
            for (String imported : file.imports()) {
                for (String coordinate : THIRD_PARTY_PREFIXES) {
                    if (imported.startsWith(coordinate)) {
                        violations.add(file.shortName() + " -> " + imported);
                    }
                }
            }
        }
        violations.sort(String::compareTo);

        assertTrue(violations.isEmpty(),
                "DEP-5（ddd-design.md §5.3）：只有 infrastructure.. 可以依赖第三方 SDK（" + THIRD_PARTY_PREFIXES
                        + "）；平台白名单：" + PLATFORM_PREFIXES + "；违规：" + violations);
    }

    @Test
    @DisplayName("JDBC/SQLite 类型只在 infrastructure.sqlite，Redis 客户端只在 infrastructure.redis，JDK HTTP 只在 interfaces.rest")
    void platformTypesStayInTheirSlice() {
        List<String> violations = new ArrayList<>();
        for (SourceFile file : sources()) {
            boolean sqliteSlice = file.inPackage("com.wolfmind.infrastructure.sqlite");
            boolean redisSlice = file.inPackage("com.wolfmind.infrastructure.redis");
            boolean restSlice = file.inPackage("com.wolfmind.interfaces.rest");
            for (String imported : file.imports()) {
                boolean redisType = imported.startsWith("io.lettuce")
                        || imported.startsWith("org.springframework.data.redis");
                if (redisType && !redisSlice) {
                    violations.add(file.shortName() + " -> " + imported + "（应只在 infrastructure.redis）");
                }
                boolean sqliteType = imported.startsWith("java.sql.")
                        || imported.startsWith("javax.sql.")
                        || imported.startsWith("org.xerial");
                if (sqliteType && !sqliteSlice) {
                    violations.add(file.shortName() + " -> " + imported + "（应只在 infrastructure.sqlite）");
                }
                if (imported.startsWith("com.sun.net.httpserver") && !restSlice) {
                    violations.add(file.shortName() + " -> " + imported + "（应只在 interfaces.rest）");
                }
            }
        }
        violations.sort(String::compareTo);

        assertTrue(violations.isEmpty(),
                "分层落点约束（ddd-design.md §5.2 包结构 + §5.3 DEP-4）：违规：" + violations);
    }

    @Test
    @DisplayName("DEP-6: domain.game.model 不得引用 domain.game.repository")
    void dep6() {
        List<SourceFile> files = sources();
        List<String> violations = violations(files, f -> f.inPackage("com.wolfmind.domain.game.model")
                && f.code().contains("domain.game.repository"));

        assertTrue(violations.isEmpty(),
                "DEP-6（ddd-design.md §5.3）：聚合不得感知持久化；违规：" + violations);
        assertTrue(files.stream().anyMatch(f -> f.inPackage("com.wolfmind.domain.game.model")),
                "DEP-6 必须真的扫到 domain.game.model 包，否则规则未生效");
    }

    @Test
    @DisplayName("DEP-7: application.agent 不得引用 domain.game.model，只能消费视图")
    void dep7() {
        List<SourceFile> agentFiles = sources().stream()
                .filter(f -> f.inPackage("com.wolfmind.application.agent"))
                .toList();
        List<String> violations = violations(agentFiles,
                f -> f.code().contains("domain.game.model")
                        || f.imports().stream().anyMatch(i -> i.startsWith("com.wolfmind.domain.game.model")));

        assertTrue(violations.isEmpty(),
                "DEP-7（ddd-design.md §5.3）：application..agent 不得引用 domain..game.model"
                        + "（AI 侧只认 GameViewSnapshot，C-5）；违规：" + violations);
    }
}
