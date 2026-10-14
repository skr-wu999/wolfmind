package com.wolfmind;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * WolfMind 后端启动类（AGENTS.md 3 基线：Spring Boot 3）。
 *
 * <p>放在 com.wolfmind 根包，组件扫描因此覆盖 application / infrastructure / interfaces 三层，
 * 领域类不声明 Spring 组件（DEP-1、DEP-2），只作为普通 Java 库被应用层调用。
 *
 * <p>依赖装配按职责放在 {@code infrastructure.config}，Redis 存储装配位于
 * {@code infrastructure.redis.RedisStorageConfiguration}；业务类只依赖端口。
 */
@SpringBootApplication
public class WolfMindApplication {

    public static void main(String[] args) {
        SpringApplication.run(WolfMindApplication.class, args);
    }
}
