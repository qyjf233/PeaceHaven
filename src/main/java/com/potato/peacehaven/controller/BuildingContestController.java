package com.potato.peacehaven.controller;

import com.potato.peacehaven.entity.BuildingContestConfig;
import com.potato.peacehaven.entity.BuildingContestConfig.ContestPhase;
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
            Activity activity = activityService.getActivityBySlug("building-master-1");
            result.put("success", true);
            result.put("message", "投票成功！");
            result.put("remainingVotes", contestService.getRemainingVotes(activity.getId(), user.getId()));
        } catch (RuntimeException e) {
            result.put("success", false);
            result.put("message", e.getMessage());
        }

        return ResponseEntity.ok(result);
    }

    /**
     * 撤回投票
     */
    @PostMapping("/unvote/{workId}")
    public ResponseEntity<Map<String, Object>> unvote(@PathVariable Long workId, HttpSession session) {
        Map<String, Object> result = new HashMap<>();
        User user = (User) session.getAttribute(AdminInterceptor.SESSION_USER_KEY);

        if (user == null) {
            result.put("success", false);
            result.put("message", "请先登录");
            return ResponseEntity.ok(result);
        }

        try {
            contestService.retractVote(workId, user);
            Activity activity = activityService.getActivityBySlug("building-master-1");
            result.put("success", true);
            result.put("message", "已撤回投票");
            result.put("remainingVotes", contestService.getRemainingVotes(activity.getId(), user.getId()));
        } catch (RuntimeException e) {
            result.put("success", false);
            result.put("message", e.getMessage());
        }

        return ResponseEntity.ok(result);
    }

    /**
     * 删除自己的投稿作品
     */
    @PostMapping("/delete-work")
    public ResponseEntity<Map<String, Object>> deleteWork(HttpSession session) {
        Map<String, Object> result = new HashMap<>();
        User user = (User) session.getAttribute(AdminInterceptor.SESSION_USER_KEY);

        if (user == null) {
            result.put("success", false);
            result.put("message", "请先登录");
            return ResponseEntity.ok(result);
        }

        try {
            Activity activity = activityService.getActivityBySlug("building-master-1");
            boolean wasApproved = contestService.deleteOwnWork(activity.getId(), user.getId());
            result.put("success", true);
            result.put("message", wasApproved ? "作品已删除，关联投票记录已一并清除" : "作品已删除");
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
        Long activityId = activity.getId();
        List<BuildingContestWork> works = contestService.getApprovedWorks(activityId);

        // 按投稿时间排序，生成作品编号
        List<BuildingContestWork> sortedByTime = works.stream()
                .sorted((a, b) -> {
                    if (a.getCreatedAt() == null && b.getCreatedAt() == null) return 0;
                    if (a.getCreatedAt() == null) return 1;
                    if (b.getCreatedAt() == null) return -1;
                    return a.getCreatedAt().compareTo(b.getCreatedAt());
                })
                .collect(Collectors.toList());
        Map<Long, Integer> workNumberMap = new HashMap<>();
        for (int i = 0; i < sortedByTime.size(); i++) {
            workNumberMap.put(sortedByTime.get(i).getId(), i + 1);
        }

        User user = (User) session.getAttribute(AdminInterceptor.SESSION_USER_KEY);

        // 获取当前阶段
        ContestPhase phase = contestService.getCurrentPhase(activityId);
        boolean showVoteCount = contestService.shouldShowVoteCount(activityId);
        boolean showJudgeScore = contestService.shouldShowJudgeScore(activityId);

        // 裁判身份检查
        boolean userIsJudge = (user != null && contestService.isJudge(activityId, user.getId()));

        boolean canVote = (phase == ContestPhase.VOTING) && !userIsJudge;
        boolean canSubmit = (phase == ContestPhase.SUBMISSION) && !userIsJudge;
        boolean canDelete = (phase == ContestPhase.SUBMISSION || phase == ContestPhase.REVIEW) && !userIsJudge;

        List<Map<String, Object>> workList = works.stream().map(w -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", w.getId());
            m.put("title", w.getTitle());
            m.put("description", w.getDescription());
            m.put("imageUrl", w.getImageUrl());
            m.put("authorName", w.getUser().getNickname());
            // 根据阶段控制票数显示
            m.put("voteCount", showVoteCount ? w.getVoteCount() : -1);
            // 评委分数仅在 RESULTS 阶段显示
            m.put("judgeScore", showJudgeScore ? w.getJudgeScore() : null);
            m.put("finalScore", showJudgeScore ? w.getFinalScore() : null);
            m.put("createdAt", w.getCreatedAt() != null ? w.getCreatedAt().toString() : null);
            m.put("workNumber", workNumberMap.getOrDefault(w.getId(), 0));
            // 标记当前用户是否已投票
            if (user != null) {
                m.put("hasVoted", contestService.hasVoted(w.getId(), user.getId()));
            } else {
                m.put("hasVoted", false);
            }
            return m;
        }).collect(Collectors.toList());

        result.put("works", workList);

        // 阶段信息
        result.put("phase", phase.name());
        result.put("phaseLabel", getPhaseLabel(phase));
        result.put("canVote", canVote);
        result.put("canSubmit", canSubmit);
        result.put("canDelete", canDelete);
        result.put("isJudge", userIsJudge);
        result.put("showVoteCount", showVoteCount);
        result.put("showJudgeScore", showJudgeScore);

        // 时间节点（供进程条显示）
        BuildingContestConfig config = contestService.getConfig(activityId);
        if (config != null) {
            List<Map<String, String>> milestones = new java.util.ArrayList<>();
            milestones.add(milestone("投稿开始", config.getSubmitStart()));
            milestones.add(milestone("投稿截止", config.getSubmitEnd()));
            milestones.add(milestone("评委打分", config.getJudgeStart()));
            milestones.add(milestone("打分截止", config.getJudgeEnd()));
            milestones.add(milestone("投票开启", config.getVoteStart()));
            milestones.add(milestone("投票截止", config.getVoteEnd()));
            result.put("milestones", milestones);
        }

        // 当前用户投稿状态 + 剩余票数
        if (user != null) {
            BuildingContestWork myWork = contestService.getUserWork(activityId, user.getId());
            if (myWork != null) {
                result.put("myWorkStatus", myWork.getStatus().name());
            }
            result.put("remainingVotes", contestService.getRemainingVotes(activityId, user.getId()));
            result.put("maxVotes", BuildingContestService.MAX_VOTES_PER_USER);
        }

        return ResponseEntity.ok(result);
    }

    private String getPhaseLabel(ContestPhase phase) {
        return switch (phase) {
            case BEFORE_START -> "活动未开始";
            case SUBMISSION -> "投稿阶段";
            case REVIEW -> "作品审核中";
            case JUDGING -> "评委打分中";
            case PRE_VOTE -> "等待投票";
            case VOTING -> "投票进行中";
            case RESULTS -> "结果已公布";
        };
    }

    private Map<String, String> milestone(String label, java.time.LocalDateTime time) {
        Map<String, String> m = new HashMap<>();
        m.put("label", label);
        m.put("time", time != null ? time.format(java.time.format.DateTimeFormatter.ofPattern("M/d HH:mm")) : null);
        return m;
    }

    // ==================== 裁判评分 API ====================

    /**
     * 获取裁判视角的作品列表（含评分状态）
     */
    @GetMapping("/judge/works")
    public ResponseEntity<Map<String, Object>> getJudgeWorks(HttpSession session) {
        Map<String, Object> result = new HashMap<>();
        User user = (User) session.getAttribute(AdminInterceptor.SESSION_USER_KEY);

        if (user == null) {
            result.put("success", false);
            result.put("message", "请先登录");
            return ResponseEntity.ok(result);
        }

        Activity activity = activityService.getActivityBySlug("building-master-1");
        Long activityId = activity.getId();

        // 裁判身份检查
        if (!contestService.isJudge(activityId, user.getId())) {
            result.put("success", false);
            result.put("message", "您不是本次活动的裁判");
            return ResponseEntity.ok(result);
        }

        ContestPhase phase = contestService.getCurrentPhase(activityId);
        List<BuildingContestWork> works = contestService.getApprovedWorks(activityId);

        // 按投稿时间排序，生成作品编号
        List<BuildingContestWork> sortedByTimeJ = works.stream()
                .sorted((a, b) -> {
                    if (a.getCreatedAt() == null && b.getCreatedAt() == null) return 0;
                    if (a.getCreatedAt() == null) return 1;
                    if (b.getCreatedAt() == null) return -1;
                    return a.getCreatedAt().compareTo(b.getCreatedAt());
                })
                .collect(Collectors.toList());
        Map<Long, Integer> workNumberMapJ = new HashMap<>();
        for (int i = 0; i < sortedByTimeJ.size(); i++) {
            workNumberMapJ.put(sortedByTimeJ.get(i).getId(), i + 1);
        }

        List<Map<String, Object>> workList = works.stream().map(w -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", w.getId());
            m.put("title", w.getTitle());
            m.put("description", w.getDescription());
            m.put("imageUrl", w.getImageUrl());
            m.put("authorName", w.getUser().getNickname());
            m.put("authorCampName", w.getUser().getCampName());
            m.put("workNumber", workNumberMapJ.getOrDefault(w.getId(), 0));
            // 该裁判是否已评分
            Double myScore = contestService.getJudgeScoreForWork(w.getId(), user.getId());
            m.put("myScore", myScore);
            m.put("hasScored", myScore != null);
            return m;
        }).collect(Collectors.toList());

        result.put("success", true);
        result.put("works", workList);
        result.put("phase", phase.name());
        result.put("canScore", phase == ContestPhase.JUDGING);

        // 评分进度
        int[] progress = contestService.getJudgeProgress(activityId, user.getId());
        result.put("scoredCount", progress[0]);
        result.put("totalCount", progress[1]);

        return ResponseEntity.ok(result);
    }

    /**
     * 裁判提交评分
     */
    @PostMapping("/judge/score/{workId}")
    public ResponseEntity<Map<String, Object>> submitScore(
            @PathVariable Long workId,
            @RequestBody Map<String, Object> body,
            HttpSession session) {
        Map<String, Object> result = new HashMap<>();
        User user = (User) session.getAttribute(AdminInterceptor.SESSION_USER_KEY);

        if (user == null) {
            result.put("success", false);
            result.put("message", "请先登录");
            return ResponseEntity.ok(result);
        }

        try {
            Object scoreObj = body.get("score");
            if (scoreObj == null) {
                result.put("success", false);
                result.put("message", "请输入分数");
                return ResponseEntity.ok(result);
            }

            double score = Double.parseDouble(scoreObj.toString());
            contestService.submitJudgeScore(workId, user, score);

            result.put("success", true);
            result.put("message", "评分成功");
            result.put("score", score);
        } catch (NumberFormatException e) {
            result.put("success", false);
            result.put("message", "分数格式错误，请输入 0~10 的数字");
        } catch (RuntimeException e) {
            result.put("success", false);
            result.put("message", e.getMessage());
        }

        return ResponseEntity.ok(result);
    }
}
