package com.wolfmind.infrastructure.sqlite;

import com.wolfmind.application.port.EventStorePort;
import com.wolfmind.application.user.UserRepositoryPort;
import com.wolfmind.domain.game.event.DomainEvent;
import com.wolfmind.domain.game.event.EventEnvelope;
import com.wolfmind.domain.user.event.UserEventCodec;
import com.wolfmind.domain.user.model.User;
import com.wolfmind.domain.user.model.UserId;
import com.wolfmind.infrastructure.sqlite.SqliteArchiveRepository.ArchiveException;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 账号仓储：账号事实走 {@code user:{id}:events} 事件流，凭据与用户名唯一性落 SQLite {@code users} 表
 * （database-schema.md §3.2，{@link UserRepositoryPort} 的分界）。
 *
 * <p>{@code password_hash} 列存 {@code {algorithm}${hash}}：表里没有算法列，而换算法时必须能识别旧凭据。
 * 散列是 Base64，不含 {@code $}。
 *
 * <p>孤儿行：事件流丢失（Redis 数据丢失或过期）而 SQLite 行仍在时，账号无法重建。注册同名账号时
 * 删除这类孤儿行，否则 {@code ux_users_username} 会让这个用户名永远不可再用。
 */
public final class SqliteUserRepository implements UserRepositoryPort, AutoCloseable {

    private static final String STREAM_PREFIX = "user:";

    private final EventStorePort eventStore;
    private final Connection connection;

    private SqliteUserRepository(EventStorePort eventStore, Connection connection) {
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore");
        this.connection = connection;
    }

    /** 打开数据库并应用结构脚本（幂等）。 */
    public static SqliteUserRepository open(String jdbcUrl, EventStorePort eventStore) {
        try {
            Connection connection = SqliteSupport.open(jdbcUrl);
            SqliteSupport.applySchema(connection);
            return new SqliteUserRepository(eventStore, connection);
        } catch (SQLException | IOException error) {
            throw new ArchiveException("打开账号库失败：" + error.getMessage(), error);
        }
    }

    @Override
    public synchronized Optional<User> findByUsername(String username) {
        String sql = "SELECT user_id FROM users WHERE username = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, username);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                return findById(UserId.of(rows.getString(1)));
            }
        } catch (SQLException error) {
            throw new ArchiveException("按用户名查询账号失败：" + error.getMessage(), error);
        }
    }

    @Override
    public Optional<User> findById(UserId userId) {
        String stream = STREAM_PREFIX + userId.value();
        if (!eventStore.exists(stream)) {
            return Optional.empty();
        }
        User user = User.create(userId);
        for (EventEnvelope envelope : eventStore.read(stream, 1, Integer.MAX_VALUE)) {
            user.replay(UserEventCodec.decode(envelope));
        }
        return Optional.of(user);
    }

    @Override
    public synchronized void save(User user, List<DomainEvent> events, PasswordCredential credential) {
        if (credential != null) {
            try {
                deleteOrphanWithSameUsername(user);
                upsert(user, credential);
            } catch (SQLException error) {
                throw new ArchiveException("写入账号凭据失败：" + error.getMessage(), error);
            }
        }
        if (events == null || events.isEmpty()) {
            return;
        }
        String stream = STREAM_PREFIX + user.userId().value();
        int current = eventStore.currentVersion(stream);
        int version = current;
        List<EventEnvelope> envelopes = new ArrayList<>(events.size());
        for (DomainEvent event : events) {
            envelopes.add(EventEnvelope.of(event, stream, ++version));
        }
        eventStore.append(stream, current, envelopes);
    }

    @Override
    public synchronized Optional<PasswordCredential> credentialOf(UserId userId) {
        String sql = "SELECT password_hash, password_salt FROM users WHERE user_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, userId.value());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                String stored = rows.getString(1);
                int separator = stored.indexOf('$');
                if (separator <= 0) {
                    throw new ArchiveException("users.password_hash 缺少算法前缀：" + userId.value(), null);
                }
                return Optional.of(new PasswordCredential(
                        stored.substring(0, separator), rows.getString(2), stored.substring(separator + 1)));
            }
        } catch (SQLException error) {
            throw new ArchiveException("读取账号凭据失败：" + error.getMessage(), error);
        }
    }

    private void deleteOrphanWithSameUsername(User user) throws SQLException {
        String select = "SELECT user_id FROM users WHERE username = ? AND user_id <> ?";
        List<String> orphans = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(select)) {
            statement.setString(1, user.username());
            statement.setString(2, user.userId().value());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String other = rows.getString(1);
                    if (!eventStore.exists(STREAM_PREFIX + other)) {
                        orphans.add(other);
                    }
                }
            }
        }
        for (String orphan : orphans) {
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM users WHERE user_id = ?")) {
                statement.setString(1, orphan);
                statement.executeUpdate();
            }
        }
    }

    private void upsert(User user, PasswordCredential credential) throws SQLException {
        String sql = """
                INSERT INTO users (user_id, username, display_name, password_hash, password_salt, status,
                                   created_at, last_login_at, last_logout_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(user_id) DO UPDATE SET
                  username = excluded.username,
                  display_name = excluded.display_name,
                  password_hash = excluded.password_hash,
                  password_salt = excluded.password_salt,
                  status = excluded.status,
                  last_login_at = excluded.last_login_at,
                  last_logout_at = excluded.last_logout_at
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, user.userId().value());
            statement.setString(2, user.username());
            statement.setString(3, user.displayName());
            statement.setString(4, credential.algorithm() + "$" + credential.hash());
            statement.setString(5, credential.salt());
            statement.setString(6, user.status().name());
            statement.setLong(7, user.createdAt() == null ? 0L : user.createdAt());
            setNullableLong(statement, 8, user.lastLoginAt());
            setNullableLong(statement, 9, user.lastLogoutAt());
            statement.executeUpdate();
        }
    }

    private static void setNullableLong(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.INTEGER);
        } else {
            statement.setLong(index, value);
        }
    }

    @Override
    public synchronized void close() {
        try {
            connection.close();
        } catch (SQLException error) {
            throw new ArchiveException("关闭账号库失败：" + error.getMessage(), error);
        }
    }
}
