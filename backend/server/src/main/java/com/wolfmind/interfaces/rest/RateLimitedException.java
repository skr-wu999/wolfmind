package com.wolfmind.interfaces.rest;

/**
 * 超过频率或并发限制（429 {@code RATE_LIMITED}，api-spec.md §1.2）。
 *
 * <p>它替换的是"把并发上限当成领域规则"的错误做法：实验室的并发上限来自服务端资源与配置，
 * 与游戏规则无关，因此不该借用任何 {@code DomainErrorCode}。
 * {@code Errors.Code.RATE_LIMITED} 已经是接口层的码，它在这里第一次被真正抛出。
 */
class RateLimitedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    RateLimitedException(String message) {
        super(message);
    }
}
