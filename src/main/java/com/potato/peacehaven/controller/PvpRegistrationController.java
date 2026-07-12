package com.potato.peacehaven.controller;

import com.potato.peacehaven.config.AdminInterceptor;
import com.potato.peacehaven.entity.Activity;
import com.potato.peacehaven.entity.PvpRegistration;
import com.potato.peacehaven.entity.User;
import com.potato.peacehaven.repository.PvpRegistrationRepository;
import com.potato.peacehaven.service.ActivityService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * PVP 通用报名 API
 */
@Slf4j
@RestController
@RequestMapping("/api/pvp")
@RequiredArgsConstructor
public class PvpRegistrationController {

    private final PvpRegistrationRepository registrationRepository;
    private final ActivityService activityService;

    /**
     * 获取报名状态（是否已报名 + 当前报名人数）
     */
    @GetMapping("/{slug}/status")
    public ResponseEntity<Map<String, Object>> getStatus(@PathVariable String slug, HttpSession session) {
        Activity activity = activityService.getActivityBySlug(slug);
        long totalRegistered = registrationRepository.countByActivityId(activity.getId());

        User user = (User) session.getAttribute(AdminInterceptor.SESSION_USER_KEY);
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
        User user = (User) session.getAttribute(AdminInterceptor.SESSION_USER_KEY);
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
        User user = (User) session.getAttribute(AdminInterceptor.SESSION_USER_KEY);
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
}
