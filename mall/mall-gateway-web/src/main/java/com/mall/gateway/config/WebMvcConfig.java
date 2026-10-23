package com.mall.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Paths;

/**
 * 静态资源映射：{@code /static/**} → 本地磁盘上传目录（默认 {@code uploads/}）。
 *
 * <p>上传接口（mall-product 的 FileService）把文件写入 {@code uploads/yyyyMM/} 并返回
 * {@code /static/yyyyMM/xxx.jpg} 形式的 url，此处配置使该 url 匿名可访问（安全链放行清单见
 * mall-security 的 SecurityConfig）。</p>
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final String uploadDir;

    public WebMvcConfig(@Value("${mall.file.upload-dir:uploads}") String uploadDir) {
        this.uploadDir = uploadDir;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // toUri() 仅当目录已存在才带尾 "/"，启动时目录可能尚未创建，须显式补齐，
        // 否则资源位置被当作文件前缀，/static/** 永远解析不到（404）
        String location = Paths.get(uploadDir).toAbsolutePath().normalize().toUri().toString();
        if (!location.endsWith("/")) {
            location = location + "/";
        }
        registry.addResourceHandler("/static/**").addResourceLocations(location);
    }
}
