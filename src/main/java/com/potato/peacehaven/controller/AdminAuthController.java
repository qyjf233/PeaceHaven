package com.potato.peacehaven.controller;

import com.potato.peacehaven.config.AdminInterceptor;
import com.potato.peacehaven.entity.User;
import com.potato.peacehaven.enums.UserRole;
import com.potato.peacehaven.enums.UserStatus;
import com.potato.peacehaven.service.UserService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminAuthController {

    private final UserService userService;

    /**
     * 管理员登录页（不被拦截器拦截）
     */
    @GetMapping("/login")
    public String loginPage(HttpSession session, Model model) {
        // 如果已登录且是管理员，直接跳转
        User user = (User) session.getAttribute(AdminInterceptor.SESSION_USER_KEY);
        if (user != null && user.getRole() == UserRole.ADMIN) {
            return "redirect:/admin/activities";
        }
        return "admin/login";
    }

    /**
     * 建筑大赛作品审核页
     */
    @GetMapping("/contest-works")
    public String contestWorksPage() {
        return "admin/contest-works";
    }
}
