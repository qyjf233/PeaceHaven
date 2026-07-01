package com.potato.peacehaven.config;

import com.potato.peacehaven.entity.User;
import com.potato.peacehaven.enums.UserRole;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 后台管理路由拦截器
 * 拦截 /admin/** 请求，校验 session 中用户是否为 ADMIN 角色
 */
@Component
public class AdminInterceptor implements HandlerInterceptor {

    public static final String SESSION_USER_KEY = "session_user";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        HttpSession session = request.getSession(false);

        if (session != null) {
            User user = (User) session.getAttribute(SESSION_USER_KEY);
            if (user != null && user.getRole() == UserRole.ADMIN) {
                return true; // 放行
            }
        }

        // API 路径返回 JSON 401，页面路径重定向到登录页
        if (request.getRequestURI().startsWith("/admin/api/")) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"success\":false,\"message\":\"未登录或无管理员权限\"}");
            return false;
        }

        response.sendRedirect("/admin/login");
        return false;
    }
}
