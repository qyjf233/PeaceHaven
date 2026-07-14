package com.potato.peacehaven.controller;

import com.potato.peacehaven.entity.CampMember;
import com.potato.peacehaven.entity.WelfareRecord;
import com.potato.peacehaven.repository.CampMemberRepository;
import com.potato.peacehaven.repository.WelfareRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 福利系统管理（后台）
 */
@Controller
@RequestMapping("/admin/welfare")
@RequiredArgsConstructor
public class AdminWelfareController {

    private final WelfareRecordRepository welfareRecordRepository;
    private final CampMemberRepository campMemberRepository;

    private static final List<String> WELFARE_TYPES = List.of("月度幸运儿", "最佳贡献");

    /**
     * 福利记录管理页
     */
    @GetMapping
    public String list(@RequestParam(required = false) String from,
                       @RequestParam(required = false) String to,
                       Model model) {
        LocalDate dateFrom = from != null ? LocalDate.parse(from) : LocalDate.now().minusMonths(1);
        LocalDate dateTo = to != null ? LocalDate.parse(to) : LocalDate.now();

        List<WelfareRecord> records = welfareRecordRepository.findByWelfareDateBetweenOrderByWelfareDateDescIdDesc(dateFrom, dateTo);
        List<CampMember> members = campMemberRepository.findAllByOrderBySortOrderAsc();

        // 按日期分组
        Map<String, List<WelfareRecord>> grouped = new LinkedHashMap<>();
        for (WelfareRecord r : records) {
            String dateKey = r.getWelfareDate().toString();
            grouped.computeIfAbsent(dateKey, k -> new ArrayList<>()).add(r);
        }

        model.addAttribute("grouped", grouped);
        model.addAttribute("members", members);
        model.addAttribute("welfareTypes", WELFARE_TYPES);
        model.addAttribute("dateFrom", dateFrom.toString());
        model.addAttribute("dateTo", dateTo.toString());
        return "admin/welfare";
    }

    /**
     * 月度幸运儿抽奖：从营地成员中随机抽取2人
     */
    @PostMapping("/api/lottery")
    @ResponseBody
    @Transactional
    public ResponseEntity<Map<String, Object>> lottery(@RequestBody Map<String, Object> body) {
        String welfareDate = (String) body.get("welfareDate");
        if (welfareDate == null) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "请选择日期"));
        }

        List<CampMember> allMembers = campMemberRepository.findAllByOrderBySortOrderAsc();
        if (allMembers.size() < 2) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "营地成员不足2人，无法抽奖"));
        }

        // 随机打乱后取前2个
        List<CampMember> shuffled = new ArrayList<>(allMembers);
        Collections.shuffle(shuffled);
        List<CampMember> winners = shuffled.subList(0, 2);

        LocalDate date = LocalDate.parse(welfareDate);
        List<String> winnerNames = new ArrayList<>();

        for (CampMember w : winners) {
            WelfareRecord record = WelfareRecord.builder()
                    .welfareDate(date)
                    .welfareType("月度幸运儿")
                    .nickname(w.getNickname())
                    .build();
            welfareRecordRepository.save(record);
            winnerNames.add(w.getNickname());
        }

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "🎉 恭喜 " + String.join("、", winnerNames) + " 成为本月幸运儿！");
        result.put("winners", winnerNames);
        return ResponseEntity.ok(result);
    }

    /**
     * 最佳贡献批量添加：选3人，每人填贡献值
     */
    @PostMapping
    @ResponseBody
    @Transactional
    public ResponseEntity<Map<String, Object>> addContribution(@RequestBody Map<String, Object> body) {
        String welfareDate = (String) body.get("welfareDate");
        Object membersObj = body.get("members");

        if (!(membersObj instanceof List) || ((List<?>) membersObj).size() != 3) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "请选择恰好3名成员"));
        }

        LocalDate date = LocalDate.parse(welfareDate);
        int count = 0;

        for (Object item : (List<?>) membersObj) {
            Map<String, Object> m = (Map<String, Object>) item;
            String nickname = (String) m.get("nickname");
            BigDecimal contribution = m.get("contribution") != null
                    ? new BigDecimal(m.get("contribution").toString()) : null;

            if (nickname == null || nickname.trim().isEmpty()) continue;
            if (contribution == null || contribution.compareTo(BigDecimal.ZERO) <= 0) {
                return ResponseEntity.badRequest().body(Map.of("success", false, "message", nickname + " 的贡献值无效"));
            }
            if (contribution.compareTo(new BigDecimal("100000000")) >= 0) {
                return ResponseEntity.badRequest().body(Map.of("success", false, "message", nickname + " 的贡献值不能超过一亿"));
            }

            WelfareRecord record = WelfareRecord.builder()
                    .welfareDate(date)
                    .welfareType("最佳贡献")
                    .nickname(nickname.trim())
                    .contribution(contribution)
                    .build();
            welfareRecordRepository.save(record);
            count++;
        }

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "已添加最佳贡献记录，共 " + count + " 人");
        return ResponseEntity.ok(result);
    }

    /**
     * 删除指定日期的所有福利记录
     */
    @PostMapping("/{date}/delete")
    @Transactional
    public String deleteByDate(@PathVariable String date, RedirectAttributes redirectAttributes) {
        LocalDate localDate = LocalDate.parse(date);
        welfareRecordRepository.deleteByWelfareDate(localDate);
        redirectAttributes.addFlashAttribute("message", "已删除 " + date + " 的福利记录");
        return "redirect:/admin/welfare";
    }
}
