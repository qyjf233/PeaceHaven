package com.potato.peacehaven.controller;

import com.potato.peacehaven.config.AdminInterceptor;
import com.potato.peacehaven.entity.User;
import com.potato.peacehaven.service.SmsService;
import com.potato.peacehaven.service.UserService;
import com.aliyun.sdk.service.dypnsapi20170525.models.SendSmsVerifyCodeResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final SmsService smsService;
    private final UserService userService;

    private static final Pattern PHONE_PATTERN = Pattern.compile("^1[3-9]\\d{9}$");

    /**
     * 发送验证码（60秒限频由前端控制，后端做基础校验）
     */
    @PostMapping("/send-code")
    public ResponseEntity<Map<String, Object>> sendCode(@RequestBody Map<String, String> body) {
        String phone = body.get("phone");
        Map<String, Object> result = new HashMap<>();

        // 校验手机号格式
        if (phone == null || !PHONE_PATTERN.matcher(phone).matches()) {
            result.put("success", false);
            result.put("message", "请输入正确的手机号");
            return ResponseEntity.ok(result);
        }

        try {
            SendSmsVerifyCodeResponse response = smsService.sendVerifyCode(phone).join();
            if (response.getBody().getSuccess()) {
                result.put("success", true);
                result.put("message", "验证码已发送");
            } else {
                result.put("success", false);
                result.put("message", "验证码发送失败，请稍后重试");
            }
        } catch (Exception e) {
            log.error("发送验证码异常: {}", e.getMessage(), e);
            result.put("success", false);
            result.put("message", "验证码发送失败，请稍后重试");
        }
        return ResponseEntity.ok(result);
    }

    /**
     * 验证码登录
     */
    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, String> body, HttpSession session) {
        String phone = body.get("phone");
        String code = body.get("code");
        String agreed = body.get("agreed");
        Map<String, Object> result = new HashMap<>();

        // 校验参数
        if (phone == null || !PHONE_PATTERN.matcher(phone).matches()) {
            result.put("success", false);
            result.put("message", "请输入正确的手机号");
            return ResponseEntity.ok(result);
        }

        if (code == null || code.length() != 4) {
            result.put("success", false);
            result.put("message", "请输入4位验证码");
            return ResponseEntity.ok(result);
        }

        if (!"true".equals(agreed)) {
            result.put("success", false);
            result.put("message", "请先阅读并同意用户协议");
            return ResponseEntity.ok(result);
        }

        // 校验验证码
        try {
            Boolean valid = smsService.checkVerifyCode(phone, code).join();
            if (!valid) {
                result.put("success", false);
                result.put("message", "验证码错误或已过期");
                return ResponseEntity.ok(result);
            }
        } catch (Exception e) {
            log.error("验证码校验异常: {}", e.getMessage(), e);
            result.put("success", false);
            result.put("message", "验证码校验失败，请稍后重试");
            return ResponseEntity.ok(result);
        }

        // 登录（自动注册）
        try {
            UserService.LoginResult loginResult = userService.login(phone);
            User user = loginResult.user();
            // 存入 session
            session.setAttribute(AdminInterceptor.SESSION_USER_KEY, user);

            result.put("success", true);
            result.put("message", "登录成功");
            result.put("nickname", user.getNickname());
            result.put("role", user.getRole().name());
            result.put("isNewUser", loginResult.isNewUser());
        } catch (RuntimeException e) {
            result.put("success", false);
            result.put("message", e.getMessage());
        }
        return ResponseEntity.ok(result);
    }

    /**
     * 设置昵称（新用户首次登录后调用）
     */
    @PostMapping("/nickname")
    public ResponseEntity<Map<String, Object>> updateNickname(@RequestBody Map<String, String> body, HttpSession session) {
        Map<String, Object> result = new HashMap<>();
        User user = (User) session.getAttribute(AdminInterceptor.SESSION_USER_KEY);

        if (user == null) {
            result.put("success", false);
            result.put("message", "请先登录");
            return ResponseEntity.ok(result);
        }

        String nickname = body.get("nickname");
        if (nickname == null || nickname.trim().isEmpty()) {
            result.put("success", false);
            result.put("message", "昵称不能为空");
            return ResponseEntity.ok(result);
        }

        if (nickname.trim().length() > 14) {
            result.put("success", false);
            result.put("message", "昵称不能超过14个字符");
            return ResponseEntity.ok(result);
        }

        try {
            userService.updateNickname(user.getId(), nickname.trim());
            // 更新 session 中的用户
            user.setNickname(nickname.trim());
            session.setAttribute(AdminInterceptor.SESSION_USER_KEY, user);

            result.put("success", true);
            result.put("message", "昵称设置成功");
            result.put("nickname", nickname.trim());
        } catch (RuntimeException e) {
            result.put("success", false);
            result.put("message", e.getMessage());
        }
        return ResponseEntity.ok(result);
    }

    /**
     * 退出登录
     */
    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logout(HttpSession session) {
        session.invalidate();
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "已退出登录");
        return ResponseEntity.ok(result);
    }

    /**
     * 获取当前登录用户信息
     */
    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(HttpSession session) {
        Map<String, Object> result = new HashMap<>();
        User user = (User) session.getAttribute(AdminInterceptor.SESSION_USER_KEY);

        if (user != null) {
            result.put("loggedIn", true);
            result.put("id", user.getId());
            result.put("phone", user.getPhone());
            result.put("nickname", user.getNickname());
            result.put("avatar", user.getAvatar());
            result.put("role", user.getRole().name());
            result.put("status", user.getStatus().name());
        } else {
            result.put("loggedIn", false);
        }
        return ResponseEntity.ok(result);
    }
}
