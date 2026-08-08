package com.potato.peacehaven.controller;

import com.potato.peacehaven.entity.Lottery;
import com.potato.peacehaven.entity.LotteryParticipant;
import com.potato.peacehaven.entity.LotteryWinner;
import com.potato.peacehaven.service.LotteryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 抽奖管理端 API
 */
@Slf4j
@RestController
@RequestMapping("/admin/api/lotteries")
@RequiredArgsConstructor
public class AdminLotteryController {

    private final LotteryService lotteryService;

    /**
     * 所有抽奖列表
     */
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list() {
        List<Lottery> lotteries = lotteryService.getAllLotteries();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Lottery l : lotteries) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", l.getId());
            map.put("title", l.getTitle());
            map.put("description", l.getDescription());
            map.put("imageUrl", l.getImageUrl());
            map.put("totalPrizes", l.getTotalPrizes());
            map.put("startDate", l.getStartDate().toString());
            map.put("endDate", l.getEndDate().toString());
            map.put("status", l.getStatus());
            map.put("participantCount", lotteryService.getParticipants(l.getId()).size());
            map.put("winnerCount", lotteryService.getWinners(l.getId()).size());
            result.add(map);
        }
        return ResponseEntity.ok(result);
    }

    /**
     * 创建抽奖
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        try {
            String title = (String) body.get("title");
            String description = (String) body.getOrDefault("description", "");
            String imageUrl = (String) body.getOrDefault("imageUrl", "");
            int totalPrizes = body.get("totalPrizes") instanceof Number
                    ? ((Number) body.get("totalPrizes")).intValue() : 1;
            LocalDateTime startDate = LocalDateTime.parse((String) body.get("startDate"));
            LocalDateTime endDate = LocalDateTime.parse((String) body.get("endDate"));

            Lottery lottery = lotteryService.createLottery(title, description, imageUrl, totalPrizes, startDate, endDate);
            return ResponseEntity.ok(Map.of("success", true, "id", lottery.getId()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 查看参与者
     */
    @GetMapping("/{id}/participants")
    public ResponseEntity<List<Map<String, Object>>> getParticipants(@PathVariable Long id) {
        List<LotteryParticipant> participants = lotteryService.getParticipants(id);
        List<Map<String, Object>> result = new ArrayList<>();
        for (LotteryParticipant p : participants) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("userId", p.getUserId());
            map.put("userName", p.getUserName());
            map.put("createdAt", p.getCreatedAt().toString());
            result.add(map);
        }
        return ResponseEntity.ok(result);
    }

    /**
     * 查看中奖者及收货信息
     */
    @GetMapping("/{id}/winners")
    public ResponseEntity<List<Map<String, Object>>> getWinners(@PathVariable Long id) {
        List<LotteryWinner> winners = lotteryService.getWinners(id);
        List<Map<String, Object>> result = new ArrayList<>();
        for (LotteryWinner w : winners) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("userId", w.getUserId());
            map.put("userName", w.getUserName());
            map.put("address", w.getAddress());
            map.put("phone", w.getPhone());
            map.put("shippingFilled", w.getShippingFilled());
            map.put("filledAt", w.getFilledAt() != null ? w.getFilledAt().toString() : null);
            map.put("wonAt", w.getCreatedAt().toString());
            result.add(map);
        }
        return ResponseEntity.ok(result);
    }
}
