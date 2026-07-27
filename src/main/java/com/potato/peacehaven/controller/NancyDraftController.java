package com.potato.peacehaven.controller;

import com.potato.peacehaven.entity.Activity;
import com.potato.peacehaven.entity.User;
import com.potato.peacehaven.service.ActivityService;
import com.potato.peacehaven.service.NancyDraftService;
import com.potato.peacehaven.service.UserService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 南希对抗赛 API
 */
@Slf4j
@RestController
@RequestMapping("/api/nancy")
@RequiredArgsConstructor
public class NancyDraftController {

    private final NancyDraftService nancyDraftService;
    private final ActivityService activityService;
    private final UserService userService;

    /**
     * 获取赛事完整状态
     */
    @GetMapping("/{slug}/status")
    public ResponseEntity<Map<String, Object>> getEventStatus(
            @PathVariable String slug, HttpSession session) {
        Activity activity = activityService.getActivityBySlug(slug);
        User currentUser = userService.getCurrentUser(session);
        Map<String, Object> status = nancyDraftService.getEventStatus(activity.getId(), currentUser);
        return ResponseEntity.ok(status);
    }

    /**
     * 报名参赛
     * body: { "roundId": 1 }
     */
    @PostMapping("/{slug}/register")
    public ResponseEntity<Map<String, Object>> register(
            @PathVariable String slug,
            @RequestBody(required = false) Map<String, Object> body,
            HttpSession session) {
        User user = userService.getCurrentUser(session);
        if (user == null) {
            return ResponseEntity.status(401).body(Map.of("error", "请先登录"));
        }

        Activity activity = activityService.getActivityBySlug(slug);
        int roundId = 1;
        if (body != null && body.get("roundId") != null) {
            roundId = Integer.parseInt(body.get("roundId").toString());
        }

        try {
            Map<String, Object> result = nancyDraftService.register(activity.getId(), user, roundId);
            return ResponseEntity.ok(result);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 取消报名
     * body: { "roundId": 1 }
     */
    @PostMapping("/{slug}/cancel")
    public ResponseEntity<Map<String, Object>> cancel(
            @PathVariable String slug,
            @RequestBody(required = false) Map<String, Object> body,
            HttpSession session) {
        User user = userService.getCurrentUser(session);
        if (user == null) {
            return ResponseEntity.status(401).body(Map.of("error", "请先登录"));
        }

        Activity activity = activityService.getActivityBySlug(slug);
        int roundId = 1;
        if (body != null && body.get("roundId") != null) {
            roundId = Integer.parseInt(body.get("roundId").toString());
        }

        try {
            Map<String, Object> result = nancyDraftService.cancelRegistration(activity.getId(), user, roundId);
            return ResponseEntity.ok(result);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 获取选秀状态
     */
    @GetMapping("/{slug}/draft")
    public ResponseEntity<Map<String, Object>> getDraftStatus(@PathVariable String slug) {
        Activity activity = activityService.getActivityBySlug(slug);
        Map<String, Object> status = nancyDraftService.getDraftStatus(activity.getId());
        return ResponseEntity.ok(status);
    }

    /**
     * 队长选人
     * body: { "teamSide": "A", "playerUserId": 123 }
     */
    @Transactional
    @PostMapping("/{slug}/draft/pick")
    public ResponseEntity<Map<String, Object>> submitPick(
            @PathVariable String slug,
            @RequestBody Map<String, Object> body,
            HttpSession session) {

        User user = userService.getCurrentUser(session);
        if (user == null) {
            return ResponseEntity.status(401).body(Map.of("error", "请先登录"));
        }

        Activity activity = activityService.getActivityBySlug(slug);

        String teamSide = (String) body.get("teamSide");
        Object playerIdObj = body.get("playerUserId");

        if (teamSide == null || playerIdObj == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "缺少参数"));
        }

        try {
            Long playerUserId = Long.valueOf(playerIdObj.toString());
            Map<String, Object> result = nancyDraftService.submitPick(
                    activity.getId(), teamSide, playerUserId, user);
            return ResponseEntity.ok(result);
        } catch (RuntimeException e) {
            log.warn("[南希对抗赛] 选人失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 裁判提交比赛成绩
     * body: { "matchIndex": 0, "scoreA": 1500, "scoreB": 1320 }
     */
    @Transactional
    @PostMapping("/{slug}/match/submit")
    public ResponseEntity<Map<String, Object>> submitMatchResult(
            @PathVariable String slug,
            @RequestBody Map<String, Object> body,
            HttpSession session) {

        User user = userService.getCurrentUser(session);
        if (user == null) {
            return ResponseEntity.status(401).body(Map.of("error", "请先登录"));
        }

        Activity activity = activityService.getActivityBySlug(slug);

        try {
            int matchIndex = Integer.parseInt(body.get("matchIndex").toString());
            int scoreA = Integer.parseInt(body.get("scoreA").toString());
            int scoreB = Integer.parseInt(body.get("scoreB").toString());

            Map<String, Object> result = nancyDraftService.submitMatchResult(
                    activity.getId(), matchIndex, scoreA, scoreB, user);
            return ResponseEntity.ok(result);
        } catch (RuntimeException e) {
            log.warn("[南希对抗赛] 成绩提交失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
