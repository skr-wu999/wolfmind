package com.wolfmind.infrastructure.ai;

import com.wolfmind.infrastructure.json.Json;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class ComposeLlmConfigurationTest {
    @SuppressWarnings("unchecked")
    private Map<String, String> composeEnvironment(String provider, String timeout) throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("deploy/docker-compose.yml"))) root = root.getParent();
        assertNotNull(root);
        var command = new ProcessBuilder("docker", "compose", "--env-file", "deploy/.env.example", "-f",
                "deploy/docker-compose.yml", "config", "--format", "json").directory(root.toFile());
        command.environment().putAll(Map.of("WOLFMIND_LLM_PROVIDER", provider, "WOLFMIND_LLM_TIMEOUT_MS", timeout,
                "WOLFMIND_LLM_API_KEY", provider.isEmpty() ? "" : "fake-test-key",
                "WOLFMIND_LLM_MODEL", provider.isEmpty() ? "" : "deepseek-chat",
                "WOLFMIND_LLM_BASE_URL", provider.isEmpty() ? "" : "https://api.deepseek.com",
                "WOLFMIND_HTTP_PORT", "8080"));
        Process process;
        try { process = command.start(); }
        catch (IOException missingDocker) {
            Assumptions.abort("Docker CLI unavailable: Compose config contract not executed");
            throw missingDocker;
        }
        try {
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "Compose config timed out");
            assertEquals(0, process.exitValue(), "Compose config must succeed");
            var output = Json.readObject(new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            var backend = (Map<String, Object>) ((Map<?, ?>) output.get("services")).get("backend");
            var environment = (Map<String, Object>) backend.get("environment");
            return environment.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey,
                    entry -> String.valueOf(entry.getValue())));
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }

    @Test void providerAndCustomTimeoutReachTheRealLlmSettingsLoader() throws Exception {
        var environment = composeEnvironment("deepseek", "65000");
        var settings = LlmSettingsLoader.toSettings(LlmSettingsLoader.fromEnvironment(environment));
        assertEquals("deepseek", settings.provider());
        assertEquals(65_000, settings.timeoutMs());
        assertEquals("deepseek-chat", settings.defaultModel());
        assertTrue(settings.configured());
    }

    @Test void emptyModelConfigurationKeepsRuleFallbackAndTheDefaultTimeout() throws Exception {
        var environment = composeEnvironment("", "");
        assertEquals("30000", environment.get("WOLFMIND_LLM_TIMEOUT_MS"));
        var settings = LlmSettingsLoader.toSettings(LlmSettingsLoader.fromEnvironment(environment));
        assertFalse(settings.configured());
        assertEquals(30_000, settings.timeoutMs());
    }
}
