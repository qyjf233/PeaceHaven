package com.potato.peacehaven.controller;

import com.potato.peacehaven.entity.Activity;
import com.potato.peacehaven.entity.PvpRegistration;
import com.potato.peacehaven.entity.SwissMatch;
import com.potato.peacehaven.entity.User;
import com.potato.peacehaven.repository.ActivityJudgeRepository;
import com.potato.peacehaven.repository.PvpRegistrationRepository;
import com.potato.peacehaven.service.ActivityService;
import com.potato.peacehaven.service.EliminationService;
import com.potato.peacehaven.service.SwissRoundService;
import com.potato.peacehaven.service.UserService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * PVP 通用报名 API + Swiss 瑞士轮赛事 API
 */
@Slf4j
@RestController
@RequestMapping("/api/pvp")
@RequiredArgsConstructor
public class PvpRegistrationController {

    private final PvpRegistrationRepository registrationRepository;
    private final ActivityService activityService;
    private final UserService userService;
    private final SwissRoundService swissRoundService;
    private final EliminationService eliminationService;
    private final ActivityJudgeRepository judgeRepository;

    /**
     * 获取报名状态（是否已报名 + 当前报名人数）
     */
    @GetMapping("/{slug}/status")
    public ResponseEntity<Map<String, Object>> getStatus(@PathVariable String slug, HttpSession session) {
        Activity activity = activityService.getActivityBySlug(slug);
        long totalRegistered = registrationRepository.countByActivityId(activity.getId());

        User user = userService.getCurrentUser(session);
        boolean isRegistered = false;
        String campName = null;
        if (user != null) {
            isRegistered = registrationRepository.existsByActivityIdAndUserId(activity.getId(), user.getId());
            campName = user.getCampName();
        }

        Map<String, Object> result = new HashMap<>();
        result.put("totalRegistered", totalRegistered);
        result.put("isRegistered", isRegistered);
        result.put("campName", campName);
        return ResponseEntity.ok(result);
    }

    /**
     * 报名
     */
    @PostMapping("/{slug}/register")
    public ResponseEntity<Map<String, Object>> register(@PathVariable String slug, HttpSession session) {
        User user = userService.getCurrentUser(session);
        if (user == null) {
            return ResponseEntity.status(401).body(Map.of("error", "请先登录"));
        }

        Activity activity = activityService.getActivityBySlug(slug);

        // 校验营地名：仅限长安成员参赛
        if (!"长安".equals(user.getCampName())) {
            return ResponseEntity.badRequest().body(Map.of("error", "本活动仅限长安成员参赛"));
        }

        if (registrationRepository.existsByActivityIdAndUserId(activity.getId(), user.getId())) {
            return ResponseEntity.badRequest().body(Map.of("error", "你已经报名了"));
        }

        PvpRegistration reg = PvpRegistration.builder()
                .activityId(activity.getId())
                .user(user)
                .build();
        registrationRepository.save(reg);

        long totalRegistered = registrationRepository.countByActivityId(activity.getId());
        log.info("用户 {} 报名活动 {} (ID:{})", user.getNickname(), slug, activity.getId());

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("totalRegistered", totalRegistered);
        return ResponseEntity.ok(result);
    }

    /**
     * 取消报名
     */
    @PostMapping("/{slug}/cancel")
    public ResponseEntity<Map<String, Object>> cancel(@PathVariable String slug, HttpSession session) {
        User user = userService.getCurrentUser(session);
        if (user == null) {
            return ResponseEntity.status(401).body(Map.of("error", "请先登录"));
        }

        Activity activity = activityService.getActivityBySlug(slug);

        var reg = registrationRepository.findByActivityIdAndUserId(activity.getId(), user.getId());
        if (reg.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "你还没有报名"));
        }

        registrationRepository.delete(reg.get());
        registrationRepository.flush();

        long totalRegistered = registrationRepository.countByActivityId(activity.getId());
        log.info("用户 {} 取消报名活动 {} (ID:{})", user.getNickname(), slug, activity.getId());

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("totalRegistered", totalRegistered);
        return ResponseEntity.ok(result);
    }

    /**
     * 排行榜（按积分降序，积分相同按胜场降序）
     */
    @Transactional(readOnly = true)
    @GetMapping("/{slug}/leaderboard")
    public ResponseEntity<Map<String, Object>> leaderboard(@PathVariable String slug) {
        Activity activity = activityService.getActivityBySlug(slug);
        var regs = registrationRepository.findByActivityIdOrderByPointsDescWinsDesc(activity.getId());

        List<Map<String, Object>> list = new ArrayList<>();
        int rank = 0;
        for (PvpRegistration r : regs) {
            rank++;
            Map<String, Object> m = new HashMap<>();
            m.put("rank", rank);
            m.put("nickname", r.getUser().getNickname());
            m.put("wins", r.getWins());
            m.put("losses", r.getLosses());
            m.put("points", r.getPoints());
            list.add(m);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("rankings", list);
        return ResponseEntity.ok(result);
    }

    /**
     * 通用排行榜 - 返回所有字段，前端自行排序和选择显示列
     */
    @Transactional(readOnly = true)
    @GetMapping("/{slug}/rankings")
    public ResponseEntity<Map<String, Object>> rankings(@PathVariable String slug) {
        Activity activity = activityService.getActivityBySlug(slug);
        var regs = registrationRepository.findByActivityIdOrderByCreatedAtAsc(activity.getId());

        List<Map<String, Object>> list = new ArrayList<>();
        for (PvpRegistration r : regs) {
            Map<String, Object> m = new HashMap<>();
            m.put("nickname", r.getUser().getNickname());
            m.put("campName", r.getUser().getCampName());
            m.put("avatar", r.getUser().getAvatar());
            m.put("wins", r.getWins());
            m.put("losses", r.getLosses());
            m.put("points", r.getPoints());
            m.put("rankNum", r.getRankNum());
            m.put("completion", r.getCompletion());
            list.add(m);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("rankings", list);
        return ResponseEntity.ok(result);
    }

    // ==================== Swiss 瑞士轮赛事 API ====================

    /**
     * 获取当前 Swiss 阶段状态（当前轮次 + 比赛列表）
     * <p>
     * 懒初始化：当 Swiss 阶段已开始但尚无比赛数据时，自动触发第1轮分组。
     * </p>
     */
    @Transactional
    @GetMapping("/{slug}/swiss/status")
    public ResponseEntity<Map<String, Object>> getSwissStatus(
            @PathVariable String slug, HttpSession session) {
        Activity activity = activityService.getActivityBySlug(slug);
        User currentUser = userService.getCurrentUser(session);

        // 懒初始化：若尚无比赛记录且有足够选手，自动初始化第1轮
        int currentRound = swissRoundService.getCurrentRound(activity.getId());
        if (currentRound == 0) {
            long totalRegistered = registrationRepository.countByActivityId(activity.getId());
            if (totalRegistered >= 2) {
                try {
                    swissRoundService.initRound(activity.getId(), 1);
                } catch (Exception e) {
                    // 并发初始化时唯一约束冲突，忽略异常，直接读取已创建的比赛
                    log.info("轮次1初始化被并发拦截（唯一约束），读取已有数据");
                }
            }
        }

        Map<String, Object> status = swissRoundService.getSwissStatus(activity.getId(), currentUser);
        return ResponseEntity.ok(status);
    }

    /**
     * 获取完整赛程（所有轮次）
     */
    @Transactional(readOnly = true)
    @GetMapping("/{slug}/swiss/matches")
    public ResponseEntity<Map<String, Object>> getAllMatches(@PathVariable String slug) {
        Activity activity = activityService.getActivityBySlug(slug);
        List<Map<String, Object>> matches = swissRoundService.getAllMatches(activity.getId());

        Map<String, Object> result = new HashMap<>();
        result.put("matches", matches);
        return ResponseEntity.ok(result);
    }

    /**
     * 裁判提交比赛结果
     * <p>
     * body: { "matchId": 123, "winnerId": 456 }
     * </p>
     */
    @PostMapping("/{slug}/swiss/submit")
    public ResponseEntity<Map<String, Object>> submitResult(
            @PathVariable String slug,
            @RequestBody Map<String, Object> body,
            HttpSession session) {

        User user = userService.getCurrentUser(session);
        if (user == null) {
            return ResponseEntity.status(401).body(Map.of("error", "请先登录"));
        }

        Activity activity = activityService.getActivityBySlug(slug);

        // 解析参数
        Object matchIdObj = body.get("matchId");
        Object winnerIdObj = body.get("winnerId");

        if (matchIdObj == null || winnerIdObj == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "缺少参数 matchId 或 winnerId"));
        }

        try {
            Long matchId = Long.valueOf(matchIdObj.toString());
            Long winnerId = Long.valueOf(winnerIdObj.toString());

            SwissMatch updatedMatch = swissRoundService.submitResult(
                    activity.getId(), matchId, winnerId, user);

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("winnerName", updatedMatch.getWinnerName());
            result.put("roundNumber", updatedMatch.getRoundNumber());
            result.put("message", "成绩提交成功");
            return ResponseEntity.ok(result);

        } catch (RuntimeException e) {
            log.warn("成绩提交失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 裁判查看自己负责的比赛
     */
    @GetMapping("/{slug}/swiss/my-matches")
    public ResponseEntity<Map<String, Object>> getMyMatches(
            @PathVariable String slug, HttpSession session) {

        User user = userService.getCurrentUser(session);
        if (user == null) {
            return ResponseEntity.status(401).body(Map.of("error", "请先登录"));
        }

        Activity activity = activityService.getActivityBySlug(slug);

        // 检查裁判身份
        if (!judgeRepository.existsByActivityIdAndUserId(activity.getId(), user.getId())) {
            return ResponseEntity.status(403).body(Map.of("error", "您不是本次活动的裁判"));
        }

        List<Map<String, Object>> matches = swissRoundService.getMyMatches(
                activity.getId(), user.getId());

        Map<String, Object> result = new HashMap<>();
        result.put("matches", matches);
        return ResponseEntity.ok(result);
    }

    // ==================== Elimination 淘汰赛 API ====================

    /**
     * 获取淘汰赛状态（含完整 bracket 数据）
     */
    @Transactional
    @GetMapping("/{slug}/elimination/status")
    public ResponseEntity<Map<String, Object>> getEliminationStatus(
            @PathVariable String slug, HttpSession session) {
        Activity activity = activityService.getActivityBySlug(slug);
        User currentUser = userService.getCurrentUser(session);

        Map<String, Object> status = eliminationService.getEliminationStatus(activity.getId(), currentUser);
        return ResponseEntity.ok(status);
    }

    /**
     * 裁判提交淘汰赛成绩（BO1直接结束 / BO3小局计分）
     * <p>
     * body: { "matchId": 123, "winnerId": 456 }
     * </p>
     */
    @PostMapping("/{slug}/elimination/submit")
    public ResponseEntity<Map<String, Object>> submitEliminationResult(
            @PathVariable String slug,
            @RequestBody Map<String, Object> body,
            HttpSession session) {

        User user = userService.getCurrentUser(session);
        if (user == null) {
            return ResponseEntity.status(401).body(Map.of("error", "请先登录"));
        }

        Activity activity = activityService.getActivityBySlug(slug);

        Object matchIdObj = body.get("matchId");
        Object winnerIdObj = body.get("winnerId");

        if (matchIdObj == null || winnerIdObj == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "缺少参数 matchId 或 winnerId"));
        }

        try {
            Long matchId = Long.valueOf(matchIdObj.toString());
            Long winnerId = Long.valueOf(winnerIdObj.toString());

            SwissMatch updatedMatch = eliminationService.submitGameResult(
                    activity.getId(), matchId, winnerId, user);

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("winnerName", updatedMatch.getWinnerName());
            result.put("stage", updatedMatch.getStage());
            result.put("status", updatedMatch.getStatus());
            result.put("player1GameWin", updatedMatch.getPlayer1GameWin());
            result.put("player2GameWin", updatedMatch.getPlayer2GameWin());
            result.put("message", "COMPLETED".equals(updatedMatch.getStatus()) ? "比赛结束" : "本局成绩已记录");
            return ResponseEntity.ok(result);

        } catch (RuntimeException e) {
            log.warn("淘汰赛成绩提交失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 裁判查看自己负责的淘汰赛比赛
     */
    @GetMapping("/{slug}/elimination/my-matches")
    public ResponseEntity<Map<String, Object>> getMyEliminationMatches(
            @PathVariable String slug, HttpSession session) {

        User user = userService.getCurrentUser(session);
        if (user == null) {
            return ResponseEntity.status(401).body(Map.of("error", "请先登录"));
        }

        Activity activity = activityService.getActivityBySlug(slug);

        if (!judgeRepository.existsByActivityIdAndUserId(activity.getId(), user.getId())) {
            return ResponseEntity.status(403).body(Map.of("error", "您不是本次活动的裁判"));
        }

        List<Map<String, Object>> matches = eliminationService.getMyEliminationMatches(
                activity.getId(), user.getId());

        Map<String, Object> result = new HashMap<>();
        result.put("matches", matches);
        return ResponseEntity.ok(result);
    }
}
