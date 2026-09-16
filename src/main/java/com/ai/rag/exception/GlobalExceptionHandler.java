package com.ai.rag.exception;

import com.ai.rag.model.dto.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.LocalDateTime;
import java.util.stream.Collectors;

/**
 * 全局异常处理器
 *
 * 统一把异常转成 {@link ApiError} JSON 体返回，避免控制器返回空 body 的 500。
 *
 * <p><b>关于框架级异常的显式映射（修复缺陷 D1）：</b>
 * 本类带有 {@code @ExceptionHandler(Exception.class)} 兜底，而 {@code @RestControllerAdvice}
 * 的优先级高于 {@code DefaultHandlerExceptionResolver}，因此 Spring MVC 自己的框架异常
 * （405 / 415 / 404 / 406 / 400 这一类）也会被兜底捞走，
 * 把**客户端错误降级成 500 服务端错误**。
 * 所以下面为每一种框架异常补了显式处理器，让状态码回到它本该有的值。
 *
 * <p>兜底分支的 {@code detail} 不再回填 {@code e.getMessage()}（修复缺陷 D2）：
 * 原始消息可能包含 SQL、连接串、类名等内部细节。
 * 完整堆栈仍会以 ERROR 级别落日志，排查不受影响。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 请求体参数校验失败（@Valid）
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException e, HttpServletRequest request) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return build(HttpStatus.BAD_REQUEST, "参数校验失败", detail, request);
    }

    /**
     * 请求体不可解析（JSON 格式错误等）
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException e, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "请求体格式错误", e.getMessage(), request);
    }

    /**
     * 缺少必填的查询参数 → 400
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> handleMissingParam(MissingServletRequestParameterException e,
                                                       HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "缺少必填参数",
                "缺少查询参数 " + e.getParameterName(), request);
    }

    /**
     * 参数类型不匹配（路径变量 / 查询参数） → 400
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException e,
                                                       HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "参数类型错误",
                "参数 " + e.getName() + " 的取值无法转换", request);
    }

    /**
     * 请求方法不支持（如 GET 打到只支持 POST 的端点） → 405
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> handleMethodNotSupported(HttpRequestMethodNotSupportedException e,
                                                             HttpServletRequest request) {
        String supported = e.getSupportedHttpMethods() == null ? "无"
                : e.getSupportedHttpMethods().stream()
                        .map(Object::toString).collect(Collectors.joining(", "));
        return build(HttpStatus.METHOD_NOT_ALLOWED, "请求方法不支持",
                "该端点支持的方法：" + supported, request);
    }

    /**
     * 请求的 Content-Type 不受支持 → 415
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiError> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException e,
                                                                HttpServletRequest request) {
        return build(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "请求体类型不支持",
                "不支持的 Content-Type：" + e.getContentType(), request);
    }

    /**
     * 客户端 Accept 声明的类型服务端无法产出 → 406
     */
    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ApiError> handleMediaTypeNotAcceptable(HttpMediaTypeNotAcceptableException e,
                                                                 HttpServletRequest request) {
        return build(HttpStatus.NOT_ACCEPTABLE, "无法产出请求所需的响应类型",
                "本服务只提供 application/json 与 text/event-stream", request);
    }

    /**
     * 路径不存在 → 404
     *
     * <p>Spring Boot 3.2+ 对未匹配的路径抛 {@link NoResourceFoundException}
     * （由静态资源处理器兜底而来），3.2 以下通常是 {@link NoHandlerFoundException}，两者都接住。
     * detail 不回填原始消息，避免泄漏「No static resource ...」这类内部措辞（缺陷 D2）。
     */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ApiError> handleNotFound(Exception e, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "请求的资源不存在",
                "路径 " + request.getRequestURI() + " 未匹配到任何端点", request);
    }

    /**
     * 兜底异常
     *
     * <p>走到这里说明是真正的服务端故障，固定文案 + 落日志，
     * 不把 {@code e.getMessage()} 回给客户端（缺陷 D2）。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleGeneric(Exception e, HttpServletRequest request) {
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), e);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "服务器内部错误",
                "服务处理请求时发生内部错误，请稍后重试", request);
    }

    private ResponseEntity<ApiError> build(HttpStatus status, String message, String detail, HttpServletRequest request) {
        ApiError error = new ApiError(
                LocalDateTime.now(),
                status.value(),
                message,
                detail,
                request.getRequestURI()
        );
        return ResponseEntity.status(status).body(error);
    }
}
