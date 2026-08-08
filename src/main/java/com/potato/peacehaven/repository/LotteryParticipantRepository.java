package com.potato.peacehaven.repository;

import com.potato.peacehaven.entity.LotteryParticipant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LotteryParticipantRepository extends JpaRepository<LotteryParticipant, Long> {

    List<LotteryParticipant> findByLotteryIdOrderByCreatedAtAsc(Long lotteryId);

    boolean existsByLotteryIdAndUserId(Long lotteryId, Long userId);

    long countByLotteryId(Long lotteryId);
}
