package com.wolfmind.interfaces.rest;

/** 未认证（401）。缺少凭据或凭据无效时抛出。 */
class UnauthorizedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    UnauthorizedException(String message) {
        super(message);
    }
}