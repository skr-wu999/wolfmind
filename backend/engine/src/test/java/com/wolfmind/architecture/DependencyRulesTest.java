package com.wolfmind.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 依赖规则的机器校验（docs/architecture/ddd-design.md §5.3 DEP-1..DEP-7）。
 *
 * <p>§5.3 的原文要求："以下规则写成测试，任何一条失败即构建失败。这是'不改变已有架构'的长期保障"。
 * 本测试用源码文本扫描实现，因此不引入 ArchUnit 之类的新依赖（AGENTS.md §3：不为测试引入基础设施）。
 *
 * <p>规则作用域是 {@code backend/engine/src/main/java} 与 {@code backend/server/src/main/java}；
 * 测试文件本身不参与判定。所有断言消息给出文档出处，便于直接定位到规则原文。
 */
class DependencyRulesTest {

    /** DEP-2 禁止在 domain 下出现的类型与设施（忽略大小写，先去掉注释再匹配）。 */
    private static final List<String> DOMAIN_FORBIDDEN_TOKENS = List.of(
            "java.time.Clock",
            "java.util.Random",
            "java.util.UUID",
            "redis",
            "sqlite",
            "langchain4j",
            "langgraph4j",
            "websocket",
            "org.slf4j",
            "java.util.logging");

    /** 领域层禁止取当前时间（INV-16：领域不使用时钟）。 */
    private static final List<String> DOMAIN_FORBIDDEN_CLOCK_CALLS = List.of(
            "System.currentTimeMillis",
            "Instant.now");

    /**
     * JDK 自带的包前缀（不是第三方 SDK）。
     *
     * <p>{@code com.sun.net.httpserver} 随 JDK 提供（{@code jdk.httpserver} 模块），
     * 因此 DEP-5 的"第三方 SDK"判定必须把它算作平台，否则规则本身写错。
     */
    private static final List<String> PLATFORM_PREFIXES = List.of(
            "java.", "javax.", "jdk.", "sun.", "com.sun.net.httpserver");

    /**
     * 第三方依赖坐标（本项目当前唯一一条，见 backend/pom.xml 的 {@code sqlite.version}）。
     *
     * <p>DEP-5 只拦截这些包出现在 {@code infrastructure..} 之外。
     */
    private static final List<String> THIRD_PARTY_PREFIXES = List.of("org.xerial");

    /** HTTP 类型标记，用于 DEP-4（只允许出现在 interface..rest）。 */
    private static final List<String> HTTP_MARKERS = List.of(
            "com.sun.net.httpserver",
            "java.net.http",
            "jakarta.servlet",
            "javax.servlet",
            "org.springframework.web",
            "org.springframework.http",
            "io.netty");

    private record SourceFile(Path path, String packageName, String code, List<String> imports) {

        boolean inPackage(String prefix) {
            return packageName.equals(prefix) || packageName.startsWith(prefix + ".");
        }

        String shortName() {
            return TestPaths.relative(path);
        }
    }

    private static List<SourceFile> sources() {
        List<SourceFile> result = new ArrayList<>();
        for (Path root : List.of(TestPaths.engineMain(), TestPaths.serverMain())) {
            for (Path path : TestPaths.javaFiles(root)) {
                String raw = TestPaths.read(path);
                result.add(new SourceFile(
                        path,
                        TestPaths.packageOf(raw),
                        TestPaths.stripComments(raw),
                        TestPaths.importsOf(raw)));
            }
        }
        return result;
    }

    private static List<String> violating(List<SourceFile> files, java.util.function.Predicate<SourceFile> rule) {
        return files.stream().filter(rule).map(SourceFile::shortName).sorted().toList();
    }

    @Test
    @DisplayName("扫描器必须真的读到源码，否则规则测试是空转")
    void scannerMustFindSources() {
        List<SourceFile> files = sources();
        long domainFiles = files.stream().filter(f -> f.inPackage("com.wolfmind.domain")).count();
        long serverFiles = files.stream().filter(f -> f.inPackage("com.wolfmind.application")).count();

        assertTrue(domainFiles >= 50,
                "engine 领域源码应被扫描到（当前 " + domainFiles + " 个文件）；若路径解析失败必须显式失败，"
                        + "不能让 DEP 规则在空集合上通过（ddd-design.md §5.3）");
        assertTrue(serverFiles >= 1,
                "server 应用层源码应被扫描到（当前 " + serverFiles + " 个文件）；DEP-7 依赖 service 模块源码可读，"
                        + "路径为 " + TestPaths.serverMain());
    }

    @Test
    @DisplayName("DEP-1: domain 不得依赖 application / infrastructure / interface(s)")
    void dep1DomainMustNotDependOutward() {
        List<SourceFile> files = sources();
        List<String> violations = violating(files, f -> f.inPackage("com.wolfmind.domain")
                && (f.imports().stream().anyMatch(i -> i.startsWith("com.wolfmind.application.")
                        || i.startsWith("com.wolfmind.infrastructure.")
                        || i.startsWith("com.wolfmind.interface."))
                || f.code().contains("com.wolfmind.application.")
                || f.code().contains("com.wolfmind.infrastructure.")
                || f.code().contains("com.wolfmind.interfaces.")));

        assertTrue(violations.isEmpty(),
                "DEP-1（ddd-design.md §5.3）：domain.. 不得依赖 infrastructure..、application..、interface..；"
                        + "违规文件：" + violations);
    }

    @Test
    @DisplayName("DEP-2: domain 不得引用 Redis/SQLite/LLM/WebSocket/日志框架/时钟/随机数/UUID")
    void dep2DomainMustNotReferenceInfrastructureTypes() {
        List<SourceFile> files = sources();
        List<String> violations = new ArrayList<>();
        for (SourceFile file : files) {
            if (!file.inPackage("com.wolfmind.domain")) {
                continue;
            }
            String code = file.code().toLowerCase(Locale.ROOT);
            for (String token : DOMAIN_FORBIDDEN_TOKENS) {
                if (code.contains(token.toLowerCase(Locale.ROOT))) {
                    violations.add(file.shortName() + " -> " + token);
                }
            }
        }

        assertTrue(violations.isEmpty(),
                "DEP-2（ddd-design.md §5.3、domain-model.md §12）：domain.. 不得出现 "
                        + "redis / sqlite / langchain4j / langgraph4j / websocket / java.time.Clock / "
                        + "java.util.Random / UUID / 日志框架的引用；违规：" + violations);
    }

    @Test
    @DisplayName("领域层不得取当前时间（INV-16 / domain-model.md §12）")
    void domainMustNotReadWallClock() {
        List<SourceFile> files = sources();
        List<String> violations = new ArrayList<>();
        for (SourceFile file : files) {
            if (!file.inPackage("com.wolfmind.domain")) {
                continue;
            }
            for (String call : DOMAIN_FORBIDDEN_CLOCK_CALLS) {
                if (file.code().contains(call)) {
                    violations.add(file.shortName() + " -> " + call);
                }
            }
        }

        assertTrue(violations.isEmpty(),
                "INV-16（ddd-design.md §4.2、domain-model.md §12）：领域内取当前时间会破坏重放确定性，"
                        + "时间只能由 ActionContext 注入；违规：" + violations);
    }

    @Test
    @DisplayName("DEP-3: application.game / application.room 与 application.agent 互不依赖")
    void dep3ApplicationSlicesMustNotDependOnEachOther() {
        List<SourceFile> files = sources();
        List<String> violations = new ArrayList<>();
        for (SourceFile file : files) {
            boolean gameOrRoom = file.inPackage("com.wolfmind.application.game")
                    || file.inPackage("com.wolfmind.application.room");
            boolean agent = file.inPackage("com.wolfmind.application.agent");
            if (gameOrRoom && (file.code().contains("com.wolfmind.application.agent.")
                    || file.imports().stream().anyMatch(i -> i.startsWith("com.wolfmind.application.agent.")))) {
                violations.add(file.shortName() + " -> application.agent");
            }
            if (agent && (file.code().contains("com.wolfmind.application.game.")
                    || file.code().contains("com.wolfmind.application.room.")
                    || file.imports().stream().anyMatch(i -> i.startsWith("com.wolfmind.application.game.")
                            || i.startsWith("com.wolfmind.application.room.")))) {
                violations.add(file.shortName() + " -> application.game/room");
            }
        }

        assertTrue(violations.isEmpty(),
                "DEP-3（ddd-design.md §5.3）：application..game 与 application..room 不得依赖 application..agent，"
                        + "反之亦然（同层通过事件与端口协作）；违规：" + violations);
    }

    @Test
    @DisplayName("DEP-5: 第三方 SDK 只能出现在 infrastructure")
    void dep5OnlyInfrastructureMayDependOnThirdPartySdk() {
        List<SourceFile> files = sources();
        List<String> violations = new ArrayList<>();
        for (SourceFile file : files) {
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

        assertTrue(violations.isEmpty(),
                "DEP-5（ddd-design.md §5.3）：只有 infrastructure.. 可以依赖第三方 SDK（本项目唯一坐标 "
                        + THIRD_PARTY_PREFIXES + "，即 sqlite-jdbc）；平台类型的白名单是 " + PLATFORM_PREFIXES
                        + "。违规：" + violations);
    }

    @Test
    @DisplayName("JDK 平台模块的落点约束：javax.sql / org.xerial 只在 infrastructure.sqlite，JDK HTTP 只在 interfaces.rest")
    void platformModulesMustStayInTheirSlice() {
        List<SourceFile> files = sources();
        List<String> violations = new ArrayList<>();
        for (SourceFile file : files) {
            boolean sqliteSlice = file.inPackage("com.wolfmind.infrastructure.sqlite");
            boolean restSlice = file.inPackage("com.wolfmind.interfaces.rest");
            for (String imported : file.imports()) {
                boolean sqliteType = imported.startsWith("java.sql.") || imported.startsWith("javax.sql.")
                        || imported.startsWith("org.xerial");
                if (sqliteType && !sqliteSlice) {
                    violations.add(file.shortName() + " -> " + imported + "（应只在 infrastructure.sqlite）");
                }
                if (imported.startsWith("com.sun.net.httpserver") && !restSlice) {
                    violations.add(file.shortName() + " -> " + imported + "（应只在 interfaces.rest）");
                }
            }
        }

        assertTrue(violations.isEmpty(),
                "DEP-4（ddd-design.md §5.3：interface..rest 之外不得出现 HTTP 类型）与分层落点："
                        + "JDBC/SQLite 类型只在 infrastructure.sqlite，JDK HTTP 类型只在 interfaces.rest；违规：" + violations);
    }

    @Test
    @DisplayName("DEP-4: HTTP 类型只能出现在 interface(s).rest")
    void dep4HttpTypesOnlyInRestSlice() {
        List<SourceFile> files = sources();
        List<String> violations = new ArrayList<>();
        for (SourceFile file : files) {
            if (file.inPackage("com.wolfmind.interfaces.rest") || file.inPackage("com.wolfmind.interface.rest")) {
                continue;
            }
            for (String marker : HTTP_MARKERS) {
                if (file.code().contains(marker)) {
                    violations.add(file.shortName() + " -> " + marker);
                }
            }
        }

        assertTrue(violations.isEmpty(),
                "DEP-4（ddd-design.md §5.3）：interface..rest 之外不得出现 HTTP 类型；违规：" + violations);
    }

    @Test
    @DisplayName("domain 只能依赖 Java 标准库（ddd-design.md §5.1 允许依赖列）")
    void domainMayOnlyDependOnJavaStandardLibrary() {
        List<SourceFile> files = sources();
        List<String> violations = new ArrayList<>();
        for (SourceFile file : files) {
            if (!file.inPackage("com.wolfmind.domain")) {
                continue;
            }
            for (String imported : file.imports()) {
                if (imported.startsWith("java.") || imported.startsWith("javax.") || imported.startsWith("com.wolfmind.domain.")) {
                    continue;
                }
                violations.add(file.shortName() + " -> " + imported);
            }
        }

        assertTrue(violations.isEmpty(),
                "ddd-design.md §5.1：Domain 的允许依赖仅为 Java 标准库（外加同层 domain.. 类型）；"
                        + "违规：" + violations);
    }

    @Test
    @DisplayName("DEP-6: domain.game.model 不得引用 domain.game.repository")
    void dep6AggregateMustNotKnowRepository() {
        List<SourceFile> files = sources();
        List<String> violations = violating(files, f -> f.inPackage("com.wolfmind.domain.game.model")
                && (f.code().contains("domain.game.repository")
                || f.imports().stream().anyMatch(i -> i.startsWith("com.wolfmind.domain.game.repository"))));

        assertTrue(violations.isEmpty(),
                "DEP-6（ddd-design.md §5.3、domain-model.md §10）：domain..game.model 不得引用 "
                        + "domain..game.repository（聚合不感知持久化）；违规：" + violations);

        assertFalse(files.stream().noneMatch(f -> f.inPackage("com.wolfmind.domain.game.model")),
                "DEP-6 必须真的扫到 domain.game.model 包，否则规则未生效（ddd-design.md §5.3）");
    }

    @Test
    @DisplayName("DEP-7: application.agent 不得引用 domain.game.model")
    void dep7AgentSliceMustNotTouchDomainModel() {
        List<SourceFile> files = sources();
        List<SourceFile> agentFiles = files.stream()
                .filter(f -> f.inPackage("com.wolfmind.application.agent"))
                .toList();
        List<String> violations = violating(agentFiles, f -> f.code().contains("domain.game.model")
                || f.imports().stream().anyMatch(i -> i.startsWith("com.wolfmind.domain.game.model")));

        assertTrue(violations.isEmpty(),
                "DEP-7（ddd-design.md §5.3）：application..agent 不得引用 domain..game.model"
                        + "（AI 侧只认 GameView，C-5）；违规：" + violations);
    }
}
