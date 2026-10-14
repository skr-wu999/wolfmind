package com.wolfmind.infrastructure.memory;

import com.wolfmind.application.port.EventStorePort;
import com.wolfmind.application.user.UserRepositoryPort;
import com.wolfmind.domain.game.event.DomainEvent;
import com.wolfmind.domain.game.event.EventEnvelope;
import com.wolfmind.domain.user.model.User;
import com.wolfmind.domain.user.model.UserId;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 账号仓储的内存实现。
 *
 * <p>账号事实走 {@link EventStorePort} 的 {@code user:{id}:events} 流；
 * **凭据单独放在一张内存表里**——这条分界是整个认证设计的关键（见 {@link UserRepositoryPort}）：
 * 事件流是长期归档，口令散列与令牌不能进去。
 *
 * <p>用户名 → userId 的索引也是单独维护的：它是查询加速，不是事实来源，
 * 因此重建（索引丢失）只会让查询变慢，不会让账号消失。
 */
public final class InMemoryUserRepository implements UserRepositoryPort {

    public static final String STREAM_PREFIX = "user:";

    private final EventStorePort eventStore;
    private final Map<String, String> userIdByUsername = new ConcurrentHashMap<>();
    private final Map<String, PasswordCredential> credentials = new ConcurrentHashMap<>();

    public InMemoryUserRepository(EventStorePort eventStore) {
        this.eventStore = eventStore;
    }

    public static String streamKey(String userId) {
        return STREAM_PREFIX + userId;
    }

    @Override
    public Optional<User> findByUsername(String username) {
        String userId = userIdByUsername.get(username);
        return userId == null ? Optional.empty() : findById(UserId.of(userId));
    }

    @Override
    public Optional<User> findById(UserId userId) {
        String stream = streamKey(userId.value());
        if (!eventStore.exists(stream)) {
            return Optional.empty();
        }
        User user = User.create(userId);
        for (EventEnvelope envelope : eventStore.read(stream, 1, Integer.MAX_VALUE)) {
            user.replay(com.wolfmind.domain.user.event.UserEventCodec.decode(envelope));
        }
        return Optional.of(user);
    }

    @Override
    public void save(User user, List<DomainEvent> events, PasswordCredential credential) {
        // 凭据先写：它不产生事件，若写到一半失败（内存实现在这里不会），
        // 也不会留下"有账号、没凭据"因而永远无法登录的账号。
        if (credential != null) {
            credentials.put(user.userId().value(), credential);
        }
        userIdByUsername.put(user.username(), user.userId().value());
        if (events == null || events.isEmpty()) {
            return;
        }
        String stream = streamKey(user.userId().value());
        int version = eventStore.currentVersion(stream);
        List<EventEnvelope> envelopes = new java.util.ArrayList<>(events.size());
        for (DomainEvent event : events) {
            envelopes.add(EventEnvelope.of(event, stream, ++version));
        }
        eventStore.append(stream, eventStore.currentVersion(stream), envelopes);
    }

    @Override
    public Optional<PasswordCredential> credentialOf(UserId userId) {
        return Optional.ofNullable(credentials.get(userId.value()));
    }

    /** 已注册账号数；供诊断与测试。 */
    public int size() {
        return userIdByUsername.size();
    }
}