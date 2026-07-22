package com.potato.peacehaven.controller;

import com.potato.peacehaven.entity.SiteStats;
import com.potato.peacehaven.entity.TeamMember;
import com.potato.peacehaven.entity.WelfareRecord;
import com.potato.peacehaven.repository.SiteStatsRepository;
import com.potato.peacehaven.repository.TeamMemberRepository;
import com.potato.peacehaven.repository.UserRepository;
import com.potato.peacehaven.repository.WelfareRecordRepository;
import com.potato.peacehaven.service.ActivityService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Controller
@RequiredArgsConstructor
public class HomeController {

    private final ActivityService activityService;
    private final SiteStatsRepository siteStatsRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final UserRepository userRepository;
    private final WelfareRecordRepository welfareRecordRepository;

    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute("recentActivities", activityService.getRecentActivities());

        // 营地简介统计数据
        SiteStats stats = siteStatsRepository.findSiteStats().orElse(
                SiteStats.builder().memberCount(0).eventCount(0).battleCount(0).build()
        );
        model.addAttribute("siteStats", stats);

        // 管理组成员 + 从 User 表解析昵称
        List<TeamMember> members = teamMemberRepository.findAllByOrderBySortOrderAsc();
        Map<Long, String> nicknameMap = new HashMap<>();
        for (TeamMember m : members) {
            if (m.getUserId() != null && m.getUserId() > 0) {
                userRepository.findById(m.getUserId())
                        .ifPresent(u -> nicknameMap.put(m.getId(), u.getNickname()));
            }
        }
        model.addAttribute("teamMembers", members);
        model.addAttribute("nicknameMap", nicknameMap);

        // 最近一次福利记录
        java.time.LocalDate luckyDate = welfareRecordRepository.findLatestDateByType("月度幸运儿");
        if (luckyDate != null) {
            model.addAttribute("latestLuckyDate", luckyDate);
            model.addAttribute("latestLuckyWinners", welfareRecordRepository.findByWelfareTypeAndWelfareDate("月度幸运儿", luckyDate));
        }
        java.time.LocalDate contribDate = welfareRecordRepository.findLatestDateByType("最佳贡献");
        if (contribDate != null) {
            List<WelfareRecord> contribs = welfareRecordRepository.findByWelfareTypeAndWelfareDate("最佳贡献", contribDate);
            contribs.sort(Comparator.comparing(WelfareRecord::getContribution, Comparator.nullsLast(Comparator.reverseOrder())));
            model.addAttribute("latestContribDate", contribDate);
            model.addAttribute("latestContribWinners", contribs);
        }

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

    @GetMapping("/judge/building-master-2")
    public String judgePanel2() {
        return "activities/judge-panel";
    }
}
