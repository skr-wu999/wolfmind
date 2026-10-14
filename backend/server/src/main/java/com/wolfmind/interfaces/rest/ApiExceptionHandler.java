package com.wolfmind.interfaces.rest;

import com.wolfmind.domain.game.model.GameException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;
import java.util.UUID;

/**
 * 全局异常映射：把异常收敛成 api-spec.md 1.1 的错误体。
 *
 * <p>集中在一处而不是各控制器 try/catch：错误契约是一份，实现也只能有一处；
 * 分散处理迟早出现同一个码在不同接口返回不同状态或不同字段。
 *
 * <p>每个响应都带 X-Trace-Id 响应头与 body 里的 traceId，保证"任何一次失败都能对上日志与事件流"。
 */
@RestControllerAdvice
class ApiExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(GameException.class)
    ResponseEntity<Errors.ApiError> onDomainRejection(GameException error, HttpServletRequest request) {
        return respond(Errors.of(error, traceId(request)));
    }

    @ExceptionHandler(RequestShapeException.class)
    ResponseEntity<Errors.ApiError> onRequestShape(RequestShapeException error, HttpServletRequest request) {
        return respond(Errors.badRequest(error.getMessage(), traceId(request), List.of()));
    }

    /**
     * 查询参数类型转换失败（例如 page=abc）。
     *
     * <p>必须显式处理：Spring 的类型转换失败发生在参数解析阶段，不经过控制器的请求体解析，
     * 因此不会被 RequestShapeException 覆盖；不处理就会掉进兜底分支变成 500，
     * 让"客户端传错了参数"看起来像"服务端坏了"。
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<Errors.ApiError> onTypeMismatch(
            MethodArgumentTypeMismatchException error, HttpServletRequest request) {
        return respond(Errors.badRequest(
                "参数 " + error.getName() + " 的值不合法：" + error.getValue(),
                traceId(request),
                List.of()));
    }
    @ExceptionHandler(NotWiredException.class)
    ResponseEntity<Errors.ApiError> onNotWired(NotWiredException error, HttpServletRequest request) {
        return respond(Errors.of(Errors.Code.NOT_IMPLEMENTED, error.getMessage(), traceId(request), List.of()));
    }
    /**
     * 请求体不可读（不是合法 JSON、编码错误等）。
     *
     * <p>必须在兜底分支之前显式处理：Spring 的反序列化失败发生在进入控制器方法**之前**，
     * 不处理就会变成 500，把"客户端发错了"显示成"服务端坏了"。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Errors.ApiError> onUnreadableBody(
            HttpMessageNotReadableException error, HttpServletRequest request) {
        return respond(Errors.badRequest(
                "请求体不是合法 JSON 或与预期结构不符", traceId(request), List.of()));
    }

    /**
     * 路由不存在（Spring 6.1 起由 {@code NoResourceFoundException} 表达）。
     *
     * <p>不映射就会掉进兜底 500。404 与 500 的区别对客户端很重要：
     * 前者说明调错了地址，后者说明服务端有问题。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<Errors.ApiError> onNoResource(
            NoResourceFoundException error, HttpServletRequest request) {
        return respond(Errors.of(Errors.Code.NOT_FOUND,
                "接口不存在：" + request.getMethod() + " " + request.getRequestURI(),
                traceId(request), List.of()));
    }
    @ExceptionHandler(UnauthorizedException.class)
    ResponseEntity<Errors.ApiError> onUnauthorized(UnauthorizedException error, HttpServletRequest request) {
        return respond(Errors.of(Errors.Code.AUTH_REQUIRED, error.getMessage(), traceId(request), List.of()));
    }

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<Errors.ApiError> onNotFound(NotFoundException error, HttpServletRequest request) {
        return respond(Errors.of(Errors.Code.NOT_FOUND, error.getMessage(), traceId(request), List.of()));
    }

    /**
     * 频率或并发限制（429）。
     *
     * <p>响应带 {@code Retry-After}（api-spec.md §12）：没有它客户端只能盲目重试，
     * 而实验室批次的重试代价是真实的模型额度。
     */
    @ExceptionHandler(RateLimitedException.class)
    ResponseEntity<Errors.ApiError> onRateLimited(RateLimitedException error, HttpServletRequest request) {
        Errors.ApiError body = Errors.of(Errors.Code.RATE_LIMITED, error.getMessage(), traceId(request), List.of());
        return ResponseEntity.status(body.status())
                .header("X-Trace-Id", body.traceId())
                .header("Retry-After", "5")
                .body(body);
    }

    @ExceptionHandler(RuntimeException.class)
    ResponseEntity<Errors.ApiError> onUnexpected(RuntimeException error, HttpServletRequest request) {
        // 响应体刻意不含堆栈（既防信息泄漏，也避免客户端以为能据此处理），
        // 但服务端必须留下日志：排查线上问题时唯一线索就是这一行。
        LOG.error("未预期的接口层异常：{} {}", request.getMethod(), request.getRequestURI(), error);
        return respond(Errors.internal(traceId(request)));
    }

    private static ResponseEntity<Errors.ApiError> respond(Errors.ApiError error) {
        return ResponseEntity.status(error.status())
                .header("X-Trace-Id", error.traceId())
                .body(error);
    }

    private static String traceId(HttpServletRequest request) {
        String existing = request.getHeader("X-Trace-Id");
        if (existing != null && !existing.isBlank()) {
            return existing;
        }
        return "cor_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }
}