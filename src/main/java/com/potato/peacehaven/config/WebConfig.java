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
    private final PageVisitInterceptor pageVisitInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 管理员鉴权拦截器
        registry.addInterceptor(adminInterceptor)
                .addPathPatterns("/admin", "/admin/**")
                .excludePathPatterns("/admin/login");

        // CSRF Token 校验拦截器
        registry.addInterceptor(csrfInterceptor)
                .addPathPatterns("/admin", "/admin/**", "/api/contest/**")
                .excludePathPatterns("/admin/login", "/api/auth/**");

        // 页面访问记录拦截器（仅 GET 页面请求）
        registry.addInterceptor(pageVisitInterceptor)
                .addPathPatterns("/", "/activities", "/activities/**", "/combat-roster",
                        "/agreement", "/admin", "/admin/**");
    }
}
