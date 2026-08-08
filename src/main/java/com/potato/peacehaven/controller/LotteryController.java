package com.potato.peacehaven.controller;

import com.potato.peacehaven.entity.Lottery;
import com.potato.peacehaven.entity.LotteryWinner;
import com.potato.peacehaven.entity.User;
import com.potato.peacehaven.service.LotteryService;
import com.potato.peacehaven.service.UserService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 抽奖公开 API
 */
@Slf4j
@RestController
@RequestMapping("/api/lotteries")
@RequiredArgsConstructor
public class LotteryController {

    private final LotteryService lotteryService;
    private final UserService userService;

    /**
     * 获取当前可参与的抽奖列表
     */
    @GetMapping("/active")
    public ResponseEntity<List<Map<String, Object>>> getActive() {
        List<Lottery> lotteries = lotteryService.getActiveLotteries();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Lottery l : lotteries) {
            result.add(toLotteryMap(l));
        }
        return ResponseEntity.ok(result);
    }

    /**
     * 获取所有抽奖（含已开奖，用于展示）
     */
    @GetMapping("/all")
    public ResponseEntity<List<Map<String, Object>>> getAll() {
        List<Lottery> lotteries = lotteryService.getAllLotteries();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Lottery l : lotteries) {
            Map<String, Object> map = toLotteryMap(l);
            map.put("participantCount", lotteryService.getParticipants(l.getId()).size());
            map.put("winnerCount", lotteryService.getWinners(l.getId()).size());
            result.add(map);
        }
        return ResponseEntity.ok(result);
    }

    /**
     * 参与抽奖（需登录）
     */
    @PostMapping("/{id}/join")
    public ResponseEntity<Map<String, Object>> join(@PathVariable Long id, HttpSession session) {
        User user = userService.getCurrentUser(session);
        if (user == null) return ResponseEntity.status(401).body(Map.of("error", "请先登录"));

        try {
            lotteryService.participate(id, user.getId(), user.getNickname());
            return ResponseEntity.ok(Map.of("success", true, "message", "参与成功"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 查询我的中奖状态
     */
    @GetMapping("/{id}/my-result")
    public ResponseEntity<Map<String, Object>> myResult(@PathVariable Long id, HttpSession session) {
        User user = userService.getCurrentUser(session);
        if (user == null) return ResponseEntity.status(401).body(Map.of("error", "请先登录"));

        Optional<LotteryWinner> winner = lotteryService.getMyResult(id, user.getId());
        if (winner.isEmpty()) {
            boolean joined = lotteryService.getParticipants(id).stream()
                    .anyMatch(p -> p.getUserId().equals(user.getId()));
            return ResponseEntity.ok(Map.of("won", false, "joined", joined));
        }

        LotteryWinner w = winner.get();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("won", true);
        result.put("joined", true);
        result.put("shippingFilled", w.getShippingFilled());
        result.put("address", w.getAddress());
        result.put("phone", w.getPhone());
        return ResponseEntity.ok(result);
    }

    /**
     * 填写收货地址
     */
    @PostMapping("/{id}/fill-shipping")
    public ResponseEntity<Map<String, Object>> fillShipping(@PathVariable Long id,
                                                             @RequestBody Map<String, String> body,
                                                             HttpSession session) {
        User user = userService.getCurrentUser(session);
        if (user == null) return ResponseEntity.status(401).body(Map.of("error", "请先登录"));

        try {
            lotteryService.fillShipping(id, user.getId(), body.get("address"), body.get("phone"));
            return ResponseEntity.ok(Map.of("success", true, "message", "收货信息已提交"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 获取某抽奖的中奖名单（公开）
     */
    @GetMapping("/{id}/winners")
    public ResponseEntity<List<Map<String, Object>>> getWinners(@PathVariable Long id) {
        var winners = lotteryService.getWinners(id);
        List<Map<String, Object>> result = new ArrayList<>();
        for (LotteryWinner w : winners) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("userName", w.getUserName());
            map.put("shippingFilled", w.getShippingFilled());
            result.add(map);
        }
        return ResponseEntity.ok(result);
    }

    private Map<String, Object> toLotteryMap(Lottery l) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", l.getId());
        map.put("title", l.getTitle());
        map.put("description", l.getDescription());
        map.put("imageUrl", l.getImageUrl());
        map.put("totalPrizes", l.getTotalPrizes());
        map.put("startDate", l.getStartDate().toString());
        map.put("endDate", l.getEndDate().toString());
        map.put("status", l.getStatus());
        return map;
    }
}
