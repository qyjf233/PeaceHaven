package com.potato.peacehaven.controller;

import com.potato.peacehaven.repository.PageVisitRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 流量监控管理页
 */
@Controller
@RequestMapping("/admin/traffic")
@RequiredArgsConstructor
public class AdminTrafficController {

    private final PageVisitRepository pageVisitRepository;

    @GetMapping
    public String page(@RequestParam(required = false) String from,
                       @RequestParam(required = false) String to,
                       Model model) {
        LocalDate dateFrom = from != null ? LocalDate.parse(from) : LocalDate.now().minusDays(30);
        LocalDate dateTo = to != null ? LocalDate.parse(to) : LocalDate.now();
        model.addAttribute("dateFrom", dateFrom.toString());
        model.addAttribute("dateTo", dateTo.toString());
        return "admin/traffic";
    }

    /**
     * 流量概览统计 + 所有图表数据（一次请求返回全部）
     */
    @GetMapping("/api/stats")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> stats(
            @RequestParam String from,
            @RequestParam String to) {

        LocalDate dateFrom = LocalDate.parse(from);
        LocalDate dateTo = LocalDate.parse(to);
        LocalDateTime fromDt = dateFrom.atStartOfDay();
        LocalDateTime toDt = dateTo.atTime(LocalTime.MAX);

        Map<String, Object> result = new LinkedHashMap<>();

        // 1. 概览数据
        long totalPv = pageVisitRepository.countByCreatedAtBetween(fromDt, toDt);
        long totalUv = pageVisitRepository.countDistinctIpBetween(fromDt, toDt);
        result.put("totalPv", totalPv);
        result.put("totalUv", totalUv);

        // 2. 每日PV趋势
        List<Object[]> pvRaw = pageVisitRepository.dailyPv(fromDt, toDt);
        List<Map<String, Object>> pvTrend = new ArrayList<>();
        for (Object[] row : pvRaw) {
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("date", row[0].toString());
            point.put("pv", ((Number) row[1]).longValue());
            pvTrend.add(point);
        }
        result.put("pvTrend", pvTrend);

        // 3. 每日UV趋势
        List<Object[]> uvRaw = pageVisitRepository.dailyUv(fromDt, toDt);
        List<Map<String, Object>> uvTrend = new ArrayList<>();
        for (Object[] row : uvRaw) {
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("date", row[0].toString());
            point.put("uv", ((Number) row[1]).longValue());
            uvTrend.add(point);
        }
        result.put("uvTrend", uvTrend);

        // 4. 页面热度排行（取Top 10）
        List<Object[]> pageRaw = pageVisitRepository.pageRanking(fromDt, toDt);
        List<Map<String, Object>> pageRank = pageRaw.stream().limit(10).map(row -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("page", row[0]);
            item.put("count", ((Number) row[1]).longValue());
            return item;
        }).collect(Collectors.toList());
        result.put("pageRank", pageRank);

        // 5. Top IP 排行（取Top 10）
        List<Object[]> ipRaw = pageVisitRepository.topIps(fromDt, toDt);
        List<Map<String, Object>> topIps = ipRaw.stream().limit(10).map(row -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("ip", row[0]);
            item.put("count", ((Number) row[1]).longValue());
            return item;
        }).collect(Collectors.toList());
        result.put("topIps", topIps);

        // 6. 来源排行（取Top 10）
        List<Object[]> refRaw = pageVisitRepository.topReferers(fromDt, toDt);
        List<Map<String, Object>> topRefs = refRaw.stream().limit(10).map(row -> {
            Map<String, Object> item = new LinkedHashMap<>();
            String ref = (String) row[0];
            // 截取域名部分
            item.put("referer", simplifyReferer(ref));
            item.put("count", ((Number) row[1]).longValue());
            return item;
        }).collect(Collectors.toList());
        result.put("topReferers", topRefs);

        return ResponseEntity.ok(result);
    }

    /**
     * 简化 Referer URL，提取域名
     */
    private String simplifyReferer(String url) {
        if (url == null || url.isEmpty()) return "直接访问";
        try {
            String host = url.replaceFirst("^https?://", "");
            int slashIdx = host.indexOf('/');
            if (slashIdx > 0) host = host.substring(0, slashIdx);
            return host;
        } catch (Exception e) {
            return url;
        }
    }
}
