package com.potato.peacehaven.controller;

import com.potato.peacehaven.entity.CampAffair;
import com.potato.peacehaven.entity.CampMember;
import com.potato.peacehaven.repository.CampAffairRepository;
import com.potato.peacehaven.repository.CampMemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 营地事务管理（后台）
 */
@Controller
@RequestMapping("/admin/camp-affairs")
@RequiredArgsConstructor
public class AdminCampAffairController {

    private final CampAffairRepository campAffairRepository;
    private final CampMemberRepository campMemberRepository;

    private static final List<String> AFFAIR_TYPES = List.of("资源战", "尸潮", "铁手", "巡逻");

    /**
     * 事务记录管理页
     * 默认展示近一个月数据，支持日期范围筛选
     */
    @GetMapping
    public String list(@RequestParam(required = false) String from,
                       @RequestParam(required = false) String to,
                       Model model) {
        LocalDate dateFrom = from != null ? LocalDate.parse(from) : LocalDate.now().minusMonths(1);
        LocalDate dateTo = to != null ? LocalDate.parse(to) : LocalDate.now();

        List<CampAffair> records = campAffairRepository.findByAffairDateBetweenOrderByAffairDateDescIdDesc(dateFrom, dateTo);
        List<CampMember> members = campMemberRepository.findAllByOrderBySortOrderAsc();

        // 按日期分组记录
        Map<String, List<CampAffair>> grouped = new LinkedHashMap<>();
        for (CampAffair r : records) {
            String dateKey = r.getAffairDate().toString();
            grouped.computeIfAbsent(dateKey, k -> new ArrayList<>()).add(r);
        }

        model.addAttribute("grouped", grouped);
        model.addAttribute("members", members);
        model.addAttribute("affairTypes", AFFAIR_TYPES);
        model.addAttribute("dateFrom", dateFrom.toString());
        model.addAttribute("dateTo", dateTo.toString());
        return "admin/camp-affairs";
    }

    /**
     * 批量添加事务记录：选择类型+日期+勾选成员，每个勾选成员生成一条记录
     * 支持营地排名（同一批次共用）和成员个人排名（每人独立）
     */
    @PostMapping
    @ResponseBody
    @Transactional
    public ResponseEntity<Map<String, Object>> add(@RequestBody Map<String, Object> body,
                                                    RedirectAttributes redirectAttributes) {
        String affairType = (String) body.get("affairType");
        String affairDate = (String) body.get("affairDate");
        Integer campRanking = body.get("campRanking") != null ? Integer.valueOf(body.get("campRanking").toString()) : null;

        if (!AFFAIR_TYPES.contains(affairType)) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "无效的事务类型"));
        }

        Object membersObj = body.get("members");
        if (!(membersObj instanceof List) || ((List<?>) membersObj).isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "请至少选择一名成员"));
        }

        LocalDate date = LocalDate.parse(affairDate);
        int count = 0;

        for (Object item : (List<?>) membersObj) {
            Map<String, Object> m = (Map<String, Object>) item;
            String nickname = (String) m.get("nickname");
            Integer memberRanking = m.get("ranking") != null ? Integer.valueOf(m.get("ranking").toString()) : null;

            if (nickname == null || nickname.trim().isEmpty()) continue;

            CampAffair record = CampAffair.builder()
                    .nickname(nickname.trim())
                    .affairType(affairType)
                    .affairDate(date)
                    .campRanking(campRanking)
                    .memberRanking(memberRanking)
                    .build();
            campAffairRepository.save(record);
            count++;
        }

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "已添加 " + affairType + "（" + affairDate + "）记录，共 " + count + " 人");
        return ResponseEntity.ok(result);
    }

    /**
     * 删除指定日期的所有事务记录
     */
    @PostMapping("/{date}/delete")
    @Transactional
    public String deleteByDate(@PathVariable String date, RedirectAttributes redirectAttributes) {
        LocalDate localDate = LocalDate.parse(date);
        campAffairRepository.deleteByAffairDate(localDate);
        redirectAttributes.addFlashAttribute("message", "已删除 " + date + " 的所有事务记录");
        return "redirect:/admin/camp-affairs";
    }

    /**
     * 图表数据 API：返回每个事务类型按日期的参与人数，支持日期范围筛选
     */
    @GetMapping("/api/chart")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> chartData(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        LocalDate dateFrom = from != null ? LocalDate.parse(from) : LocalDate.now().minusMonths(1);
        LocalDate dateTo = to != null ? LocalDate.parse(to) : LocalDate.now();

        List<Object[]> raw = campAffairRepository.countGroupedByDateAndTypeBetween(dateFrom, dateTo);

        // 构建 { type: [ {date, count}, ... ] } 结构
        Map<String, List<Map<String, Object>>> series = new LinkedHashMap<>();
        for (String type : AFFAIR_TYPES) {
            series.put(type, new ArrayList<>());
        }

        for (Object[] row : raw) {
            LocalDate d = (LocalDate) row[0];
            String type = (String) row[1];
            Long count = (Long) row[2];
            Map<String, Object> point = new HashMap<>();
            point.put("date", d.toString());
            point.put("count", count);
            series.computeIfAbsent(type, k -> new ArrayList<>()).add(point);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("series", series);
        return ResponseEntity.ok(result);
    }

    /**
     * 查询某次事务的参与成员列表
     */
    @GetMapping("/api/members")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> members(
            @RequestParam String date,
            @RequestParam String type) {
        LocalDate localDate = LocalDate.parse(date);
        List<CampAffair> records = campAffairRepository.findByAffairDateAndAffairType(localDate, type);

        List<Map<String, Object>> list = records.stream().map(r -> {
            Map<String, Object> m = new HashMap<>();
            m.put("nickname", r.getNickname());
            m.put("campRanking", r.getCampRanking());
            m.put("memberRanking", r.getMemberRanking());
            return m;
        }).collect(Collectors.toList());

        Map<String, Object> result = new HashMap<>();
        result.put("date", date);
        result.put("type", type);
        result.put("members", list);
        return ResponseEntity.ok(result);
    }
}
