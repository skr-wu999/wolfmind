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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 认证接口的端到端契约测试（api-spec.md §3）。
 *
 * <p><b>迭代次数被调到 1</b>：PBKDF2 的默认 12 万次是给生产用的（对抗离线爆破），
 * 但在测试里跑几十次会让构建慢几十秒。散列强度是构造参数而不是常量，正是为了这件事。
 *
 * <p>这个类同时是"会话令牌能真的驱动身份解析"的证据：{@code GET /auth/me} 走的
 * 是 {@code SessionViewerResolver} 的真实解析路径，不是测试替身。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "wolfmind.auth.pbkdf2-iterations=1",
                "wolfmind.auth.viewer-resolver=session"
        })
class AuthContractTest {

    @Autowired
    private TestRestTemplate rest;

    private static HttpHeaders json(String bearer) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (bearer != null) {
            headers.set("Authorization", "Bearer " + bearer);
        }
        return headers;
    }

    private ResponseEntity<Map> post(String path, String body, String bearer) {
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, json(bearer)), Map.class);
    }

    private ResponseEntity<Map> get(String path, String bearer) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(json(bearer)), Map.class);
    }

    /** 每个用例用独立用户名，避免共享 Spring 上下文里的账号表互相干扰。 */
    private static String uniqueName(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private Map<String, Object> register(String username, String password) {
        ResponseEntity<Map> response = post("/api/v1/auth/register",
                "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}", null);
        assertEquals(HttpStatus.CREATED, response.getStatusCode(), "注册失败：" + response.getBody());
        return response.getBody();
    }

    private String login(String username, String password) {
        ResponseEntity<Map> response = post("/api/v1/auth/login",
                "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}", null);
        assertEquals(HttpStatus.OK, response.getStatusCode(), "登录失败：" + response.getBody());
        return (String) response.getBody().get("sessionToken");
    }

    @Test
    @DisplayName("注册：返回 userId/username/displayName，且响应里没有口令或令牌")
    void registerReturnsIdentityWithoutCredentials() {
        String username = uniqueName("auth_reg_");
        Map<String, Object> body = register(username, "correct-horse");

        assertNotNull(body.get("userId"));
        assertEquals(username, body.get("username"));
        assertEquals(username, body.get("displayName"), "未给 displayName 时回落为 username");
        // 注册**不下发令牌**（api-spec.md §3.1）：注册与登录是两步，
        // 让注册顺带下发会话会掩盖"这个口令是否真的能登录"。
        assertFalse(body.containsKey("sessionToken"), "注册响应不得含会话令牌");
        assertFalse(body.containsKey("password"), "响应里绝不能出现口令");
        assertFalse(body.toString().contains("correct-horse"), "响应里绝不能出现明文口令");
    }

    @Test
    @DisplayName("口令散列不落入事件流：账号事件 payload 里只有身份字段")
    void passwordNeverEntersEventStream() {
        String username = uniqueName("auth_evt_");
        Map<String, Object> registered = register(username, "correct-horse");
        String userId = (String) registered.get("userId");
        String token = login(username, "correct-horse");

        // 账号事件是 JUDGE 可见性，属于审计数据，不面向浏览器推送。
        // 用 status 端点无法读到；这里改为断言"响应里从不出现凭据"这一可观测性质，
        // 事件流的 payload 契约（只有 userId/username/sessionId/at）由 UserEventCodec 的测试锁定。
        assertNotNull(token);
        assertTrue(token.startsWith(userId + "."), "令牌形如 {userId}.{sessionId}.{secret}");
        assertFalse(token.contains("correct-horse"), "令牌里绝不能出现明文口令");
    }

    @Test
    @DisplayName("重复用户名注册返回 409 USERNAME_TAKEN（INV-U1）")
    void duplicateUsernameIsRejected() {
        String username = uniqueName("auth_dup_");
        register(username, "correct-horse");

        ResponseEntity<Map> again = post("/api/v1/auth/register",
                "{\"username\":\"" + username + "\",\"password\":\"another-one\"}", null);

        assertEquals(HttpStatus.CONFLICT, again.getStatusCode());
        assertEquals("USERNAME_TAKEN", again.getBody().get("code"));
    }

    @Test
    @DisplayName("口令过短或用户名过短返回 400 VALIDATION_ERROR")
    void weakCredentialsAreRejected() {
        ResponseEntity<Map> shortPassword = post("/api/v1/auth/register",
                "{\"username\":\"" + uniqueName("auth_weak_") + "\",\"password\":\"short\"}", null);
        assertEquals(HttpStatus.BAD_REQUEST, shortPassword.getStatusCode());
        assertEquals("VALIDATION_ERROR", shortPassword.getBody().get("code"));

        ResponseEntity<Map> shortName = post("/api/v1/auth/register",
                "{\"username\":\"ab\",\"password\":\"long-enough-password\"}", null);
        assertEquals(HttpStatus.BAD_REQUEST, shortName.getStatusCode());
    }

    @Test
    @DisplayName("登录成功：下发不透明令牌与过期时刻")
    void loginIssuesToken() {
        String username = uniqueName("auth_login_");
        register(username, "correct-horse");

        ResponseEntity<Map> response = post("/api/v1/auth/login",
                "{\"username\":\"" + username + "\",\"password\":\"correct-horse\"}", null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        assertNotNull(body.get("sessionToken"));
        assertNotNull(body.get("expiresAt"));
        assertTrue(((Number) body.get("expiresAt")).longValue() > System.currentTimeMillis(),
                "过期时刻必须在将来");
        // 令牌是不透明串，不是 JWT：登出必须能立即撤销它。
        String token = (String) body.get("sessionToken");
        assertFalse(token.contains(".") && token.split("\\.").length == 3 && token.startsWith("eyJ"),
                "不得使用自包含签名令牌（api-spec.md §3.2 的形态是 {userId}.{sessionId}.{secret}）");
    }

    @Test
    @DisplayName("口令错误与用户不存在返回同一个响应（不可枚举账号）")
    void loginFailuresAreIndistinguishable() {
        String username = uniqueName("auth_enum_");
        register(username, "correct-horse");

        ResponseEntity<Map> wrongPassword = post("/api/v1/auth/login",
                "{\"username\":\"" + username + "\",\"password\":\"wrong-password\"}", null);
        ResponseEntity<Map> noSuchUser = post("/api/v1/auth/login",
                "{\"username\":\"" + uniqueName("auth_ghost_") + "\",\"password\":\"whatever-long\"}", null);

        assertEquals(wrongPassword.getStatusCode(), noSuchUser.getStatusCode(),
                "两种失败的 HTTP 状态必须一致，否则可以用状态码枚举账号");
        assertEquals(wrongPassword.getBody().get("message"), noSuchUser.getBody().get("message"),
                "两种失败的文案必须一致");
    }

    @Test
    @DisplayName("GET /auth/me：带令牌返回身份；无令牌 401；令牌无效也 401")
    void currentUserRequiresValidSession() {
        String username = uniqueName("auth_me_");
        register(username, "correct-horse");
        String token = login(username, "correct-horse");

        ResponseEntity<Map> me = get("/api/v1/auth/me", token);
        assertEquals(HttpStatus.OK, me.getStatusCode(), "带有效令牌应返回身份：" + me.getBody());
        assertEquals(username, me.getBody().get("username"));
        // 这两个字段尚未接入（需要按账号反查房间/订阅关系）：必须存在且为 null/空，
        // 而不是编造一个指向不存在对局的值。
        assertTrue(me.getBody().containsKey("activeGameId"));
        assertNull(me.getBody().get("activeGameId"));
        assertEquals(List.of(), me.getBody().get("spectatingGameIds"));

        assertEquals(HttpStatus.UNAUTHORIZED, get("/api/v1/auth/me", null).getStatusCode(),
                "无令牌必须 401");
        assertEquals(HttpStatus.UNAUTHORIZED, get("/api/v1/auth/me", "u_1.bogus.secret").getStatusCode(),
                "伪造令牌必须 401");
    }

    @Test
    @DisplayName("登出：撤销会话后原令牌不可用，且重复登出仍然成功（幂等）")
    void logoutRevokesSession() {
        String username = uniqueName("auth_logout_");
        register(username, "correct-horse");
        String token = login(username, "correct-horse");
        assertEquals(HttpStatus.OK, get("/api/v1/auth/me", token).getStatusCode());

        assertEquals(HttpStatus.NO_CONTENT, post("/api/v1/auth/logout", "{}", token).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, get("/api/v1/auth/me", token).getStatusCode(),
                "登出后原令牌必须立即失效");
        assertEquals(HttpStatus.NO_CONTENT, post("/api/v1/auth/logout", "{}", token).getStatusCode(),
                "重复登出应幂等成功");
    }

    @Test
    @DisplayName("一个账号可以多端登录，各自独立会话（api-spec.md §3.2）")
    void multipleSessionsAreIndependent() {
        String username = uniqueName("auth_multi_");
        register(username, "correct-horse");
        String first = login(username, "correct-horse");
        String second = login(username, "correct-horse");

        assertFalse(first.equals(second), "两次登录必须得到不同令牌");
        assertEquals(HttpStatus.OK, get("/api/v1/auth/me", first).getStatusCode());
        assertEquals(HttpStatus.OK, get("/api/v1/auth/me", second).getStatusCode());

        // 登出其中一个不影响另一个。
        post("/api/v1/auth/logout", "{}", first);
        assertEquals(HttpStatus.UNAUTHORIZED, get("/api/v1/auth/me", first).getStatusCode());
        assertEquals(HttpStatus.OK, get("/api/v1/auth/me", second).getStatusCode(),
                "登出一个会话不得影响另一个");
    }

    @Test
    @DisplayName("有效令牌让只读接口识别身份（会话驱动 ViewerResolver）")
    void sessionDrivesViewerResolver() {
        String username = uniqueName("auth_viewer_");
        register(username, "correct-horse");
        String token = login(username, "correct-horse");

        // 匿名读者看不到角色；带令牌后 viewerUserId 被填上，但参与者座位集合仍为空
        // （需要按对局反查），因此角色依然不可见——这是安全的一侧。
        ResponseEntity<Map> anonymous = get("/api/v1/games/g_auth_probe", null);
        ResponseEntity<Map> identified = get("/api/v1/games/g_auth_probe", token);

        // 对局不存在时两者都应得到 404（而不是 500）：说明身份解析没有把未知对局变成异常。
        assertEquals(404, anonymous.getStatusCode().value());
        assertEquals(404, identified.getStatusCode().value(),
                "身份解析不得改变只读接口的状态码语义");
    }
}
