package com.wolfmind.application.user;

import com.wolfmind.application.port.TimePort;
import com.wolfmind.domain.game.event.DomainEvent;
import com.wolfmind.domain.game.model.ActionContext;
import com.wolfmind.domain.game.model.GameException;
import com.wolfmind.domain.user.event.UserLoggedInEvent;
import com.wolfmind.domain.user.event.UserLoggedOutEvent;
import com.wolfmind.domain.user.model.User;
import com.wolfmind.domain.user.model.UserId;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 认证用例：注册、登录、登出、读取当前账号（api-spec.md §3）。
 *
 * <p><b>三条硬约束</b>（都来自 event-design.md §7.3 尾注与 redis-schema.md §5.9）：
 * <ol>
 *   <li>口令只以散列与盐存储，**永不进入事件与日志**；</li>
 *   <li>会话令牌**不进事件流**——只存会话记录（将来是 Redis 的 {@code user:{id}:session:{sessionId}}，
 *       带 12 小时 TTL）。事件里只留 {@code sessionId} 供审计；</li>
 *   <li>登录失败不区分"用户不存在"与"口令错误"，避免账号枚举。</li>
 * </ol>
 *
 * <p>会话时长与散列迭代次数都是构造参数：前者是产品配置，后者需要在测试里调低
 * （否则每次构建都要跑几十万次 PBKDF2）。
 */
public final class AuthApplicationService {

    /** 会话有效期：12 小时（redis-schema.md §5.9 的设计建议值）。 */
    public static final long DEFAULT_SESSION_TTL_MILLIS = 12L * 60 * 60 * 1000;

    private final UserRepositoryPort users;
    private final SessionPort sessions;
    private final PasswordHasher hasher;
    private final TimePort time;
    private final long sessionTtlMillis;
    private final ActiveGameLookup activeGames;
    private final SecureRandom random = new SecureRandom();
    private final java.util.concurrent.atomic.AtomicLong userSequence =
            new java.util.concurrent.atomic.AtomicLong();

    public AuthApplicationService(
            UserRepositoryPort users, SessionPort sessions, PasswordHasher hasher, TimePort time) {
        this(users, sessions, hasher, time, DEFAULT_SESSION_TTL_MILLIS);
    }

    public AuthApplicationService(
            UserRepositoryPort users,
            SessionPort sessions,
            PasswordHasher hasher,
            TimePort time,
            long sessionTtlMillis) {
        this(users, sessions, hasher, time, sessionTtlMillis, ActiveGameLookup.NONE);
    }

    public AuthApplicationService(
            UserRepositoryPort users,
            SessionPort sessions,
            PasswordHasher hasher,
            TimePort time,
            long sessionTtlMillis,
            ActiveGameLookup activeGames) {
        this.users = Objects.requireNonNull(users, "users");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.hasher = Objects.requireNonNull(hasher, "hasher");
        this.time = Objects.requireNonNull(time, "time");
        this.sessionTtlMillis = sessionTtlMillis;
        this.activeGames = Objects.requireNonNull(activeGames, "activeGames");
    }

    /** 注册结果；响应里**不含**任何凭据。 */
    public record RegisteredUser(String userId, String username, String displayName) {}

    /** 登录结果；{@code sessionToken} 是唯一一次下发明文令牌的地方。 */
    public record LoggedIn(String userId, String username, String displayName,
                           String sessionToken, long expiresAt) {}

    /** 当前账号视图（api-spec.md §3.3）。 */
    public record CurrentUser(String userId, String username, String displayName,
                              String activeGameId, List<String> spectatingGameIds) {}

    /**
     * 注册（{@code POST /auth/register}）。
     *
     * <p>用户名唯一性（INV-U1）用**先查后写 + 同步块**保证。真实部署的最终保证是
     * SQLite 的 {@code ux_users_username} 唯一索引；这里同步是为了让内存实现同样正确，
     * 而不是依赖"客户端不会并发注册同名"。
     */
    public RegisteredUser register(String username, String password, String displayName) {
        validateCredentials(username, password);
        synchronized (this) {
            if (users.findByUsername(username).isPresent()) {
                throw GameException.Factory.usernameTaken(username);
            }
            String userId = nextUserId();
            User user = User.create(UserId.of(userId));
            long now = time.nowMillis();
            // 口令先散列再落库；明文口令只在这个方法栈上存在，用完即不再引用。
            String salt = hasher.newSalt();
            UserRepositoryPort.PasswordCredential credential = new UserRepositoryPort.PasswordCredential(
                    PasswordHasher.ALGORITHM, salt, hasher.hash(password, salt));
            DomainEvent event = user.register(
                    username, displayName == null || displayName.isBlank() ? username : displayName,
                    context(userId, "register:" + userId, now));
            users.save(user, List.of(event), credential);
            return new RegisteredUser(userId, username, user.displayName());
        }
    }

    /**
     * 登录（{@code POST /auth/login}）。
     *
     * <p>失败一律返回 {@code AUTH_REQUIRED} 的同一条消息：区分"用户不存在"与"口令错误"
     * 会让攻击者能枚举账号。真实用户遇到失败时也只需知道"用户名或口令不对"。
     */
    public LoggedIn login(String username, String password) {
        if (username == null || username.isBlank() || password == null) {
            throw GameException.Factory.authRequired("用户名或口令不正确");
        }
        Optional<User> found = users.findByUsername(username);
        if (found.isEmpty()) {
            throw GameException.Factory.authRequired("用户名或口令不正确");
        }
        User user = found.get();
        Optional<UserRepositoryPort.PasswordCredential> credential =
                users.credentialOf(user.userId());
        if (credential.isEmpty()) {
            // 账号存在但没有凭据：数据不一致，不能当成登录成功。
            throw GameException.Factory.authRequired("用户名或口令不正确");
        }
        String candidate = hasher.hash(password, credential.get().salt());
        if (!PasswordHasher.matches(candidate, credential.get().hash())) {
            throw GameException.Factory.authRequired("用户名或口令不正确");
        }

        String sessionId = newSecret();
        String secret = newSecret();
        long now = time.nowMillis();
        sessions.issue(user.userId().value(), sessionId, secret, now, sessionTtlMillis);
        DomainEvent event = user.login(
                sessionId, now, context(user.userId().value(), "login:" + sessionId, now));
        users.save(user, List.of(event), credential.get());

        return new LoggedIn(
                user.userId().value(),
                user.username(),
                user.displayName(),
                token(user.userId().value(), sessionId, secret),
                now + sessionTtlMillis);
    }

    /** 登出（{@code POST /auth/logout}）：撤销会话并记事件。重复登出是幂等的。 */
    public boolean logout(String token) {
        Optional<SessionPort.Verified> verified = sessions.verify(token);
        if (verified.isEmpty()) {
            // 令牌已失效：登出仍然"成功"（幂等）。让一个过期令牌的登出报错没有意义。
            return false;
        }
        String userId = verified.get().userId();
        String sessionId = verified.get().sessionId();
        sessions.revoke(token);
        Optional<User> user = users.findById(UserId.of(userId));
        if (user.isEmpty()) {
            return true;
        }
        long now = time.nowMillis();
        DomainEvent event = user.get().logout(
                sessionId, now, context(userId, "logout:" + sessionId, now));
        users.save(user.get(), List.of(event), null);
        return true;
    }

    /**
     * 当前账号（{@code GET /auth/me}）。
     *
     * <p>{@code activeGameId} 是该账号控制真人座位的进行中对局，没有则为 {@code null}。
     * {@code spectatingGameIds} 仍恒为空列表：观战订阅关系只存在于各个 WebSocket 连接里，
     * 尚无跨连接的查询；返回空而不是编一个值。
     */
    public CurrentUser currentUser(String token) {
        SessionPort.Verified verified = requireSession(token);
        User user = users.findById(UserId.of(verified.userId()))
                .orElseThrow(() -> GameException.Factory.userNotFound(verified.userId()));
        String activeGameId = activeGames.activeGameOf(user.userId().value()).orElse(null);
        return new CurrentUser(
                user.userId().value(), user.username(), user.displayName(), activeGameId, List.of());
    }

    /**
     * 校验令牌但不抛异常。
     *
     * <p>与 {@link #requireSession} 的分工：只读接口用它来决定"能否看到私有内容"，
     * 而"没有身份"是常态（匿名浏览），不该是一次错误；需要登录的接口用
     * {@code requireSession} 得到明确的拒绝。
     */
    public Optional<SessionPort.Verified> verifySession(String token) {
        return sessions.verify(token);
    }
    /**
     * 校验令牌；无效时抛 {@code AUTH_REQUIRED}。
     *
     * <p>必须是 {@code authRequired}（401）而不是 {@code forbidden}（403）：
     * 403 的语义是"凭据有效但你没这个权限"，而这里的情况是"根本没有有效凭据"。
     * 前端据此决定"跳登录页"还是"提示无权限"——用错会让已登出的用户看到"无权限"。
     */
    public SessionPort.Verified requireSession(String token) {
        return sessions.verify(token)
                .orElseThrow(() -> GameException.Factory.authRequired("会话无效或已过期，请重新登录"));
    }

    // ------------------------------------------------------------------

    private void validateCredentials(String username, String password) {
        if (username == null || username.isBlank()) {
            throw GameException.Factory.validation("用户名不能为空");
        }
        if (username.length() < 3 || username.length() > 32) {
            throw GameException.Factory.validation("用户名需为 3 至 32 个字符");
        }
        if (password == null || password.length() < 8) {
            throw GameException.Factory.validation("口令至少 8 个字符");
        }
    }

    /** 账号号：不透明字符串（api-spec.md §1）；形态 {@code u_<序列>} 只是生成约定。 */
    private String nextUserId() {
        String candidate;
        do {
            candidate = "u_" + userSequence.incrementAndGet();
        } while (users.findById(UserId.of(candidate)).isPresent());
        return candidate;
    }

    /** 生成随机段（会话 id 与密钥）。URL 安全 Base64，避免出现在 header 时被转义。 */
    private String newSecret() {
        byte[] bytes = new byte[18];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String token(String userId, String sessionId, String secret) {
        return userId + "." + sessionId + "." + secret;
    }

    private ActionContext context(String userId, String commandId, long now) {
        // randomSeed 固定为 0：账号事件不含随机决策（角色分配才有），
        // 用 0 而不是随机值可以让同一命令重放得到同一个事件 id。
        return new ActionContext(
                "ev_user_" + commandId, now, userId, commandId, commandId,
                ActionContext.NO_VERSION_CHECK, 0L);
    }
}
