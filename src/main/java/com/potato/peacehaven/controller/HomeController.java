package com.potato.peacehaven.controller;

import com.potato.peacehaven.entity.SiteStats;
import com.potato.peacehaven.repository.SiteStatsRepository;
import com.potato.peacehaven.service.ActivityService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
@RequiredArgsConstructor
public class HomeController {

    private final ActivityService activityService;
    private final SiteStatsRepository siteStatsRepository;

    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute("recentActivities", activityService.getRecentActivities());

        // 营地简介统计数据
        SiteStats stats = siteStatsRepository.findSiteStats().orElse(
                SiteStats.builder().memberCount(0).eventCount(0).battleCount(0).build()
        );
        model.addAttribute("siteStats", stats);

        return "index";
    }

    @GetMapping("/agreement")
    public String agreement() {
        return "agreement";
    }

    @GetMapping("/judge/building-master-1")
    public String judgePanel() {
        return "activities/judge-panel";
    }
}
