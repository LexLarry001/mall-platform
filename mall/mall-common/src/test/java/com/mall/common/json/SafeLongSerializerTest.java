package com.mall.common.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 雪花 ID 前端精度保护：超出 2^53-1 的 Long 输出 JSON 字符串，安全范围内保持数字。
 */
class SafeLongSerializerTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new SimpleModule().addSerializer(Long.class, new SafeLongSerializer()));

    @Test
    void 超大雪花ID序列化为字符串() throws Exception {
        Long snowflake = 2112586603225444353L;

        String json = mapper.writeValueAsString(snowflake);

        assertThat(json).isEqualTo("\"2112586603225444353\"");
    }

    @Test
    void 安全范围内的Long保持数字() throws Exception {
        assertThat(mapper.writeValueAsString(101L)).isEqualTo("101");
        assertThat(mapper.writeValueAsString(9007199254740991L)).isEqualTo("9007199254740991");
        assertThat(mapper.writeValueAsString(-5L)).isEqualTo("-5");
    }

    @Test
    void customizer不影响LocalDateTime等其他类型序列化() throws Exception {
        // 模拟 Boot 装配：先注册 JavaTimeModule，再应用 customizer（防 modules() 覆盖回归）
        org.springframework.http.converter.json.Jackson2ObjectMapperBuilder builder =
                new org.springframework.http.converter.json.Jackson2ObjectMapperBuilder()
                        .modules(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        new SafeLongSerializer.SafeLongJacksonConfig().safeLongCustomizer().customize(builder);
        ObjectMapper om = builder.build();

        record Demo(Long id, java.time.LocalDateTime createTime) {
        }

        String json = om.writeValueAsString(new Demo(2112586603225444353L, java.time.LocalDateTime.of(2026, 10, 21, 1, 2, 3)));

        assertThat(json).contains("\"id\":\"2112586603225444353\"");
        assertThat(json).contains("2026");
    }
}
