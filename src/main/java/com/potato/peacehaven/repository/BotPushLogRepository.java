package com.potato.peacehaven.repository;

import com.potato.peacehaven.entity.BotPushLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;

public interface BotPushLogRepository extends JpaRepository<BotPushLog, Long> {

    boolean existsByPushDateAndTimedMessageIdAndScheduleConfigIdAndEventTime(
            LocalDate pushDate, Long timedMessageId, Long scheduleConfigId, LocalTime eventTime);

    @Transactional
    void deleteByPushDateBefore(LocalDate date);
}
