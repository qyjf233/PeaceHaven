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
import java.util.List;
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

        // 独立事务：自动为将领创建报名记录（失败不影响主查询）
        try {
            nancyDraftService.autoRegisterCaptains(activity.getId());
        } catch (Exception e) {
            log.debug("[南希对抗赛] 将领自动报名跳过: {}", e.getMessage());
        }

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

    /**
     * 录入战绩记录（裁判/管理员）
     * body: { "userId": 123, "userName": "xxx", "gameId": 1, "team": "teamA",
     *         "kills": 5, "deaths": 2, "assists": 3, "damage": 12000,
     *         "job": "步枪兵", "result": "WIN" }
     */
    @Transactional
    @PostMapping("/{slug}/record")
    public ResponseEntity<Map<String, Object>> submitBattleRecord(
            @PathVariable String slug,
            @RequestBody Map<String, Object> body,
            HttpSession session) {

        User user = userService.getCurrentUser(session);
        if (user == null) {
            return ResponseEntity.status(401).body(Map.of("error", "请先登录"));
        }

        Activity activity = activityService.getActivityBySlug(slug);

        try {
            Long userId = Long.valueOf(body.get("userId").toString());
            String userName = (String) body.getOrDefault("userName", "");
            int gameId = Integer.parseInt(body.get("gameId").toString());
            String team = (String) body.get("team");
            int kills = Integer.parseInt(body.getOrDefault("kills", 0).toString());
            int deaths = Integer.parseInt(body.getOrDefault("deaths", 0).toString());
            int assists = Integer.parseInt(body.getOrDefault("assists", 0).toString());
            long damage = Long.parseLong(body.getOrDefault("damage", 0).toString());
            String job = (String) body.get("job");
            String result = (String) body.get("result");

            Map<String, Object> resp = nancyDraftService.submitBattleRecord(
                    activity.getId(), userId, userName, gameId, team,
                    kills, deaths, assists, damage, job, result);
            return ResponseEntity.ok(resp);
        } catch (RuntimeException e) {
            log.warn("[南希对抗赛] 战绩录入失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 批量录入战绩（一场比赛所有玩家）
     * body: { "gameId": 1, "players": [ { "userId": 1, "userName": "xx", "team": "teamA", ... }, ... ] }
     */
    @Transactional
    @PostMapping("/{slug}/record/batch")
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, Object>> submitBatchRecords(
            @PathVariable String slug,
            @RequestBody Map<String, Object> body,
            HttpSession session) {

        User user = userService.getCurrentUser(session);
        if (user == null) {
            return ResponseEntity.status(401).body(Map.of("error", "请先登录"));
        }

        Activity activity = activityService.getActivityBySlug(slug);

        try {
            int gameId = Integer.parseInt(body.get("gameId").toString());
            List<Map<String, Object>> players = (List<Map<String, Object>>) body.get("players");
            Map<String, Object> resp = nancyDraftService.submitBatchRecords(
                    activity.getId(), gameId, players);
            return ResponseEntity.ok(resp);
        } catch (RuntimeException e) {
            log.warn("[南希对抗赛] 批量战绩录入失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
