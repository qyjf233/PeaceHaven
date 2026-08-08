package com.potato.peacehaven.repository;

import com.potato.peacehaven.entity.Lottery;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface LotteryRepository extends JpaRepository<Lottery, Long> {

    List<Lottery> findByStatusOrderByStartDateDesc(String status);

    List<Lottery> findByStatusAndEndDateBeforeOrderByEndDateAsc(String status, LocalDateTime now);

    List<Lottery> findAllByOrderByCreatedAtDesc();
}
