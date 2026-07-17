package com.potato.peacehaven.controller;

import com.potato.peacehaven.entity.BotGroupMember;
import com.potato.peacehaven.entity.CampMember;
import com.potato.peacehaven.entity.User;
import com.potato.peacehaven.repository.BotGroupMemberRepository;
import com.potato.peacehaven.repository.CampMemberRepository;
import com.potato.peacehaven.repository.UserRepository;
import com.potato.peacehaven.service.AdminOperationLogService;
import com.potato.peacehaven.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 营地成员管理（后台）
 */
@Controller
@RequestMapping("/admin/camp-members")
@RequiredArgsConstructor
public class AdminCampMemberController {

    private final CampMemberRepository campMemberRepository;
    private final BotGroupMemberRepository groupMemberRepository;
    private final UserRepository userRepository;
    private final UserService userService;
    private final AdminOperationLogService logService;

    /**
     * 成员列表页
     */
    @GetMapping
    public String list(Model model) {
        List<CampMember> members = campMemberRepository.findAllByOrderBySortOrderAsc();
        model.addAttribute("members", members);

        // 传递群成员有效昵称集合，用于前端显示"已加群"标签
        Set<String> groupNicknames = groupMemberRepository.findAll().stream()
                .map(BotGroupMember::getEffectiveName)
                .collect(Collectors.toSet());
        model.addAttribute("groupNicknames", groupNicknames);

        return "admin/camp-members";
    }

    /**
     * 添加成员
     */
    @PostMapping
    public String add(@RequestParam String nickname,
                      @RequestParam(defaultValue = "0") Integer sortOrder,
                      RedirectAttributes redirectAttributes,
                      HttpServletRequest request) {
        if (nickname == null || nickname.trim().isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "昵称不能为空");
            return "redirect:/admin/camp-members";
        }
        if (nickname.trim().length() > 50) {
            redirectAttributes.addFlashAttribute("error", "昵称不能超过50个字符");
            return "redirect:/admin/camp-members";
        }

        CampMember member = CampMember.builder()
                .nickname(nickname.trim())
                .sortOrder(sortOrder)
                .build();
        campMemberRepository.save(member);
        logService.record("营地成员", "新增", "添加成员：" + nickname.trim(), request);

        redirectAttributes.addFlashAttribute("message", "成员已添加：" + nickname.trim());
        return "redirect:/admin/camp-members";
    }

    /**
     * 编辑成员
     */
    @PostMapping("/{id}")
    public String update(@PathVariable Long id,
                         @RequestParam String nickname,
                         @RequestParam(defaultValue = "0") Integer sortOrder,
                         RedirectAttributes redirectAttributes,
                         HttpServletRequest request) {
        CampMember member = campMemberRepository.findById(id).orElse(null);
        if (member == null) {
            redirectAttributes.addFlashAttribute("error", "成员不存在");
            return "redirect:/admin/camp-members";
        }

        if (nickname == null || nickname.trim().isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "昵称不能为空");
            return "redirect:/admin/camp-members";
        }

        member.setNickname(nickname.trim());
        member.setSortOrder(sortOrder);
        campMemberRepository.save(member);
        logService.record("营地成员", "修改", "更新成员：" + nickname.trim(), request);

        redirectAttributes.addFlashAttribute("message", "成员已更新：" + nickname.trim());
        return "redirect:/admin/camp-members";
    }

    /**
     * 删除成员
     */
    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes redirectAttributes, HttpServletRequest request) {
        CampMember member = campMemberRepository.findById(id).orElse(null);
        campMemberRepository.deleteById(id);
        logService.record("营地成员", "删除", "删除成员：" + (member != null ? member.getNickname() : id.toString()), request);
        redirectAttributes.addFlashAttribute("message", "成员已删除");
        return "redirect:/admin/camp-members";
    }

    /**
     * 检查营地成员是否在群聊中
     */
    @GetMapping("/{id}/check-group")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> checkGroup(@PathVariable Long id) {
        CampMember member = campMemberRepository.findById(id).orElse(null);
        if (member == null) {
            return ResponseEntity.ok(Map.of("inGroup", false));
        }

        String nickname = member.getNickname();
        List<BotGroupMember> groupMembers = groupMemberRepository.findAll();
        for (BotGroupMember gm : groupMembers) {
            if (gm.getEffectiveName().equals(nickname)
                    || (gm.getDisplayName() != null && gm.getDisplayName().equals(nickname))
                    || (gm.getNickName() != null && gm.getNickName().equals(nickname))) {
                Map<String, Object> result = new HashMap<>();
                result.put("inGroup", true);
                result.put("wxid", gm.getWxid());
                result.put("effectiveName", gm.getEffectiveName());
                return ResponseEntity.ok(result);
            }
        }
        return ResponseEntity.ok(Map.of("inGroup", false));
    }

    /**
     * 扫描营地成员：找出 User 表中 campName='长安' 但不在成员表的用户
     */
    @GetMapping("/api/scan")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> scan() {
        // 查询所有 campName='长安' 的用户
        List<User> changAnUsers = userRepository.findByCampName("长安");

        // 查询当前成员表所有昵称
        Set<String> existingNicknames = campMemberRepository.findAll()
                .stream()
                .map(CampMember::getNickname)
                .collect(Collectors.toSet());

        // 找出不在成员表的用户
        List<Map<String, Object>> candidates = changAnUsers.stream()
                .filter(u -> !existingNicknames.contains(u.getNickname()))
                .map(u -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("id", u.getId());
                    m.put("nickname", u.getNickname());
                    return m;
                })
                .collect(Collectors.toList());

        Map<String, Object> result = new HashMap<>();
        result.put("candidates", candidates);
        result.put("totalChangAn", changAnUsers.size());
        result.put("alreadyInList", changAnUsers.size() - candidates.size());
        return ResponseEntity.ok(result);
    }

    /**
     * 批量处理扫描结果：
     * - checkedIds 中的用户：添加到成员表
     * - 未勾选的用户：将 campName 改为 '快乐101'
     */
    @PostMapping("/api/batch-process")
    @ResponseBody
    @Transactional
    public ResponseEntity<Map<String, Object>> batchProcess(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        List<Long> checkedIds = new ArrayList<>();
        Object idsObj = body.get("checkedIds");
        if (idsObj instanceof List) {
            for (Object id : (List<?>) idsObj) {
                checkedIds.add(Long.valueOf(id.toString()));
            }
        }

        List<Long> allIds = new ArrayList<>();
        Object allIdsObj = body.get("allIds");
        if (allIdsObj instanceof List) {
            for (Object id : (List<?>) allIdsObj) {
                allIds.add(Long.valueOf(id.toString()));
            }
        }

        int addedCount = 0;
        int removedCount = 0;

        Set<Long> checkedSet = new HashSet<>(checkedIds);

        for (Long userId : allIds) {
            User user = userRepository.findById(userId).orElse(null);
            if (user == null) continue;

            if (checkedSet.contains(userId)) {
                // 添加到成员表
                CampMember member = CampMember.builder()
                        .nickname(user.getNickname())
                        .sortOrder(0)
                        .build();
                campMemberRepository.save(member);
                addedCount++;
            } else {
                // 未勾选：将营地改为“快乐101”
                userService.updateCampName(userId, "快乐101");
                removedCount++;
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("addedCount", addedCount);
        result.put("removedCount", removedCount);
        logService.record("营地成员", "修改", "批量处理：添加" + addedCount + "人，移除" + removedCount + "人", request);
        return ResponseEntity.ok(result);
    }
}
