package com.potato.peacehaven.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 全局安全响应头过滤器
 * 为所有响应添加安全相关的 HTTP 头
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SecurityHeadersFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        if (response instanceof HttpServletResponse httpResponse) {
            // 防止 MIME 类型嗅探攻击
            httpResponse.setHeader("X-Content-Type-Options", "nosniff");

            // 防止点击劫持（禁止被 iframe 嵌套）
            httpResponse.setHeader("X-Frame-Options", "DENY");

            // XSS 过滤（兼容旧浏览器）
            httpResponse.setHeader("X-XSS-Protection", "1; mode=block");

            // 控制 Referrer 信息泄露
            httpResponse.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");

            // 禁止搜索引擎索引管理后台
            String uri = ((jakarta.servlet.http.HttpServletRequest) request).getRequestURI();
            if (uri.startsWith("/admin")) {
                httpResponse.setHeader("X-Robots-Tag", "noindex, nofollow");
                httpResponse.setHeader("Cache-Control", "no-store, no-cache, must-revalidate");
            }
        }

        chain.doFilter(request, response);
    }
}
