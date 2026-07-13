package com.potato.peacehaven.controller;

import com.potato.peacehaven.entity.User;
import com.potato.peacehaven.enums.UserRole;
import com.potato.peacehaven.repository.*;
import com.potato.peacehaven.service.UserService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminAuthController {

    private final UserService userService;
    private final ActivityRepository activityRepository;
    private final UserRepository userRepository;
    private final CombatMemberRepository combatMemberRepository;

    /**
     * 管理员仪表盘首页
     */
    @GetMapping
    public String dashboard(Model model) {
        model.addAttribute("activityCount", activityRepository.count());
        model.addAttribute("userCount", userRepository.count());
        model.addAttribute("combatMemberCount", combatMemberRepository.count());
        return "admin/dashboard";
    }

    /**
     * 管理员登录页（不被拦截器拦截）
     */
    @GetMapping("/login")
    public String loginPage(HttpSession session, Model model) {
        // 如果已登录且是管理员，直接跳转
        User user = userService.getCurrentUser(session);
        if (user != null && user.getRole() == UserRole.ADMIN) {
            return "redirect:/admin";
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
