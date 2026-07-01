package com.potato.peacehaven.service;

import com.potato.peacehaven.entity.User;
import com.potato.peacehaven.enums.UserRole;
import com.potato.peacehaven.enums.UserStatus;
import com.potato.peacehaven.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;

    @Value("${app.admin-phones:}")
    private String adminPhones;

    /** 登录结果，包含用户对象和是否为新用户标识 */
    public record LoginResult(User user, boolean isNewUser) {}

    /**
     * 登录（自动注册新用户）
     */
    @Transactional
    public LoginResult login(String phone) {
        final boolean[] isNew = {false};
        User user = userRepository.findByPhone(phone).orElseGet(() -> {
            isNew[0] = true;
            // 自动注册新用户
            String defaultNickname = "用户" + phone.substring(phone.length() - 4);
            User newUser = User.builder()
                    .phone(phone)
                    .nickname(defaultNickname)
                    .build();

            // 检查是否为配置的管理员手机号
            if (isAdminPhone(phone)) {
                newUser.setRole(UserRole.ADMIN);
            }

            return userRepository.save(newUser);
        });

        // 检查是否被封禁
        if (user.getStatus() == UserStatus.BANNED) {
            throw new RuntimeException("该账号已被封禁");
        }

        // 更新最后登录时间
        user.setLastLoginAt(LocalDateTime.now());
        userRepository.save(user);

        return new LoginResult(user, isNew[0]);
    }

    public User getUserById(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("用户不存在: " + id));
    }

    public Page<User> getAllUsers(int page, int size) {
        return userRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(page, size));
    }

    @Transactional
    public void updateUserRole(Long id, UserRole role) {
        User user = getUserById(id);
        user.setRole(role);
        userRepository.save(user);
    }

    @Transactional
    public void updateUserStatus(Long id, UserStatus status) {
        User user = getUserById(id);
        user.setStatus(status);
        userRepository.save(user);
    }

    @Transactional
    public void updateNickname(Long id, String nickname) {
        User user = getUserById(id);
        user.setNickname(nickname);
        userRepository.save(user);
    }

    @Transactional
    public void updateCampName(Long id, String campName) {
        User user = getUserById(id);
        user.setCampName(campName);
        userRepository.save(user);
    }

    /**
     * 获取营地名建议列表（前缀匹配，最多返回10条）
     */
    public List<String> getCampSuggestions(String prefix) {
        if (prefix == null || prefix.trim().isEmpty()) {
            return List.of();
        }
        return userRepository.findDistinctCampNamesByPrefix(prefix.trim())
                .stream().limit(10).toList();
    }

    /**
     * 检查手机号是否在管理员列表中
     */
    private boolean isAdminPhone(String phone) {
        if (adminPhones == null || adminPhones.isBlank()) return false;
        List<String> admins = Arrays.asList(adminPhones.split(","));
        return admins.stream().map(String::trim).anyMatch(phone::equals);
    }
}
