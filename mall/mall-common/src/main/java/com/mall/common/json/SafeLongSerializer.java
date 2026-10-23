package com.mall.common.json;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

/**
 * 雪花 ID 前端精度保护：Long 值超出 JS Number.MAX_SAFE_INTEGER(2^53-1) 时序列化为 JSON 字符串，
 * 安全范围内仍输出数字（分页 total/size、类目小 id、状态位等不受影响，前端类型无需调整）。
 */
public class SafeLongSerializer extends JsonSerializer<Long> {

    private static final long MAX_SAFE_INTEGER = 9007199254740991L;

    @Override
    public void serialize(Long value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        if (value == null) {
            gen.writeNull();
        } else if (Math.abs(value) > MAX_SAFE_INTEGER) {
            gen.writeString(value.toString());
        } else {
            gen.writeNumber(value);
        }
    }

    @Configuration
    static class SafeLongJacksonConfig {

        @Bean
        Jackson2ObjectMapperBuilderCustomizer safeLongCustomizer() {
            // serializerByType 不触碰模块注册（modules() 会覆盖 Boot 自动注册的 JavaTimeModule，已踩坑）
            SafeLongSerializer serializer = new SafeLongSerializer();
            return builder -> builder
                    .serializerByType(Long.class, serializer)
                    .serializerByType(Long.TYPE, serializer);
        }
    }
}
