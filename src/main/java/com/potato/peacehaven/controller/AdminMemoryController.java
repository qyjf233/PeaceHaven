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
 * 留言板管理端 API
 */
@Slf4j
@RestController
@RequestMapping("/admin/api/memory-messages")
@RequiredArgsConstructor
public class AdminMemoryController {

    private final MemoryMessageService messageService;

    /**
     * 获取待审核留言
     */
    @GetMapping("/pending")
    public ResponseEntity<Map<String, Object>> getPending() {
        var messages = messageService.getPendingMessages();
        List<Map<String, Object>> list = messages.stream().map(m -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", m.getId());
            map.put("nickname", m.getNickname());
            map.put("content", m.getContent());
            map.put("createdAt", m.getCreatedAt().toString());
            return map;
        }).toList();
        return ResponseEntity.ok(Map.of("messages", list, "pendingCount", messageService.getPendingCount()));
    }

    /**
     * 获取所有留言（含已审核和已拒绝）
     */
    @GetMapping("/all")
    public ResponseEntity<List<Map<String, Object>>> getAll() {
        var messages = messageService.getAllMessages();
        List<Map<String, Object>> list = messages.stream().map(m -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", m.getId());
            map.put("nickname", m.getNickname());
            map.put("content", m.getContent());
            map.put("status", m.getStatus());
            map.put("createdAt", m.getCreatedAt().toString());
            if (m.getApprovedAt() != null) map.put("approvedAt", m.getApprovedAt().toString());
            return map;
        }).toList();
        return ResponseEntity.ok(list);
    }

    /**
     * 审核通过
     */
    @PostMapping("/{id}/approve")
    public ResponseEntity<Map<String, Object>> approve(@PathVariable Long id) {
        try {
            messageService.approve(id);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 审核拒绝
     */
    @PostMapping("/{id}/reject")
    public ResponseEntity<Map<String, Object>> reject(@PathVariable Long id) {
        try {
            messageService.reject(id);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 删除留言
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable Long id) {
        messageService.delete(id);
        return ResponseEntity.ok(Map.of("success", true));
    }
}
