package com.potato.peacehaven.controller;

import com.potato.peacehaven.entity.ContestWork;
import com.potato.peacehaven.service.ContestService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 管理员作品审核 API
 * 路径 /admin/api/contest/** 受 AdminInterceptor 鉴权保护
 */
@Slf4j
@RestController
@RequestMapping("/admin/api/contest")
@RequiredArgsConstructor
public class AdminContestApiController {

    private final ContestService contestService;

    private static final int PAGE_SIZE = 9;

    /**
     * 分页查询作品
     */
    @GetMapping("/works")
    public ResponseEntity<Map<String, Object>> listWorks(
            @RequestParam Long activityId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) String status) {

        Page<ContestWork> workPage = contestService.getWorksForReview(activityId, status, page, PAGE_SIZE);

        List<ContestWork> pageWorks = workPage.getContent();
        Map<Long, Integer> voteCountMap = contestService.getVoteCountMap(pageWorks);

        List<Map<String, Object>> works = pageWorks.stream().map(w -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", w.getId());
            m.put("title", w.getTitle());
            m.put("description", w.getDescription());
            m.put("imageUrl", w.getImageUrl());
            m.put("authorName", w.getUser().getNickname());
            m.put("authorPhone", w.getUser().getPhone());
            m.put("voteCount", voteCountMap.getOrDefault(w.getId(), 0));
            m.put("status", w.getStatus().name());
            m.put("createdAt", w.getCreatedAt() != null ? w.getCreatedAt().toString() : null);
            return m;
        }).collect(Collectors.toList());

        Map<String, Object> result = new HashMap<>();
        result.put("works", works);
        result.put("page", workPage.getNumber());
        result.put("totalPages", workPage.getTotalPages());
        result.put("totalElements", workPage.getTotalElements());
        result.put("hasNext", workPage.hasNext());

        return ResponseEntity.ok(result);
    }

    /**
     * 审核作品（通过/拒绝）
     */
    @PostMapping("/works/{id}/review")
    public ResponseEntity<Map<String, Object>> reviewWork(
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {

        Map<String, Object> result = new HashMap<>();
        String action = body.get("action");

        if (!"approve".equals(action) && !"reject".equals(action)) {
            result.put("success", false);
            result.put("message", "无效的操作");
            return ResponseEntity.ok(result);
        }

        try {
            ContestWork.WorkStatus newStatus =
                    "approve".equals(action) ? ContestWork.WorkStatus.APPROVED : ContestWork.WorkStatus.REJECTED;
            contestService.reviewWork(id, newStatus);
            result.put("success", true);
            result.put("message", "approve".equals(action) ? "已通过" : "已拒绝");
        } catch (RuntimeException e) {
            result.put("success", false);
            result.put("message", e.getMessage());
        }

        return ResponseEntity.ok(result);
    }
}
