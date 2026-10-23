package com.mall.gateway.file;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mall.common.exception.GlobalExceptionHandler;
import com.mall.gateway.config.WebMvcConfig;
import com.mall.product.mapper.FileRecordMapper;
import com.mall.security.jwt.JwtTokenProvider;
import com.mall.security.token.AccountType;
import com.mall.security.token.LoginUser;
import com.mall.security.token.TokenStore;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 文件上传链路安全测试（真实安全过滤链 + 真实 FileController/FileService，
 * TokenStore 与 FileRecordMapper mock，磁盘落 target/test-uploads）。
 *
 * <p>钉死 plan Review Focus 6 的 HTTP 侧行为：</p>
 * <ul>
 *   <li>未登录上传 → HTTP 401 且 {@code code=401}</li>
 *   <li>登录后传非图片 → HTTP 200 且 {@code code=400}</li>
 *   <li>合法 PNG → {@code code=0} 且返回 {@code /static/} 前缀 url</li>
 *   <li>{@code /static/**} 匿名可访问（不被安全链拦成 401，资源不存在时为 404）</li>
 * </ul>
 */
@SpringBootTest(
        classes = FileUploadSecurityTest.TestApp.class,
        properties = {
                "mall.jwt.secret=" + FileUploadSecurityTest.SECRET,
                "mall.jwt.access-expire-hours=12",
                "mall.jwt.refresh-expire-days=7",
                "mall.file.upload-dir=target/test-uploads",
                "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
                "spring.datasource.url=jdbc:mysql://localhost:3306/mall?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true",
                "spring.datasource.username=root",
                "spring.datasource.password=123456"
        })
@AutoConfigureMockMvc
class FileUploadSecurityTest {

    static final String SECRET = "file-upload-test-secret-0123456789abcdefghij";

    private static final byte[] PNG_BYTES = {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52
    };

    @SpringBootApplication(scanBasePackages = {"com.mall.product", "com.mall.security", "com.mall.ai"})
    @MapperScan("com.mall.product.mapper")
    @Import({GlobalExceptionHandler.class, WebMvcConfig.class})
    @EnableMongoRepositories(basePackages = "com.mall")
    static class TestApp {
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @MockBean
    private TokenStore tokenStore;

    @MockBean
    private FileRecordMapper fileRecordMapper;

    /** 未登录调上传接口 → 401。 */
    @Test
    void anonymous_upload_should_return_401() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "a.png", "image/png", PNG_BYTES);

        mockMvc.perform(multipart("/api/file/upload").file(file))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    /** 登录后传非图片 → 业务错误 400（经 GlobalExceptionHandler 统一包装）。 */
    @Test
    void logged_in_non_image_upload_should_return_400() throws Exception {
        String token = merchantToken();
        MockMultipartFile file = new MockMultipartFile("file", "a.jpg", "text/plain", "hello".getBytes());

        mockMvc.perform(multipart("/api/file/upload").file(file)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    /** 合法 PNG 上传 → 返回 /static/ 前缀 url，且该 url 匿名可直接访问到文件内容。 */
    @Test
    void valid_png_upload_should_return_static_url() throws Exception {
        String token = merchantToken();
        MockMultipartFile file = new MockMultipartFile("file", "photo.png", "image/png", PNG_BYTES);

        String response = mockMvc.perform(multipart("/api/file/upload").file(file)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.url").value(org.hamcrest.Matchers.startsWith("/static/")))
                .andReturn().getResponse().getContentAsString();

        String url = com.jayway.jsonpath.JsonPath.read(response, "$.data.url");
        mockMvc.perform(get(url))
                .andExpect(status().isOk());
    }

    /** /static/** 匿名放行：资源不存在应 404（若被安全链拦截则是 401）。 */
    @Test
    void static_resource_should_be_anonymous_accessible() throws Exception {
        mockMvc.perform(get("/static/202610/not-exists.png"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    private String merchantToken() {
        String token = jwtTokenProvider.createToken(7L, AccountType.MERCHANT, 99L);
        LoginUser user = new LoginUser(7L, AccountType.MERCHANT, 99L, null);
        when(tokenStore.get(token)).thenReturn(user);
        when(tokenStore.isCurrent(token, AccountType.MERCHANT, 7L)).thenReturn(true);
        return token;
    }
}
