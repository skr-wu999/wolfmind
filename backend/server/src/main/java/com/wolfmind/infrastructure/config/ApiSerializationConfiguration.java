package com.wolfmind.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** REST 与 WebSocket 共用的序列化契约装配。 */
@Configuration
public class ApiSerializationConfiguration {

    /**
     * 为 {@code Participant} 注册自定义序列化器，使 {@code controller} 按 api-spec.md 5.2
     * 写成嵌套对象 {@code {type, refId}}，而不是领域 record 的扁平组件名。
     *
     * <p>为什么在这里注册而不是给领域 record 加注解：engine 不得引入 Jackson（DEP-2/DEP-5），
     * "线上 JSON 形状"是接口层职责。集中注册也保证 WebSocket 推送与 REST 视图用同一份形状，
     * 不会两条通道各写一遍。
     */
    @Bean
    org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer
            participantContractShape() {
        return builder -> {
            com.fasterxml.jackson.databind.module.SimpleModule module =
                    new com.fasterxml.jackson.databind.module.SimpleModule("wolfmind-contract-shape");
            module.addSerializer(
                    com.wolfmind.domain.game.view.GameViewSnapshot.Participant.class,
                    new com.wolfmind.interfaces.rest.ParticipantSerializer());
            builder.modulesToInstall(module);
        };
    }
}
