package com.wolfmind.infrastructure.config;

import com.wolfmind.application.port.GameViewQueryPort;
import com.wolfmind.application.port.TimePort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 账号认证与会话用例装配。 */
@Configuration
public class IdentityConfiguration {

    /**
     * 认证用例。
     *
     * <p>散列迭代次数从配置读（默认 {@code wolfmind.auth.pbkdf2-iterations}）：
     * 生产需要慢散列，契约测试需要快——把它写死会让"测试跑得动"与"生产够安全"只能二选一。
     */
    @Bean
    com.wolfmind.application.user.AuthApplicationService authApplicationService(
            com.wolfmind.application.user.UserRepositoryPort users,
            com.wolfmind.application.user.SessionPort sessions,
            TimePort time,
            GameViewQueryPort gameViewQuery,
            org.springframework.core.env.Environment environment) {
        int iterations = environment.getProperty(
                "wolfmind.auth.pbkdf2-iterations",
                Integer.class,
                com.wolfmind.application.user.PasswordHasher.DEFAULT_ITERATIONS);
        return new com.wolfmind.application.user.AuthApplicationService(
                users,
                sessions,
                new com.wolfmind.application.user.PasswordHasher(iterations),
                time,
                com.wolfmind.application.user.AuthApplicationService.DEFAULT_SESSION_TTL_MILLIS,
                com.wolfmind.application.user.ActiveGameLookup.fromRunningGames(gameViewQuery));
    }
}
