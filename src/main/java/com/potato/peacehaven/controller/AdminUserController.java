package com.potato.peacehaven.controller;

import com.potato.peacehaven.entity.User;
import com.potato.peacehaven.enums.UserRole;
import com.potato.peacehaven.enums.UserStatus;
import com.potato.peacehaven.service.AdminOperationLogService;
import com.potato.peacehaven.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final UserService userService;
    private final AdminOperationLogService logService;

    /**
     * 用户列表页
     */
    @GetMapping
    public String list(@RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("userPage", userService.getAllUsers(page, 20));
        model.addAttribute("currentPage", page);
        return "admin/users";
    }

    /**
     * 修改用户角色
     */
    @PostMapping("/{id}/role")
    public String updateRole(@PathVariable Long id,
                             @RequestParam String role,
                             RedirectAttributes redirect,
                             HttpServletRequest request) {
        try {
            UserRole userRole = UserRole.valueOf(role.toUpperCase());
            User user = userService.getUserById(id);
            userService.updateUserRole(id, userRole);
            logService.record("用户管理", "修改", "修改用户 " + (user.getNickname() != null ? user.getNickname() : user.getPhone()) + " 角色为 " + role, request);
            redirect.addFlashAttribute("message", "角色已更新");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("message", "无效的角色");
        }
        return "redirect:/admin/users";
    }

    /**
     * 修改用户状态（封禁/解封）
     */
    @PostMapping("/{id}/status")
    public String updateStatus(@PathVariable Long id,
                               @RequestParam String status,
                               RedirectAttributes redirect,
                               HttpServletRequest request) {
        try {
            UserStatus userStatus = UserStatus.valueOf(status.toUpperCase());
            User user = userService.getUserById(id);
            userService.updateUserStatus(id, userStatus);
            logService.record("用户管理", "修改", (userStatus == UserStatus.BANNED ? "封禁" : "解封") + "用户 " + (user.getNickname() != null ? user.getNickname() : user.getPhone()), request);
            redirect.addFlashAttribute("message", userStatus == UserStatus.BANNED ? "用户已封禁" : "用户已解封");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("message", "无效的状态");
        }
        return "redirect:/admin/users";
    }
}
