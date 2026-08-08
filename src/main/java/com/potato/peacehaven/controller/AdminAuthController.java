package com.potato.peacehaven.controller;

import com.potato.peacehaven.entity.Activity;
import com.potato.peacehaven.entity.User;
import com.potato.peacehaven.enums.UserRole;
import com.potato.peacehaven.repository.*;
import com.potato.peacehaven.service.ActivityService;
import com.potato.peacehaven.service.UserService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Controller
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminAuthController {

    private final UserService userService;
    private final ActivityRepository activityRepository;
    private final ActivityService activityService;
    private final UserRepository userRepository;
    private final CampMemberRepository campMemberRepository;
    private final ContestWorkRepository contestWorkRepository;

    /**
     * 管理员仪表盘首页
     */
    @GetMapping
    public String dashboard(Model model) {
        model.addAttribute("activityCount", activityRepository.count());
        model.addAttribute("userCount", userRepository.count());
        model.addAttribute("campMemberCount", campMemberRepository.count());
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
     * 作品审核 - 活动列表页
     */
    @GetMapping("/contest-works")
    public String contestWorksPage(Model model) {
        List<Activity> activities = activityService.getActivitiesWithWorkSubmission();
        model.addAttribute("activities", activities);
        model.addAttribute("contestWorkRepository", contestWorkRepository);
        return "admin/contest-works";
    }

    /**
     * 作品审核 - 指定活动的作品审核详情页
     */
    @GetMapping("/contest-works/{activityId}")
    public String contestWorksDetailPage(@PathVariable Long activityId, Model model) {
        Activity activity = activityService.getActivityById(activityId);
        model.addAttribute("activity", activity);
        return "admin/contest-works-detail";
    }

    /**
     * 留言管理页面
     */
    @GetMapping("/memory-messages")
    public String memoryMessagesPage() {
        return "admin/memory-messages";
    }

    /**
     * 抽奖管理页面
     */
    @GetMapping("/lotteries")
    public String lotteriesPage() {
        return "admin/lotteries";
    }
}
