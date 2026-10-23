package com.mall.common.exception;

import com.mall.common.result.R;
import com.mall.common.result.ResultCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理器：保证任何异常出口都是统一 {@link R} 结构，不向客户端泄漏堆栈。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 业务异常：按异常自带错误码返回，记 WARN（属预期内失败）。 */
    @ExceptionHandler(BizException.class)
    public R<Void> handleBizException(BizException ex) {
        log.warn("业务异常: code={}, message={}", ex.getCode(), ex.getMessage());
        return R.fail(ex.getCode(), ex.getMessage());
    }

    /** 参数校验失败：取首个字段错误消息，返回 400。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public R<Void> handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(FieldError::getDefaultMessage)
                .orElse(ResultCode.PARAM_INVALID.getMessage());
        log.warn("参数校验失败: {}", message);
        return R.fail(ResultCode.PARAM_INVALID.getCode(), message);
    }

    /**
     * 权限不足：返回真实 HTTP 403。
     *
     * <p>方法级鉴权（{@code @PreAuthorize}）失败时抛出的 {@code AccessDeniedException} 由 MVC 层
     * 抛出、走本处理器，而非过滤链的 {@code accessDeniedHandler}；两条路径统一用真实 HTTP 403，
     * 避免"过滤链 403"与"方法级 200+code403"两种形态并存。</p>
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<R<Void>> handleAccessDenied(AccessDeniedException ex) {
        log.warn("权限不足: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(R.fail(ResultCode.FORBIDDEN));
    }

    /**
     * 路由不存在：返回 404。
     *
     * <p>Spring 6.1 起未映射路径不再抛 {@code NoHandlerFoundException}，而是抛
     * {@link NoResourceFoundException}；若落到兜底分支会被误报成"系统异常 500"，
     * 掩盖了拼错接口路径这类明显问题。</p>
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<R<Void>> handleNoResourceFound(NoResourceFoundException ex) {
        log.warn("接口不存在: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(R.fail(ResultCode.NOT_FOUND));
    }

    /**
     * 上传超限：multipart 解析在 controller 之前抛出该异常，不会进入 FileService 的
     * 服务级校验；统一映射为 400，与 RF6"超 5MB → 400"口径一致。
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public R<Void> handleMaxUploadSizeExceeded(MaxUploadSizeExceededException ex) {
        log.warn("上传文件超限: {}", ex.getMessage());
        return R.fail(ResultCode.PARAM_INVALID.getCode(), "文件大小不能超过 5MB");
    }

    /** 兜底异常：记 ERROR 并返回系统异常，避免把内部细节暴露给客户端。 */
    @ExceptionHandler(Exception.class)
    public R<Void> handleException(Exception ex) {
        log.error("系统异常", ex);
        return R.fail(ResultCode.SYSTEM_ERROR);
    }
}
