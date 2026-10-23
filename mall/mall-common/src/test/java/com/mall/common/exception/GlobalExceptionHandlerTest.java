package com.mall.common.exception;

import com.mall.common.result.R;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 全局异常处理器测试：业务异常映射为统一 R，参数校验失败映射为 400。
 */
class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void bizException_should_map_to_biz_code() throws Exception {
        mockMvc.perform(get("/t/biz"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.message").value("库存不足"));
    }

    @Test
    void invalid_param_should_map_to_400() throws Exception {
        mockMvc.perform(get("/t/param").param("name", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void access_denied_should_map_to_http_403() throws Exception {
        mockMvc.perform(get("/t/denied"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void no_resource_found_should_map_to_404_not_system_error() {
        ResponseEntity<R<Void>> response = new GlobalExceptionHandler()
                .handleNoResourceFound(new NoResourceFoundException(HttpMethod.GET, "/api/nope"));

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(404);
    }

    @RestController
    static class ProbeController {

        @GetMapping("/t/biz")
        public R<Void> biz() {
            throw new BizException("库存不足");
        }

        @GetMapping("/t/denied")
        public R<Void> denied() {
            throw new AccessDeniedException("no authority");
        }

        @GetMapping("/t/param")
        public R<String> param(@Valid ProbeParam param) {
            return R.ok(param.getName());
        }
    }

    static class ProbeParam {

        @NotBlank(message = "name 不能为空")
        private String name;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }
}
