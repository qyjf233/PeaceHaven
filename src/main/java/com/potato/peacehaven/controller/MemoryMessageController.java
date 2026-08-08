package com.potato.peacehaven.controller;

import com.potato.peacehaven.service.MemoryMessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 留言板公开 API
 */
@Slf4j
@RestController
@RequestMapping("/api/memory-messages")
@RequiredArgsConstructor
public class MemoryMessageController {

    private final MemoryMessageService messageService;

    /**
     * 提交留言：已登录用户自动通过，游客需审核
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> submit(@RequestBody Map<String, String> body,
                                                       jakarta.servlet.http.HttpSession session) {
        String nickname = body.get("nickname");
        String content = body.get("content");

        try {
            // 检查是否已登录
            Long userId = (Long) session.getAttribute("session_user_id");
            boolean isLoggedIn = userId != null;

            var msg = messageService.create(nickname, content);
            if (isLoggedIn) {
                // 已登录用户直接审核通过
                messageService.approve(msg.getId());
                return ResponseEntity.ok(Map.of("success", true, "message", "留言已发布"));
            } else {
                return ResponseEntity.ok(Map.of("success", true, "message", "留言已提交，等待审核"));
            }
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 获取已审核通过的留言（公开接口，用于滚动展示和留言板）
     */
    @GetMapping("/approved")
    public ResponseEntity<List<Map<String, Object>>> getApproved() {
        var messages = messageService.getApprovedMessages();
        List<Map<String, Object>> result = messages.stream().map(m -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", m.getId());
            map.put("nickname", m.getNickname());
            map.put("content", m.getContent());
            map.put("createdAt", m.getApprovedAt() != null ? m.getApprovedAt().toString() : m.getCreatedAt().toString());
            return map;
        }).toList();
        return ResponseEntity.ok(result);
    }
}
