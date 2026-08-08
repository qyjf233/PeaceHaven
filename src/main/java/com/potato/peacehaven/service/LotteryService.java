package com.potato.peacehaven.service;

import com.potato.peacehaven.entity.Lottery;
import com.potato.peacehaven.entity.LotteryParticipant;
import com.potato.peacehaven.entity.LotteryWinner;
import com.potato.peacehaven.repository.LotteryParticipantRepository;
import com.potato.peacehaven.repository.LotteryRepository;
import com.potato.peacehaven.repository.LotteryWinnerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class LotteryService {

    private final LotteryRepository lotteryRepository;
    private final LotteryParticipantRepository participantRepository;
    private final LotteryWinnerRepository winnerRepository;

    /**
     * 参与抽奖
     */
    @Transactional
    public void participate(Long lotteryId, Long userId, String userName) {
        Lottery lottery = lotteryRepository.findById(lotteryId)
                .orElseThrow(() -> new IllegalArgumentException("抽奖不存在"));
        if (!"OPEN".equals(lottery.getStatus())) {
            throw new IllegalArgumentException("该抽奖已结束");
        }
        if (LocalDateTime.now().isBefore(lottery.getStartDate())) {
            throw new IllegalArgumentException("抽奖尚未开始");
        }
        if (LocalDateTime.now().isAfter(lottery.getEndDate())) {
            throw new IllegalArgumentException("抽奖已截止");
        }
        if (participantRepository.existsByLotteryIdAndUserId(lotteryId, userId)) {
            throw new IllegalArgumentException("你已经参与过该抽奖");
        }

        LotteryParticipant p = LotteryParticipant.builder()
                .lotteryId(lotteryId)
                .userId(userId)
                .userName(userName)
                .build();
        participantRepository.save(p);
        log.info("[抽奖] 用户 {}({}) 参与抽奖 #{}", userName, userId, lotteryId);
    }

    /**
     * 开奖：从参与者中随机抽取中奖者
     */
    @Transactional
    public List<LotteryWinner> drawWinners(Lottery lottery) {
        List<LotteryParticipant> participants = participantRepository.findByLotteryIdOrderByCreatedAtAsc(lottery.getId());
        if (participants.isEmpty()) {
            lottery.setStatus("DRAWN");
            lotteryRepository.save(lottery);
            log.info("[抽奖] #{} 无参与者，直接标记为已开奖", lottery.getId());
            return Collections.emptyList();
        }

        int prizeCount = Math.min(lottery.getTotalPrizes(), participants.size());
        List<LotteryParticipant> shuffled = new ArrayList<>(participants);
        Collections.shuffle(shuffled);
        List<LotteryParticipant> winners = shuffled.subList(0, prizeCount);

        List<LotteryWinner> winnerList = new ArrayList<>();
        for (LotteryParticipant p : winners) {
            LotteryWinner w = LotteryWinner.builder()
                    .lotteryId(lottery.getId())
                    .userId(p.getUserId())
                    .userName(p.getUserName())
                    .build();
            winnerRepository.save(w);
            winnerList.add(w);
            log.info("[抽奖] #{} 中奖: {}({})", lottery.getId(), p.getUserName(), p.getUserId());
        }

        lottery.setStatus("DRAWN");
        lotteryRepository.save(lottery);
        return winnerList;
    }

    /**
     * 填写收货信息
     */
    @Transactional
    public void fillShipping(Long lotteryId, Long userId, String address, String phone) {
        LotteryWinner winner = winnerRepository.findByLotteryIdAndUserId(lotteryId, userId)
                .orElseThrow(() -> new IllegalArgumentException("你未中奖，无法填写收货信息"));
        if (winner.getShippingFilled()) {
            throw new IllegalArgumentException("收货信息已填写");
        }
        if (address == null || address.isBlank()) throw new IllegalArgumentException("地址不能为空");
        if (phone == null || phone.isBlank()) throw new IllegalArgumentException("电话不能为空");

        winner.setAddress(address.trim());
        winner.setPhone(phone.trim());
        winner.setShippingFilled(true);
        winner.setFilledAt(LocalDateTime.now());
        winnerRepository.save(winner);
        log.info("[抽奖] 中奖者 {}({}) 填写收货信息", winner.getUserName(), userId);
    }

    /**
     * 获取当前可参与的抽奖
     */
    public List<Lottery> getActiveLotteries() {
        LocalDateTime now = LocalDateTime.now();
        return lotteryRepository.findByStatusOrderByStartDateDesc("OPEN").stream()
                .filter(l -> !now.isBefore(l.getStartDate()))
                .collect(Collectors.toList());
    }

    /**
     * 获取所有抽奖（含已开奖）
     */
    public List<Lottery> getAllLotteries() {
        return lotteryRepository.findAllByOrderByCreatedAtDesc();
    }

    /**
     * 查询用户是否中奖
     */
    public Optional<LotteryWinner> getMyResult(Long lotteryId, Long userId) {
        return winnerRepository.findByLotteryIdAndUserId(lotteryId, userId);
    }

    /**
     * 获取某抽奖的参与者列表
     */
    public List<LotteryParticipant> getParticipants(Long lotteryId) {
        return participantRepository.findByLotteryIdOrderByCreatedAtAsc(lotteryId);
    }

    /**
     * 获取某抽奖的中奖者列表
     */
    public List<LotteryWinner> getWinners(Long lotteryId) {
        return winnerRepository.findByLotteryIdOrderByCreatedAtAsc(lotteryId);
    }

    /**
     * 创建抽奖
     */
    @Transactional
    public Lottery createLottery(String title, String description, String imageUrl,
                                  int totalPrizes, LocalDateTime startDate, LocalDateTime endDate) {
        Lottery lottery = Lottery.builder()
                .title(title)
                .description(description)
                .imageUrl(imageUrl)
                .totalPrizes(totalPrizes)
                .startDate(startDate)
                .endDate(endDate)
                .build();
        lotteryRepository.save(lottery);
        log.info("[抽奖] 创建抽奖: {} (份数:{})", title, totalPrizes);
        return lottery;
    }
}
