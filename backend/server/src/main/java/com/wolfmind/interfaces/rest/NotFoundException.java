package com.wolfmind.interfaces.rest;

/**
 * 资源不存在（404）。
 *
 * <p>它存在的理由是一条具体的边界：**"东西不存在"与"你没权限"必须可区分**。
 * 两者都用 403 表达时，客户端只能提示"无权访问"，而用户真正需要的是"回到列表页重选"。
 */
class NotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    NotFoundException(String message) {
        super(message);
    }
}
