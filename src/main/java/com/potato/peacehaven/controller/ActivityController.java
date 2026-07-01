package com.potato.peacehaven.controller;

import com.potato.peacehaven.entity.Activity;
import com.potato.peacehaven.service.ActivityService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Controller
@RequiredArgsConstructor
public class ActivityController {

    private final ActivityService activityService;

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
        model.addAttribute("activity", activity);
        model.addAttribute("status", activityService.getStatus(activity));
        return "activities/" + slug;
    }
}
