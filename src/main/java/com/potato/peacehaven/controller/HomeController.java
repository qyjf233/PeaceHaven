package com.potato.peacehaven.controller;

import com.potato.peacehaven.service.ActivityService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
@RequiredArgsConstructor
public class HomeController {

    private final ActivityService activityService;

    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute("recentActivities", activityService.getRecentActivities());
        return "index";
    }

    @GetMapping("/agreement")
    public String agreement() {
        return "agreement";
    }
}
