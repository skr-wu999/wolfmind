package com.wolfmind.interfaces.rest;

/**
 * 请求形态错误（400）。
 *
 * <p>它由接口层抛出，不经过领域：请求体不是合法 JSON、缺必填字段、参数越界，
 * 都与"领域拒绝了这次行动"不是一回事。分开表达才能让客户端知道该改请求还是改动作。
 */
class RequestShapeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    RequestShapeException(String message) {
        super(message);
    }
}