package com.potato.peacehaven.controller;

import com.potato.peacehaven.entity.Activity;
import com.potato.peacehaven.entity.ContestWork;
import com.potato.peacehaven.entity.User;
import com.potato.peacehaven.enums.ContestPhase;
import com.potato.peacehaven.service.ActivityService;
import com.potato.peacehaven.service.ContestService;
import com.potato.peacehaven.service.OssService;
import com.potato.peacehaven.service.UserService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/contest")
@RequiredArgsConstructor
public class ContestController {

    private final ContestService contestService;
    private final ActivityService activityService;
    private final OssService ossService;
    private final UserService userService;

    /**
     * 投稿作品（上传图片 + 提交信息）
     */
    @PostMapping("/submit")
    public ResponseEntity<Map<String, Object>> submit(
            @RequestParam("image") MultipartFile image,
            @RequestParam("title") String title,
            @RequestParam(value = "description", required = false) String description,
            @RequestParam("slug") String slug,
            HttpSession session) {

        Map<String, Object> result = new HashMap<>();
        User user = userService.getCurrentUser(session);

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
            String imageUrl = ossService.uploadImage(image, "contest-works");
            Activity activity = activityService.getActivityBySlug(slug);

            ContestWork work = contestService.submitWork(
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
        User user = userService.getCurrentUser(session);

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
     * 撤回投票
     */
    @PostMapping("/unvote/{workId}")
    public ResponseEntity<Map<String, Object>> unvote(@PathVariable Long workId, HttpSession session) {
        Map<String, Object> result = new HashMap<>();
        User user = userService.getCurrentUser(session);

        if (user == null) {
            result.put("success", false);
            result.put("message", "请先登录");
            return ResponseEntity.ok(result);
        }

        try {
            contestService.retractVote(workId, user);
            result.put("success", true);
            result.put("message", "已撤回投票");
        } catch (RuntimeException e) {
            result.put("success", false);
            result.put("message", e.getMessage());
        }

        return ResponseEntity.ok(result);
    }

    /**
     * 投抽象票（每人每活动限一票，已投其他作品则自动改投）
     */
    @PostMapping("/abstract-vote/{workId}")
    public ResponseEntity<Map<String, Object>> abstractVote(@PathVariable Long workId, HttpSession session) {
        Map<String, Object> result = new HashMap<>();
        User user = userService.getCurrentUser(session);

        if (user == null) {
            result.put("success", false);
            result.put("message", "请先登录后再投票");
            return ResponseEntity.ok(result);
        }

        try {
            contestService.abstractVoteForWork(workId, user);
            result.put("success", true);
            result.put("message", "抽象票已投出！");
        } catch (RuntimeException e) {
            result.put("success", false);
            result.put("message", e.getMessage());
        }

        return ResponseEntity.ok(result);
    }

    /**
     * 撤回抽象票
     */
    @PostMapping("/abstract-unvote/{workId}")
    public ResponseEntity<Map<String, Object>> abstractUnvote(@PathVariable Long workId, HttpSession session) {
        Map<String, Object> result = new HashMap<>();
        User user = userService.getCurrentUser(session);

        if (user == null) {
            result.put("success", false);
            result.put("message", "请先登录");
            return ResponseEntity.ok(result);
        }

        try {
            contestService.retractAbstractVote(workId, user);
            result.put("success", true);
            result.put("message", "已撤回抽象票");
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
    public ResponseEntity<Map<String, Object>> deleteWork(
            @RequestParam("slug") String slug,
            HttpSession session) {
        Map<String, Object> result = new HashMap<>();
        User user = userService.getCurrentUser(session);

        if (user == null) {
            result.put("success", false);
            result.put("message", "请先登录");
            return ResponseEntity.ok(result);
        }

        try {
            Activity activity = activityService.getActivityBySlug(slug);
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
    public ResponseEntity<Map<String, Object>> getWorks(
            @RequestParam("slug") String slug,
            HttpSession session) {
        Map<String, Object> result = new HashMap<>();

        Activity activity = activityService.getActivityBySlug(slug);
        Long activityId = activity.getId();
        List<ContestWork> works = contestService.getApprovedWorks(activityId);

        // 按投稿时间排序，生成作品编号
        List<ContestWork> sortedByTime = works.stream()
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

        User user = userService.getCurrentUser(session);

        ContestPhase phase = contestService.getCurrentPhase(activityId);
        boolean showVoteCount = contestService.shouldShowVoteCount(activityId);
        boolean showJudgeScore = contestService.shouldShowJudgeScore(activityId);
        boolean isResults = (phase == ContestPhase.RESULTS);

        Map<Long, Integer> popScoreMap = new HashMap<>();
        Map<Long, Double> finalScoreMap = new HashMap<>();
        if (isResults) {
            finalScoreMap = contestService.computeFinalScores(activityId);
            popScoreMap = contestService.getPopularityScoreMap(activityId);
        }

        boolean userIsJudge = (user != null && contestService.isJudge(activityId, user.getId()));

        boolean canVote = (phase == ContestPhase.VOTING) && !userIsJudge;
        boolean canSubmit = (phase == ContestPhase.SUBMISSION) && !userIsJudge;
        boolean canDelete = (phase == ContestPhase.SUBMISSION || phase == ContestPhase.REVIEW) && !userIsJudge;

        Long abstractVotedWorkId = (user != null)
                ? contestService.getAbstractVotedWorkId(activityId, user.getId())
                : null;

        Map<Long, Integer> voteCountMap = contestService.getVoteCountMap(works);
        Map<Long, Integer> abstractVoteCountMap = contestService.getAbstractVoteCountMap(works);
        Map<Long, Double> judgeScoreMap = showJudgeScore
                ? contestService.getJudgeScoreMap(works) : new HashMap<>();

        List<ContestWork> displayList;
        if (isResults) {
            Map<Long, Double> fsMap = finalScoreMap;
            displayList = works.stream()
                    .sorted((a, b) -> {
                        double sa = fsMap.getOrDefault(a.getId(), 0.0);
                        double sb = fsMap.getOrDefault(b.getId(), 0.0);
                        return Double.compare(sb, sa);
                    })
                    .collect(Collectors.toList());
        } else {
            displayList = sortedByTime;
        }

        Map<Long, Integer> finalPopScoreMap = popScoreMap;
        Map<Long, Double> finalFsMap = finalScoreMap;
        List<Map<String, Object>> workList = displayList.stream().map(w -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", w.getId());
            m.put("title", w.getTitle());
            m.put("description", w.getDescription());
            m.put("imageUrl", w.getImageUrl());
            m.put("authorName", w.getUser().getNickname());
            m.put("authorCampName", w.getUser().getCampName());
            m.put("voteCount", showVoteCount ? voteCountMap.getOrDefault(w.getId(), 0) : -1);
            m.put("judgeScore", showJudgeScore ? judgeScoreMap.get(w.getId()) : null);
            m.put("finalScore", showJudgeScore ? finalFsMap.get(w.getId()) : null);
            m.put("popularityScore", isResults ? finalPopScoreMap.getOrDefault(w.getId(), 15) : null);
            m.put("createdAt", w.getCreatedAt() != null ? w.getCreatedAt().toString() : null);
            m.put("workNumber", workNumberMap.getOrDefault(w.getId(), 0));
            if (user != null) {
                m.put("hasVoted", contestService.hasVoted(w.getId(), user.getId()));
                m.put("hasAbstractVoted", abstractVotedWorkId != null && abstractVotedWorkId.equals(w.getId()));
            } else {
                m.put("hasVoted", false);
                m.put("hasAbstractVoted", false);
            }
            m.put("abstractVoteCount", showVoteCount ? abstractVoteCountMap.getOrDefault(w.getId(), 0) : -1);
            return m;
        }).collect(Collectors.toList());

        result.put("works", workList);
        result.put("phase", phase.name());
        result.put("phaseLabel", getPhaseLabel(phase));
        result.put("canVote", canVote);
        result.put("canSubmit", canSubmit);
        result.put("canDelete", canDelete);
        result.put("isJudge", userIsJudge);
        result.put("showVoteCount", showVoteCount);
        result.put("showJudgeScore", showJudgeScore);

        Map<String, String> configMap = contestService.getConfigMap(activityId);
        if (!configMap.isEmpty()) {
            List<Map<String, String>> milestones = new ArrayList<>();
            milestones.add(milestone("投稿开始", configMap.get("submitStart")));
            milestones.add(milestone("投稿截止", configMap.get("submitEnd")));
            milestones.add(milestone("评委打分", configMap.get("judgeStart")));
            milestones.add(milestone("打分截止", configMap.get("judgeEnd")));
            milestones.add(milestone("投票开启", configMap.get("voteStart")));
            milestones.add(milestone("投票截止", configMap.get("voteEnd")));
            result.put("milestones", milestones);
        }

        if (user != null) {
            ContestWork myWork = contestService.getUserWork(activityId, user.getId());
            if (myWork != null) {
                result.put("myWorkStatus", myWork.getStatus().name());
            }
            result.put("remainingVotes", contestService.getRemainingVotes(activityId, user.getId()));
            result.put("maxVotes", ContestService.MAX_VOTES_PER_USER);
            result.put("hasAbstractVoted", abstractVotedWorkId != null);
        }

        if (isResults && !works.isEmpty()) {
            result.put("podium", buildPodiumData(works, displayList,
                    voteCountMap, judgeScoreMap, abstractVoteCountMap, finalScoreMap));
        }

        return ResponseEntity.ok(result);
    }

    private Map<String, Object> buildPodiumData(List<ContestWork> allWorks, List<ContestWork> sortedByFinalScore,
                                                  Map<Long, Integer> voteCountMap,
                                                  Map<Long, Double> judgeScoreMap,
                                                  Map<Long, Integer> abstractVoteCountMap,
                                                  Map<Long, Double> finalScoreMap) {
        Map<String, Object> podium = new HashMap<>();

        List<Map<String, Object>> top3 = new ArrayList<>();
        for (int i = 0; i < Math.min(3, sortedByFinalScore.size()); i++) {
            ContestWork w = sortedByFinalScore.get(i);
            Map<String, Object> m = new HashMap<>();
            m.put("id", w.getId());
            m.put("title", w.getTitle());
            m.put("imageUrl", w.getImageUrl());
            m.put("authorName", w.getUser().getNickname());
            m.put("authorCampName", w.getUser().getCampName());
            m.put("finalScore", finalScoreMap.get(w.getId()));
            top3.add(m);
        }
        podium.put("top3", top3);

        ContestWork popWinner = allWorks.stream()
                .max(Comparator.comparingInt((ContestWork w) ->
                        voteCountMap.getOrDefault(w.getId(), 0)))
                .orElse(null);
        if (popWinner != null) {
            Map<String, Object> m = new HashMap<>();
            m.put("id", popWinner.getId());
            m.put("title", popWinner.getTitle());
            m.put("imageUrl", popWinner.getImageUrl());
            m.put("authorName", popWinner.getUser().getNickname());
            m.put("authorCampName", popWinner.getUser().getCampName());
            m.put("voteCount", voteCountMap.getOrDefault(popWinner.getId(), 0));
            podium.put("popularityAward", m);
        }

        ContestWork creativityWinner = allWorks.stream()
                .filter(w -> judgeScoreMap.get(w.getId()) != null)
                .max(Comparator.comparingDouble((ContestWork w) ->
                        judgeScoreMap.getOrDefault(w.getId(), 0.0)))
                .orElse(null);
        if (creativityWinner != null) {
            Map<String, Object> m = new HashMap<>();
            m.put("id", creativityWinner.getId());
            m.put("title", creativityWinner.getTitle());
            m.put("imageUrl", creativityWinner.getImageUrl());
            m.put("authorName", creativityWinner.getUser().getNickname());
            m.put("authorCampName", creativityWinner.getUser().getCampName());
            m.put("judgeScore", judgeScoreMap.get(creativityWinner.getId()));
            podium.put("creativityAward", m);
        }

        ContestWork abstractWinner = allWorks.stream()
                .max(Comparator.comparingInt((ContestWork w) ->
                        abstractVoteCountMap.getOrDefault(w.getId(), 0)))
                .orElse(null);
        if (abstractWinner != null && abstractVoteCountMap.getOrDefault(abstractWinner.getId(), 0) > 0) {
            Map<String, Object> m = new HashMap<>();
            m.put("id", abstractWinner.getId());
            m.put("title", abstractWinner.getTitle());
            m.put("imageUrl", abstractWinner.getImageUrl());
            m.put("authorName", abstractWinner.getUser().getNickname());
            m.put("authorCampName", abstractWinner.getUser().getCampName());
            m.put("abstractVoteCount", abstractVoteCountMap.getOrDefault(abstractWinner.getId(), 0));
            podium.put("abstractAward", m);
        }

        return podium;
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

    private Map<String, String> milestone(String label, String dateTimeStr) {
        Map<String, String> m = new HashMap<>();
        m.put("label", label);
        if (dateTimeStr != null && !dateTimeStr.isEmpty()) {
            try {
                java.time.LocalDateTime dt = java.time.LocalDateTime.parse(dateTimeStr,
                        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"));
                m.put("time", dt.format(java.time.format.DateTimeFormatter.ofPattern("M/d HH:mm")));
            } catch (Exception e) {
                m.put("time", dateTimeStr);
            }
        } else {
            m.put("time", null);
        }
        return m;
    }

    // ==================== 裁判评分 API ====================

    /**
     * 获取裁判视角的作品列表（含评分状态）
     */
    @GetMapping("/judge/works")
    public ResponseEntity<Map<String, Object>> getJudgeWorks(
            @RequestParam("slug") String slug,
            HttpSession session) {
        Map<String, Object> result = new HashMap<>();
        User user = userService.getCurrentUser(session);

        if (user == null) {
            result.put("success", false);
            result.put("message", "请先登录");
            return ResponseEntity.ok(result);
        }

        Activity activity = activityService.getActivityBySlug(slug);
        Long activityId = activity.getId();

        if (!contestService.isJudge(activityId, user.getId())) {
            result.put("success", false);
            result.put("message", "您不是本次活动的裁判");
            return ResponseEntity.ok(result);
        }

        ContestPhase phase = contestService.getCurrentPhase(activityId);
        List<ContestWork> works = contestService.getApprovedWorks(activityId);

        List<ContestWork> sortedByTimeJ = works.stream()
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
            Double myScore = contestService.getJudgeScoreForWork(w.getId(), user.getId());
            m.put("myScore", myScore);
            m.put("hasScored", myScore != null);
            return m;
        }).collect(Collectors.toList());

        result.put("success", true);
        result.put("works", workList);
        result.put("phase", phase.name());
        result.put("canScore", phase == ContestPhase.JUDGING);

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
        User user = userService.getCurrentUser(session);

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
