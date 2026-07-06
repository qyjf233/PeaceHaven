package com.potato.peacehaven.controller;

import com.potato.peacehaven.entity.CombatMember;
import com.potato.peacehaven.entity.User;
import com.potato.peacehaven.repository.CombatMemberRepository;
import com.potato.peacehaven.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 战斗组成员后台管理控制器
 * 路由 /admin/combat-members 已被 AdminInterceptor 拦截，仅 ADMIN 可访问
 */
@Slf4j
@Controller
@RequestMapping("/admin/combat-members")
@RequiredArgsConstructor
public class AdminCombatMemberController {

    private final CombatMemberRepository combatMemberRepository;
    private final UserRepository userRepository;

    @GetMapping
    public String list(Model model) {
        List<CombatMember> members = combatMemberRepository.findAllByOrderBySortOrderAsc();

        // 批量查询关联用户昵称
        Map<Long, String> nicknameMap = new HashMap<>();
        for (CombatMember m : members) {
            userRepository.findById(m.getUserId())
                    .ifPresent(u -> nicknameMap.put(m.getUserId(), u.getNickname()));
        }

        model.addAttribute("members", members);
        model.addAttribute("nicknameMap", nicknameMap);
        return "admin/combat-members";
    }

    @PostMapping
    public String create(@RequestParam Long userId,
                         @RequestParam String jobClass,
                         @RequestParam(defaultValue = "") String setBonuses,
                         @RequestParam String groupTag,
                         @RequestParam(defaultValue = "") String panelImage,
                         @RequestParam(defaultValue = "0") Integer sortOrder,
                         RedirectAttributes redirect) {
        // 检查用户是否存在
        Optional<User> userOpt = userRepository.findById(userId);
        if (userOpt.isEmpty()) {
            redirect.addFlashAttribute("error", "用户不存在");
            return "redirect:/admin/combat-members";
        }

        // 检查是否已存在
        if (combatMemberRepository.existsByUserId(userId)) {
            redirect.addFlashAttribute("error", "该用户已在战斗组中");
            return "redirect:/admin/combat-members";
        }

        CombatMember member = CombatMember.builder()
                .userId(userId)
                .jobClass(jobClass)
                .setBonuses(setBonuses)
                .groupTag(groupTag)
                .panelImage(panelImage)
                .sortOrder(sortOrder)
                .build();
        combatMemberRepository.save(member);
        redirect.addFlashAttribute("message", "成员添加成功！");
        return "redirect:/admin/combat-members";
    }

    @PostMapping("/{id}")
    public String update(@PathVariable Long id,
                         @RequestParam Long userId,
                         @RequestParam String jobClass,
                         @RequestParam(defaultValue = "") String setBonuses,
                         @RequestParam String groupTag,
                         @RequestParam(defaultValue = "") String panelImage,
                         @RequestParam(defaultValue = "0") Integer sortOrder,
                         RedirectAttributes redirect) {
        CombatMember member = combatMemberRepository.findById(id).orElse(null);
        if (member == null) {
            redirect.addFlashAttribute("error", "成员不存在");
            return "redirect:/admin/combat-members";
        }

        // 检查 userId 是否被其他记录占用
        Optional<CombatMember> existing = combatMemberRepository.findByUserId(userId);
        if (existing.isPresent() && !existing.get().getId().equals(id)) {
            redirect.addFlashAttribute("error", "该用户已在战斗组中");
            return "redirect:/admin/combat-members";
        }

        member.setUserId(userId);
        member.setJobClass(jobClass);
        member.setSetBonuses(setBonuses);
        member.setGroupTag(groupTag);
        member.setPanelImage(panelImage);
        member.setSortOrder(sortOrder);
        combatMemberRepository.save(member);
        redirect.addFlashAttribute("message", "成员更新成功！");
        return "redirect:/admin/combat-members";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes redirect) {
        combatMemberRepository.deleteById(id);
        redirect.addFlashAttribute("message", "成员已移除！");
        return "redirect:/admin/combat-members";
    }

    /**
     * 搜索用户（AJAX）- 根据昵称或手机号模糊搜索
     */
    @GetMapping("/search-users")
    @ResponseBody
    public List<Map<String, Object>> searchUsers(@RequestParam String keyword) {
        if (keyword == null || keyword.trim().length() < 1) {
            return List.of();
        }
        String kw = keyword.trim().toLowerCase();
        List<User> allUsers = userRepository.findAll();
        return allUsers.stream()
                .filter(u -> (u.getNickname() != null && u.getNickname().toLowerCase().contains(kw))
                        || (u.getPhone() != null && u.getPhone().contains(kw)))
                .limit(20)
                .map(u -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("id", u.getId());
                    m.put("nickname", u.getNickname());
                    m.put("phone", u.getPhone());
                    m.put("inCombat", combatMemberRepository.existsByUserId(u.getId()));
                    return m;
                })
                .collect(Collectors.toList());
    }
}
