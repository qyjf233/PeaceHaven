package com.potato.peacehaven.controller;

import com.potato.peacehaven.entity.Activity;
import com.potato.peacehaven.repository.ActivityConfigRepository;
import com.potato.peacehaven.repository.BuildingContestJudgeRepository;
import com.potato.peacehaven.service.ActivityService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Controller
@RequiredArgsConstructor
public class ActivityController {

    private final ActivityService activityService;
    private final BuildingContestJudgeRepository judgeRepository;
    private final ActivityConfigRepository configRepository;

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

        Page<Activity> activityPage = activityService.getActivities(status, page, size);

        List<Map<String, Object>> list = activityPage.getContent().stream().map(a -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", a.getId());
            m.put("slug", a.getSlug());
            m.put("title", a.getTitle());
            m.put("summary", a.getSummary());
            m.put("thumbnail", a.getThumbnail());
            m.put("status", activityService.getStatus(a));
            m.put("viewCount", a.getViewCount() != null ? a.getViewCount() : 0L);
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
     * 活动详情页（公开）- 根据 slug 路由到独立的 HTML 模板
     * 模板路径：templates/activities/{slug}.html
     */
    @GetMapping("/activities/{slug}")
    public String detail(@PathVariable String slug, Model model) {
        Activity activity = activityService.getActivityBySlug(slug);
        // 增加浏览量
        activityService.incrementViewCount(activity.getId());
        model.addAttribute("activity", activity);
        model.addAttribute("status", activityService.getStatus(activity));

        // 读取活动配置（通用）
        configRepository.findByActivityId(activity.getId()).ifPresent(cfg -> {
            model.addAttribute("configJson", cfg.getConfigJson());
        });

        // 裁判组数据（建筑大赛专用，始终显示3个位置）
        if ("building-master-1".equals(slug)) {
            var realJudges = judgeRepository.findByActivityId(activity.getId());
            List<Map<String, Object>> judgeList = new ArrayList<>();

            // 真实裁判数据
            for (var judge : realJudges) {
                Map<String, Object> m = new HashMap<>();
                m.put("name", judge.getUser().getNickname());
                m.put("title", judge.getUser().getCampName() != null ? judge.getUser().getCampName() : "");
                m.put("avatar", judge.getUser().getAvatar());
                m.put("liveRoomUrl", judge.getLiveRoomUrl());
                judgeList.add(m);
            }

            // 不足 3 个的位置用占位填充
            for (int i = judgeList.size() + 1; i <= 3; i++) {
                Map<String, Object> m = new HashMap<>();
                m.put("name", "裁判 " + i);
                m.put("title", "待公布");
                m.put("avatar", null);
                m.put("liveRoomUrl", null);
                judgeList.add(m);
            }

            model.addAttribute("judges", judgeList);
        }

        return "activities/" + slug;
    }
}
