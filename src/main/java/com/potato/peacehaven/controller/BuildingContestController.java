package com.potato.peacehaven.controller;

import com.potato.peacehaven.config.AdminInterceptor;
import com.potato.peacehaven.entity.Activity;
import com.potato.peacehaven.entity.BuildingContestWork;
import com.potato.peacehaven.entity.User;
import com.potato.peacehaven.service.ActivityService;
import com.potato.peacehaven.service.BuildingContestService;
import com.potato.peacehaven.service.OssService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/contest")
@RequiredArgsConstructor
public class BuildingContestController {

    private final BuildingContestService contestService;
    private final ActivityService activityService;
    private final OssService ossService;

    /**
     * 投稿作品（上传图片 + 提交信息）
     */
    @PostMapping("/submit")
    public ResponseEntity<Map<String, Object>> submit(
            @RequestParam("image") MultipartFile image,
            @RequestParam("title") String title,
            @RequestParam(value = "description", required = false) String description,
            HttpSession session) {

        Map<String, Object> result = new HashMap<>();
        User user = (User) session.getAttribute(AdminInterceptor.SESSION_USER_KEY);

        if (user == null) {
            result.put("success", false);
            result.put("message", "请先登录");
            return ResponseEntity.ok(result);
        }

        if (title == null || title.trim().isEmpty()) {
            result.put("success", false);
            result.put("message", "作品标题不能为空");
            return ResponseEntity.ok(result);
        }

        if (title.trim().length() > 50) {
            result.put("success", false);
            result.put("message", "作品标题不能超过50个字符");
            return ResponseEntity.ok(result);
        }

        try {
            // 上传图片到OSS
            String imageUrl = ossService.uploadImage(image, "building-contest");

            // 获取建筑大赛活动ID
            Activity activity = activityService.getActivityBySlug("building-master-1");

            // 保存投稿
            BuildingContestWork work = contestService.submitWork(
                    activity.getId(), user, title.trim(),
                    description != null ? description.trim() : null,
                    imageUrl);

            result.put("success", true);
            result.put("message", "投稿成功！请等待管理员审核");
            result.put("workId", work.getId());
        } catch (IllegalArgumentException e) {
            result.put("success", false);
            result.put("message", e.getMessage());
        } catch (RuntimeException e) {
            result.put("success", false);
            result.put("message", e.getMessage());
        } catch (Exception e) {
            log.error("投稿失败: {}", e.getMessage(), e);
            result.put("success", false);
            result.put("message", "投稿失败，请稍后重试");
        }

        return ResponseEntity.ok(result);
    }

    /**
     * 为作品投票
     */
    @PostMapping("/vote/{workId}")
    public ResponseEntity<Map<String, Object>> vote(@PathVariable Long workId, HttpSession session) {
        Map<String, Object> result = new HashMap<>();
        User user = (User) session.getAttribute(AdminInterceptor.SESSION_USER_KEY);

        if (user == null) {
            result.put("success", false);
            result.put("message", "请先登录后再投票");
            return ResponseEntity.ok(result);
        }

        try {
            contestService.voteForWork(workId, user);
            result.put("success", true);
            result.put("message", "投票成功！");
        } catch (RuntimeException e) {
            result.put("success", false);
            result.put("message", e.getMessage());
        }

        return ResponseEntity.ok(result);
    }

    /**
     * 获取已通过审核的作品列表
     */
    @GetMapping("/works")
    public ResponseEntity<Map<String, Object>> getWorks(HttpSession session) {
        Map<String, Object> result = new HashMap<>();

        Activity activity = activityService.getActivityBySlug("building-master-1");
        List<BuildingContestWork> works = contestService.getApprovedWorks(activity.getId());

        User user = (User) session.getAttribute(AdminInterceptor.SESSION_USER_KEY);

        List<Map<String, Object>> workList = works.stream().map(w -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", w.getId());
            m.put("title", w.getTitle());
            m.put("description", w.getDescription());
            m.put("imageUrl", w.getImageUrl());
            m.put("authorName", w.getUser().getNickname());
            m.put("voteCount", w.getVoteCount());
            m.put("createdAt", w.getCreatedAt() != null ? w.getCreatedAt().toString() : null);
            // 标记当前用户是否已投票
            if (user != null) {
                m.put("hasVoted", contestService.hasVoted(w.getId(), user.getId()));
            } else {
                m.put("hasVoted", false);
            }
            return m;
        }).collect(Collectors.toList());

        result.put("works", workList);

        // 当前用户投稿状态
        if (user != null) {
            BuildingContestWork myWork = contestService.getUserWork(activity.getId(), user.getId());
            if (myWork != null) {
                result.put("myWorkStatus", myWork.getStatus().name());
            }
        }

        return ResponseEntity.ok(result);
    }
}
