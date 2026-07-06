package com.potato.peacehaven.controller;

import com.potato.peacehaven.config.AdminInterceptor;
import com.potato.peacehaven.entity.CombatMember;
import com.potato.peacehaven.entity.User;
import com.potato.peacehaven.repository.CombatMemberRepository;
import com.potato.peacehaven.repository.TeamMemberRepository;
import com.potato.peacehaven.repository.UserRepository;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 战斗组成员名片展示页
 * 仅管理组成员（team_member 表用户）可见
 */
@Controller
@RequiredArgsConstructor
public class CombatMemberController {

    private final CombatMemberRepository combatMemberRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final UserRepository userRepository;

    @Value("${features.combat-roster.enabled:false}")
    private boolean combatRosterEnabled;

    @GetMapping("/combat-roster")
    public String roster(HttpSession session, Model model) {
        // 功能未启用时直接跳回首页
        if (!combatRosterEnabled) {
            return "redirect:/";
        }

        User user = (User) session.getAttribute(AdminInterceptor.SESSION_USER_KEY);

        // 必须登录
        if (user == null) {
            return "redirect:/";
        }

        // 必须是管理组成员（team_member 表中有该用户）
        boolean isManager = teamMemberRepository.existsByUserId(user.getId());
        if (!isManager) {
            model.addAttribute("errorMessage", "此页面仅管理组成员可见");
            return "combat-roster-denied";
        }

        // 查询所有战斗组成员
        List<CombatMember> members = combatMemberRepository.findAllByOrderBySortOrderAsc();

        // 批量查询关联用户昵称
        Map<Long, String> nicknameMap = new HashMap<>();
        for (CombatMember m : members) {
            userRepository.findById(m.getUserId())
                    .ifPresent(u -> nicknameMap.put(m.getUserId(), u.getNickname()));
        }

        model.addAttribute("members", members);
        model.addAttribute("nicknameMap", nicknameMap);
        model.addAttribute("isManager", true);
        return "combat-roster";
    }
}
