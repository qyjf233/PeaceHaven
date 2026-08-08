package com.potato.peacehaven.repository;

import com.potato.peacehaven.entity.LotteryWinner;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LotteryWinnerRepository extends JpaRepository<LotteryWinner, Long> {

    List<LotteryWinner> findByLotteryIdOrderByCreatedAtAsc(Long lotteryId);

    Optional<LotteryWinner> findByLotteryIdAndUserId(Long lotteryId, Long userId);

    long countByLotteryId(Long lotteryId);
}
