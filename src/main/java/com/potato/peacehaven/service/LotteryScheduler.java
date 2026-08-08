package com.potato.peacehaven.service;

import com.potato.peacehaven.entity.Lottery;
import com.potato.peacehaven.repository.LotteryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 抽奖自动开奖定时任务
 * <p>
 * 每分钟扫描已截止但未开奖的抽奖，自动执行随机抽取。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LotteryScheduler {

    private final LotteryRepository lotteryRepository;
    private final LotteryService lotteryService;

    @Scheduled(fixedDelay = 60_000) // 每 60 秒检查一次
    @Transactional
    public void checkAndDrawExpiredLotteries() {
        List<Lottery> expired = lotteryRepository.findByStatusAndEndDateBeforeOrderByEndDateAsc("OPEN", LocalDateTime.now());
        for (Lottery lottery : expired) {
            log.info("[抽奖定时任务] 抽奖 #{} 已截止，自动开奖", lottery.getId());
            try {
                lotteryService.drawWinners(lottery);
            } catch (Exception e) {
                log.error("[抽奖定时任务] 抽奖 #{} 开奖失败: {}", lottery.getId(), e.getMessage());
            }
        }
    }
}
