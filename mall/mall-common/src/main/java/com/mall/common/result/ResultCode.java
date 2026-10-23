package com.mall.common.result;

/**
 * 全局统一状态码。
 *
 * <p>约定：{@code 0} 表示成功；{@code 4xx} 沿用 HTTP 语义用于鉴权/参数类错误；
 * {@code 1000} 起为业务错误；{@code 500} 为系统异常。</p>
 */
public enum ResultCode {

    /** 成功。 */
    SUCCESS(0, "success"),
    /** 参数错误。 */
    PARAM_INVALID(400, "参数错误"),
    /** 未登录或登录已过期。 */
    UNAUTHORIZED(401, "未登录或登录已过期"),
    /** 无权限。 */
    FORBIDDEN(403, "无权限"),
    /** 接口不存在。 */
    NOT_FOUND(404, "接口不存在"),
    /** 通用业务错误。 */
    BIZ_ERROR(1000, "业务错误"),
    /** 系统异常。 */
    SYSTEM_ERROR(500, "系统异常");

    private final int code;
    private final String message;

    ResultCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
