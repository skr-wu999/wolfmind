package com.wolfmind.interfaces.rest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 5 接口契约测试（api-spec.md §7 §8 §9）：AI 分析、AI 实验室、运维指标。
 *
 * <p>三条被验证的边界：
 * <ol>
 *   <li><b>分析与实验室必须登录</b>（api-spec.md §10）：匿名开放等于把整局角色与账号的 token 预算公开；</li>
 *   <li><b>指标端点永不因为数字不好看而失败</b>：它回答"跑得怎么样"，5xx 会让编排层重启一个正常实例；</li>
 *   <li><b>实验室的拒绝有正确的 HTTP 语义</b>：缺幂等键 400、并发满 429，两者可重试性不同。</li>
 * </ol>
 *
 * <p>后台调度在这里被关掉（{@code wolfmind.game.driver.enabled=false}、
 * {@code wolfmind.lab.enabled=false}）：契约测试断言的是接口行为，让调度线程同时推进对局会让结果
 * 取决于线程时序——与既有的驱动测试同一处理方式。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "wolfmind.auth.pbkdf2-iterations=1",
                "wolfmind.auth.viewer-resolver=session",
                "wolfmind.game.driver.enabled=false",
                "wolfmind.lab.enabled=false",
                "lab.maxConcurrentGames=2"
        })
class Phase5ApiContractTest {

    @Autowired
    private TestRestTemplate rest;

    private static HttpHeaders headers(String token, boolean idempotency) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.set("Authorization", "Bearer " + token);
        }
        if (idempotency) {
            headers.set("Idempotency-Key", UUID.randomUUID().toString());
        }
        return headers;
    }

    private ResponseEntity<Map> post(String path, String body, String token, boolean idempotency) {
        return rest.exchange(path, HttpMethod.POST,
                new HttpEntity<>(body, headers(token, idempotency)), Map.class);
    }

    private ResponseEntity<Map> get(String path, String token) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers(token, false)), Map.class);
    }

    private String login(String prefix) {
        String username = prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        ResponseEntity<Map> registered = post("/api/v1/auth/register",
                "{\"username\":\"" + username + "\",\"password\":\"pw-123456\"}", null, false);
        assertEquals(HttpStatus.CREATED, registered.getStatusCode(), "注册失败：" + registered.getBody());
        ResponseEntity<Map> loggedIn = post("/api/v1/auth/login",
                "{\"username\":\"" + username + "\",\"password\":\"pw-123456\"}", null, false);
        assertEquals(HttpStatus.OK, loggedIn.getStatusCode(), "登录失败：" + loggedIn.getBody());
        return (String) loggedIn.getBody().get("sessionToken");
    }

    private static String configBody() {
        return """
                {"name":"契约配置","role":"SEER","model":"deepseek-flash","temperature":0.6,
                 "strategyProfile":"BALANCED","persona":"冷静","memoryMode":"short",
                 "promptTemplateId":"default"}
                """;
    }

    @Test
    @DisplayName("分析与实验室对匿名请求返回 401，且不泄漏任何数据")
    void analysisAndLabRequireLogin() {
        assertEquals(HttpStatus.UNAUTHORIZED,
                get("/api/v1/games/g_missing/analysis/summary", null).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED,
                get("/api/v1/games/g_missing/analysis/agents/p_1", null).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, get("/api/v1/lab/agent-configs", null).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, get("/api/v1/lab/comparisons", null).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, get("/api/v1/lab/batches/none", null).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED,
                post("/api/v1/lab/agent-configs", configBody(), null, true).getStatusCode());
    }

    @Test
    @DisplayName("指标端点无需登录，且始终是 200")
    void metricsAreAlwaysReadable() {
        ResponseEntity<Map> response = get("/api/v1/metrics", null);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<?, ?> body = response.getBody();
        assertNotNull(body);
        assertTrue(body.containsKey("counters"));
        assertTrue(body.containsKey("timers"));
        assertTrue(body.containsKey("llmCost"));
        assertTrue(body.containsKey("llmBudget"));
        assertTrue(body.containsKey("uptimeMillis"));

        ResponseEntity<Map> names = get("/api/v1/metrics/names", null);
        assertEquals(HttpStatus.OK, names.getStatusCode());
        assertTrue(String.valueOf(names.getBody().get("name")).contains("wolfmind.llm.calls"));
    }

    @Test
    @DisplayName("健康检查真的检查了东西：依赖列表非空，且 LLM 不参与整体状态")
    void healthReportsRealDependencies() {
        ResponseEntity<Map> response = get("/api/v1/health", null);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<?, ?> body = response.getBody();
        assertEquals("UP", body.get("status"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> dependencies = (List<Map<String, Object>>) body.get("dependencies");
        assertFalse(dependencies.isEmpty(),
                "空的依赖列表会被读成'依赖都好'，而它实际的含义是'没有任何检查'");
        assertTrue(dependencies.stream().anyMatch(item -> "event-store".equals(item.get("name"))));
        Map<String, Object> llm = dependencies.stream()
                .filter(item -> "llm".equals(item.get("name")))
                .findFirst()
                .orElseThrow();
        assertEquals(false, llm.get("required"),
                "LLM 不可用只降低能力（规则兜底），不该把服务判成不健康（api-spec.md §9）");
        assertTrue(String.valueOf(llm.get("detail")).contains("规则兜底"),
                "未配置模型时必须说清楚会发生什么，而不是留一句空描述");
    }

    @Test
    @DisplayName("实验室：保存配置 → 创建批次 → 读进度；缺幂等键 400")
    void labHappyPathAndRequestShape() {
        String token = login("lab");

        ResponseEntity<Map> saved = post("/api/v1/lab/agent-configs", configBody(), token, true);
        assertEquals(HttpStatus.CREATED, saved.getStatusCode(), "保存失败：" + saved.getBody());
        String configId = (String) saved.getBody().get("agentConfigId");
        assertNotNull(configId);

        ResponseEntity<Map> catalog = get("/api/v1/lab/agent-configs", token);
        assertEquals(HttpStatus.OK, catalog.getStatusCode());
        assertEquals(1, ((Number) catalog.getBody().get("total")).intValue());

        // 缺 Idempotency-Key：请求形态错误用 400，而不是 401——客户端少发了一个头，不是没登录
        ResponseEntity<Map> noKey = post("/api/v1/lab/batches",
                "{\"name\":\"批次\",\"gameCount\":1,\"agentConfigIds\":[\"" + configId + "\"]}", token, false);
        assertEquals(HttpStatus.BAD_REQUEST, noKey.getStatusCode());
        assertEquals("VALIDATION_ERROR", noKey.getBody().get("code"));

        ResponseEntity<Map> started = post("/api/v1/lab/batches",
                "{\"name\":\"批次\",\"gameCount\":2,\"agentConfigIds\":[\"" + configId + "\"]}", token, true);
        assertEquals(HttpStatus.ACCEPTED, started.getStatusCode(), "创建失败：" + started.getBody());
        String batchId = (String) started.getBody().get("batchId");
        assertEquals(2, ((Number) started.getBody().get("total")).intValue());
        assertEquals(2, ((Number) started.getBody().get("serverMaxConcurrentGames")).intValue());

        ResponseEntity<Map> batch = get("/api/v1/lab/batches/" + batchId, token);
        assertEquals(HttpStatus.OK, batch.getStatusCode());
        assertEquals("RUNNING", batch.getBody().get("status"));
        assertEquals(0, ((Number) batch.getBody().get("completed")).intValue());
        assertTrue(batch.getBody().containsKey("statistics"));

        // 第二批次：两路并发已被第一批占满 → 429 且带 Retry-After
        ResponseEntity<Map> rejected = post("/api/v1/lab/batches",
                "{\"name\":\"再来\",\"gameCount\":1,\"agentConfigIds\":[\"" + configId + "\"]}", token, true);
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, rejected.getStatusCode());
        assertEquals("RATE_LIMITED", rejected.getBody().get("code"));
        assertNotNull(rejected.getHeaders().getFirst("Retry-After"));

        ResponseEntity<Map> comparisons = get(
                "/api/v1/lab/comparisons?agentConfigIds=" + configId, token);
        assertEquals(HttpStatus.OK, comparisons.getStatusCode());
        assertTrue(comparisons.getBody().containsKey("rows"));
        assertTrue(String.valueOf(comparisons.getBody().get("provenance")).contains("已结束"));
    }

    @Test
    @DisplayName("实验室：自定义角色配置与不存在的配置都被 400 拒绝并指明字段")
    void labRejectsUnsupportedRequests() {
        String token = login("lab2");
        ResponseEntity<Map> saved = post("/api/v1/lab/agent-configs", configBody(), token, true);
        String configId = (String) saved.getBody().get("agentConfigId");

        ResponseEntity<Map> custom = post("/api/v1/lab/batches",
                "{\"name\":\"批次\",\"gameCount\":1,\"agentConfigIds\":[\"" + configId + "\"],"
                        + "\"roleComposition\":{\"playerCount\":9}}", token, true);
        assertEquals(HttpStatus.BAD_REQUEST, custom.getStatusCode());
        assertTrue(String.valueOf(custom.getBody().get("details")).contains("roleComposition"));

        ResponseEntity<Map> missing = post("/api/v1/lab/batches",
                "{\"name\":\"批次\",\"gameCount\":1,\"agentConfigIds\":[\"ac_does_not_exist\"]}", token, true);
        assertEquals(HttpStatus.BAD_REQUEST, missing.getStatusCode());
        assertTrue(String.valueOf(missing.getBody().get("details")).contains("agentConfigIds"));

        ResponseEntity<Map> badConfig = post("/api/v1/lab/agent-configs",
                "{\"name\":\"\",\"role\":\"GUARD\",\"model\":\"\",\"temperature\":9}", token, true);
        assertEquals(HttpStatus.BAD_REQUEST, badConfig.getStatusCode());
        assertFalse(((List<?>) badConfig.getBody().get("details")).isEmpty(),
                "一次返回全部字段问题，避免客户端改一个报一个");
    }

    @Test
    @DisplayName("分析与回放：不存在的对局按 404 处理，而不是 500 或空对象")
    void unknownGameIsNotFound() {
        String token = login("ana");
        ResponseEntity<Map> summary = get("/api/v1/games/g_missing/analysis/summary", token);
        assertEquals(HttpStatus.NOT_FOUND, summary.getStatusCode(),
                "对局不存在是地址错误，不是权限问题，也不该是服务端错误：" + summary.getBody());

        // 真实存在但座位不匹配的分析请求 → 404（注意：对局不存在时先命中上面的 404）
        ResponseEntity<Map> replay = get("/api/v1/games/g_missing/replay?from=1&to=5", null);
        assertEquals(HttpStatus.NOT_FOUND, replay.getStatusCode());
    }
}
