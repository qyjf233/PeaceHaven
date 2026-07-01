package com.potato.peacehaven.controller;

import com.potato.peacehaven.entity.Activity;
import com.potato.peacehaven.entity.LeaderboardEntry;
import com.potato.peacehaven.entity.VoteOption;
import com.potato.peacehaven.enums.ActivityStatus;
import com.potato.peacehaven.service.ActivityService;
import com.potato.peacehaven.service.VoteService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Controller
@RequiredArgsConstructor
public class ActivityController {

    private final ActivityService activityService;
    private final VoteService voteService;

    /**
     * 活动列表页（公开）- 只返回模板，数据由前端 AJAX 加载
     */
    @GetMapping("/activities")
    public String listPage() {
        return "activity-list";
    }

    /**
     * 活动列表 JSON API - 支持筛选 + 分页，供前端 AJAX 调用
     */
    @GetMapping("/api/activities")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> listApi(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "9") int size) {

        ActivityStatus filterStatus = null;
        if (status != null && !status.isEmpty()) {
            try {
                filterStatus = ActivityStatus.valueOf(status.toUpperCase());
            } catch (IllegalArgumentException ignored) {}
        }

        Page<Activity> activityPage = activityService.getActivities(filterStatus, page, size);

        List<Map<String, Object>> list = activityPage.getContent().stream().map(a -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", a.getId());
            m.put("title", a.getTitle());
            m.put("summary", a.getSummary());
            m.put("thumbnail", a.getThumbnail());
            m.put("status", a.getStatus().name());
            m.put("templateType", a.getTemplateType().name());
            m.put("startDate", a.getStartDate() != null ? a.getStartDate().toString() : null);
            m.put("endDate", a.getEndDate() != null ? a.getEndDate().toString() : null);
            return m;
        }).collect(Collectors.toList());

        Map<String, Object> result = new HashMap<>();
        result.put("activities", list);
        result.put("currentPage", activityPage.getNumber());
        result.put("totalPages", activityPage.getTotalPages());
        result.put("totalElements", activityPage.getTotalElements());
        return ResponseEntity.ok(result);
    }

    /**
     * 活动详情页（公开）- 根据模板类型渲染不同内容
     */
    @GetMapping("/activities/{id}")
    public String detail(@PathVariable Long id, Model model) {
        Activity activity = activityService.getActivityById(id);
        model.addAttribute("activity", activity);

        // Load template-specific data
        switch (activity.getTemplateType()) {
            case VOTE:
                List<VoteOption> voteOptions = activityService.getVoteOptions(id);
                int totalVotes = voteOptions.stream().mapToInt(VoteOption::getVoteCount).sum();
                model.addAttribute("voteOptions", voteOptions);
                model.addAttribute("totalVotes", totalVotes);
                break;
            case LEADERBOARD:
                List<LeaderboardEntry> leaderboard = activityService.getLeaderboard(id);
                model.addAttribute("leaderboardEntries", leaderboard);
                break;
            default:
                break;
        }

        return "activity-detail";
    }

    /**
     * 投票提交（POST）
     */
    @PostMapping("/activities/{id}/vote")
    public String vote(
            @PathVariable Long id,
            @RequestParam Long optionId,
            @RequestParam String voterName,
            RedirectAttributes redirect) {

        String message = voteService.submitVote(id, optionId, voterName);
        redirect.addFlashAttribute("voteMessage", message);
        return "redirect:/activities/" + id;
    }

    /**
     * 排行榜数据（JSON API）- 供前端动态刷新使用
     */
    @GetMapping("/api/activities/{id}/leaderboard")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> leaderboardApi(@PathVariable Long id) {
        Activity activity = activityService.getActivityById(id);
        List<LeaderboardEntry> entries = activityService.getLeaderboard(id);

        List<Map<String, Object>> list = entries.stream().map(e -> {
            Map<String, Object> m = new HashMap<>();
            m.put("rank", e.getRankPosition());
            m.put("playerName", e.getPlayerName());
            m.put("score", e.getScore());
            return m;
        }).collect(Collectors.toList());

        Map<String, Object> result = new HashMap<>();
        result.put("activityTitle", activity.getTitle());
        result.put("entries", list);
        return ResponseEntity.ok(result);
    }
}
