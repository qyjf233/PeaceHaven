package com.potato.peacehaven.config;

import com.potato.peacehaven.entity.PageVisit;
import com.potato.peacehaven.entity.User;
import com.potato.peacehaven.repository.PageVisitRepository;
import com.potato.peacehaven.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 页面访问记录拦截器
 * 仅记录 GET 请求且响应为 HTML 的页面访问，不记录 API 和静态资源
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PageVisitInterceptor implements HandlerInterceptor {

    private final PageVisitRepository pageVisitRepository;
    private final UserService userService;

    /** URI -> 页面名称映射 */
    private static final Map<String, String> PAGE_NAMES = new LinkedHashMap<>();
    static {
        PAGE_NAMES.put("/", "首页");
        PAGE_NAMES.put("/activities", "活动列表");
        PAGE_NAMES.put("/combat-roster", "战斗组名册");
        PAGE_NAMES.put("/agreement", "营地公约");
        PAGE_NAMES.put("/admin", "管理后台");
        PAGE_NAMES.put("/admin/activities", "活动管理");
        PAGE_NAMES.put("/admin/users", "用户管理");
        PAGE_NAMES.put("/admin/camp-members", "营地成员管理");
        PAGE_NAMES.put("/admin/camp-affairs", "营地事务管理");
        PAGE_NAMES.put("/admin/welfare", "福利系统");
        PAGE_NAMES.put("/admin/contest-works", "作品审核");
        PAGE_NAMES.put("/admin/operation-logs", "操作日志");
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        try {
            // 只记录 GET 页面请求
            if (!"GET".equalsIgnoreCase(request.getMethod())) return;

            int status = response.getStatus();
            if (status >= 400) return; // 不记录错误页

            String uri = request.getRequestURI();

            // 跳过静态资源和API
            if (uri.startsWith("/css/") || uri.startsWith("/js/") || uri.startsWith("/images/")
                    || uri.startsWith("/fonts/") || uri.startsWith("/api/")) return;

            // 映射页面名称
            String pageName = resolvePageName(uri);
            if (pageName == null) return; // 不记录未映射的路径

            // 获取用户昵称
            String nickname = null;
            HttpSession session = request.getSession(false);
            if (session != null) {
                User user = userService.getCurrentUser(session);
                if (user != null) {
                    nickname = user.getNickname() != null ? user.getNickname() : user.getPhone();
                }
            }

            // 获取IP
            String ip = getClientIp(request);

            // Referer
            String referer = request.getHeader("Referer");
            if (referer != null && referer.length() > 490) {
                referer = referer.substring(0, 490);
            }

            // UserAgent
            String ua = request.getHeader("User-Agent");
            if (ua != null && ua.length() > 490) {
                ua = ua.substring(0, 490);
            }

            PageVisit visit = PageVisit.builder()
                    .ip(ip)
                    .page(pageName)
                    .path(uri.length() > 490 ? uri.substring(0, 490) : uri)
                    .nickname(nickname)
                    .referer(referer)
                    .userAgent(ua)
                    .build();

            pageVisitRepository.save(visit);
        } catch (Exception e) {
            log.debug("页面访问记录失败: {}", e.getMessage());
        }
    }

    /**
     * 解析URI对应的页面名称
     */
    private String resolvePageName(String uri) {
        // 精确匹配
        if (PAGE_NAMES.containsKey(uri)) {
            return PAGE_NAMES.get(uri);
        }
        // 前缀匹配
        if (uri.startsWith("/activities/")) return "活动详情";
        if (uri.startsWith("/admin/contest-works/")) return "作品审核详情";
        if (uri.startsWith("/admin/camp-affairs/")) return "营地事务详情";
        if (uri.startsWith("/admin/activities/")) return "活动编辑";
        return null;
    }

    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }
}
