package com.wolfmind.architecture;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 测试用的路径与源码读取工具。
 *
 * <p>架构规则（ddd-design.md §5.3 DEP-1..DEP-7）作用于"源码文本"，因此测试必须真的读到文件。
 * 若路径解析失败必须显式失败，绝不能退化成"没扫到文件所以通过"的空转测试。
 */
final class TestPaths {

    private TestPaths() {}

    /**
     * 定位 backend 目录。
     *
     * <p>Surefire 的 {@code user.dir} 是模块目录（{@code backend/engine} 或 {@code backend/server}），
     * 但从仓库根执行时也可能是 {@code wolfmind}。两种情形都要能找到 {@code backend}。
     */
    static Path backendRoot() {
        Path start = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        List<Path> candidates = new ArrayList<>();
        Path cursor = start;
        for (int i = 0; i < 4 && cursor != null; i++) {
            candidates.add(cursor);
            cursor = cursor.getParent();
        }
        for (Path candidate : candidates) {
            if (Files.isDirectory(candidate.resolve("engine/src/main/java"))
                    && Files.isDirectory(candidate.resolve("server/src/main/java"))) {
                return candidate;
            }
            Path nested = candidate.resolve("backend");
            if (Files.isDirectory(nested.resolve("engine/src/main/java"))
                    && Files.isDirectory(nested.resolve("server/src/main/java"))) {
                return nested;
            }
        }
        throw new AssertionError("无法从 user.dir=" + start + " 定位 backend 目录，架构规则测试无法执行");
    }

    static Path engineMain() {
        return backendRoot().resolve("engine/src/main/java");
    }

    static Path serverMain() {
        return backendRoot().resolve("server/src/main/java");
    }

    /** {@code domain.game.event} 的源码目录。 */
    static Path engineEventSourceDir() {
        return engineMain().resolve("com/wolfmind/domain/game/event");
    }

    /** 递归收集 {@code *.java}。 */
    static List<Path> javaFiles(Path root) {
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

    static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 去掉块注释与行注释，只留下有效代码。
     *
     * <p>否则"Javadoc 里写了禁止使用 redis"会被误判成"引用了 redis"，规则测试会变成噪声源。
     */
    static String stripComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", " ");
    }

    /** 源文件的 package 名。 */
    static String packageOf(String source) {
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;").matcher(source);
        return matcher.find() ? matcher.group(1) : "";
    }

    /** 源文件的 import 全名（不含 {@code static} 修饰符）。 */
    static List<String> importsOf(String source) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?m)^\\s*import\\s+(?:static\\s+)?([\\w.$]+)\\s*;")
                .matcher(stripComments(source));
        List<String> imports = new ArrayList<>();
        while (matcher.find()) {
            imports.add(matcher.group(1));
        }
        return imports;
    }

    /** 相对 backend 的短路径，用于失败信息。 */
    static String relative(Path path) {
        return backendRoot().relativize(path).toString().replace('\\', '/');
    }
}
