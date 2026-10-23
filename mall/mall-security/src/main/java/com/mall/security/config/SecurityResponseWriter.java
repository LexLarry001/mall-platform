package com.mall.security.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.common.result.R;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;

import java.io.IOException;

/**
 * 安全链拒绝请求时写出统一 {@link R} JSON（401/403）。
 *
 * <p>安全链工作在 DispatcherServlet 之前，异常不会被 {@code @RestControllerAdvice} 捕获，
 * 因此需要在此自行序列化响应体，保证前端收到的错误结构与业务接口一致。</p>
 */
public final class SecurityResponseWriter {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private SecurityResponseWriter() {
    }

    /** 写出指定 HTTP 状态码与统一响应体。 */
    public static void write(HttpServletResponse response, int httpStatus, R<?> body) throws IOException {
        response.setStatus(httpStatus);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        OBJECT_MAPPER.writeValue(response.getWriter(), body);
    }
}
