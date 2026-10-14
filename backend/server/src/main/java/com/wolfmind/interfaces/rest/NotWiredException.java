package com.wolfmind.interfaces.rest;

/**
 * 接口已定义但依赖未装配（501）。
 *
 * <p>与 404 的区别：路由**存在**，只是这个部署没有接上对应的应用服务。客户端据此知道
 * "等运维接上就好"，而不是"这个接口不存在、该改代码"。
 */
class NotWiredException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    NotWiredException(String message) {
        super(message);
    }
}