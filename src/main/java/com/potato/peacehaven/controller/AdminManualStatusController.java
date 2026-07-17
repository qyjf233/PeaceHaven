package com.potato.peacehaven.controller;

import com.potato.peacehaven.entity.UserManualStatus;
import com.potato.peacehaven.repository.UserManualStatusRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 手动状态管理 API
 */
@Controller
@RequestMapping("/admin/api/status")
@RequiredArgsConstructor
public class AdminManualStatusController {

    private final UserManualStatusRepository statusRepo;

    /** 查询所有状态（含是否过期标记） */
    @ResponseBody
    @GetMapping
    public Map<String, Object> list() {
        List<Map<String, Object>> items = statusRepo.findAll().stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.getId());
            m.put("text", s.getStatusText());
            m.put("createdAt", s.getCreatedAt());
            m.put("expiresAt", s.getExpiresAt());
            m.put("active", s.getExpiresAt() == null || s.getExpiresAt().isAfter(LocalDateTime.now()));
            return m;
        }).collect(Collectors.toList());

        return Map.of("success", true, "data", items);
    }

    /** 新增状态 */
    @ResponseBody
    @PostMapping
    public Map<String, Object> add(@RequestParam String text,
                                    @RequestParam(required = false) Integer hours) {
        if (text == null || text.isBlank()) {
            return Map.of("success", false, "error", "状态描述不能为空");
        }

        UserManualStatus status = UserManualStatus.builder()
                .statusText(text.trim())
                .expiresAt(hours != null && hours > 0 ? LocalDateTime.now().plusHours(hours) : null)
                .build();
        statusRepo.save(status);

        return Map.of("success", true, "id", status.getId());
    }

    /** 删除状态 */
    @ResponseBody
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        statusRepo.deleteById(id);
        return Map.of("success", true);
    }

    /** 清除所有过期状态 */
    @ResponseBody
    @DeleteMapping("/expired")
    public Map<String, Object> clearExpired() {
        List<UserManualStatus> expired = statusRepo.findAll().stream()
                .filter(s -> s.getExpiresAt() != null && s.getExpiresAt().isBefore(LocalDateTime.now()))
                .collect(Collectors.toList());
        statusRepo.deleteAll(expired);
        return Map.of("success", true, "cleared", expired.size());
    }
}
