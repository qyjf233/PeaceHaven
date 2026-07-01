package com.potato.peacehaven.config;

import com.potato.peacehaven.service.CsrfService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * CSRF Token 校验拦截器
 * 对所有 POST/PUT/DELETE 请求校验 CSRF Token
 */
@Component
@RequiredArgsConstructor
public class CsrfInterceptor implements HandlerInterceptor {

    private final CsrfService csrfService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String method = request.getMethod();

        // 仅对状态变更方法校验
        if ("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method) || "OPTIONS".equalsIgnoreCase(method)) {
            return true;
        }

        HttpSession session = request.getSession(false);

        // 从表单参数或请求头中获取 Token
        String token = request.getParameter(CsrfService.getParamName());
        if (token == null) {
            token = request.getHeader("X-CSRF-TOKEN");
        }

        if (csrfService.validateToken(session, token)) {
            return true;
        }

        // CSRF 校验失败
        String uri = request.getRequestURI();
        if (uri.startsWith("/admin/api/") || uri.startsWith("/api/")) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"success\":false,\"message\":\"CSRF校验失败，请刷新页面后重试\"}");
        } else {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "CSRF校验失败，请刷新页面后重试");
        }
        return false;
    }
}
