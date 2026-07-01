package com.potato.peacehaven.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final AdminInterceptor adminInterceptor;
    private final CsrfInterceptor csrfInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 管理员鉴权拦截器
        registry.addInterceptor(adminInterceptor)
                .addPathPatterns("/admin/**")
                .excludePathPatterns("/admin/login");

        // CSRF Token 校验拦截器
        registry.addInterceptor(csrfInterceptor)
                .addPathPatterns("/admin/**", "/api/contest/**")
                .excludePathPatterns("/admin/login", "/api/auth/**");
    }
}
