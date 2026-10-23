package com.mall.common.exception;

import com.mall.common.result.ResultCode;

/**
 * 业务异常。所有可预期的业务失败都抛该异常，由 {@link GlobalExceptionHandler} 统一转换为 {@code R}。
 */
public class BizException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int code;

    /** 使用默认业务错误码 {@link ResultCode#BIZ_ERROR}。 */
    public BizException(String message) {
        super(message);
        this.code = ResultCode.BIZ_ERROR.getCode();
    }

    /** 自定义业务错误码。 */
    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
