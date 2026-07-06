package com.potato.peacehaven.controller;

import com.potato.peacehaven.config.AdminInterceptor;
import com.potato.peacehaven.entity.SiteStats;
import com.potato.peacehaven.entity.TeamMember;
import com.potato.peacehaven.entity.User;
import com.potato.peacehaven.repository.SiteStatsRepository;
import com.potato.peacehaven.repository.TeamMemberRepository;
import com.potato.peacehaven.repository.UserRepository;
import com.potato.peacehaven.service.ActivityService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Controller
@RequiredArgsConstructor
public class HomeController {

    private final ActivityService activityService;
    private final SiteStatsRepository siteStatsRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final UserRepository userRepository;

    @Value("${features.combat-roster.enabled:false}")
    private boolean combatRosterEnabled;

    @GetMapping("/")
    public String index(Model model, HttpSession session) {
        model.addAttribute("recentActivities", activityService.getRecentActivities());

        // 营地简介统计数据
        SiteStats stats = siteStatsRepository.findSiteStats().orElse(
                SiteStats.builder().memberCount(0).eventCount(0).battleCount(0).build()
        );
        model.addAttribute("siteStats", stats);

        // 管理组成员 + 从 User 表解析昵称
        List<TeamMember> members = teamMemberRepository.findAllByOrderBySortOrderAsc();
        Map<Long, String> nicknameMap = new HashMap<>();
        for (TeamMember m : members) {
            if (m.getUserId() != null && m.getUserId() > 0) {
                userRepository.findById(m.getUserId())
                        .ifPresent(u -> nicknameMap.put(m.getId(), u.getNickname()));
            }
        }
        model.addAttribute("teamMembers", members);
        model.addAttribute("nicknameMap", nicknameMap);

        // 判断当前用户是否为管理组成员（用于导航栏条件渲染），且战斗组功能已启用
        User user = (User) session.getAttribute(AdminInterceptor.SESSION_USER_KEY);
        boolean isManager = combatRosterEnabled && user != null && teamMemberRepository.existsByUserId(user.getId());
        model.addAttribute("isManager", isManager);

        return "index";
    }

    @GetMapping("/agreement")
    public String agreement() {
        return "agreement";
    }

    @GetMapping("/judge/building-master-1")
    public String judgePanel() {
        return "activities/judge-panel";
    }
}
