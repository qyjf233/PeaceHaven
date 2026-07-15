package com.potato.peacehaven.repository;

import com.potato.peacehaven.entity.BotScheduleConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BotScheduleConfigRepository extends JpaRepository<BotScheduleConfig, Long> {

    List<BotScheduleConfig> findByEventType(String eventType);

    Optional<BotScheduleConfig> findByEventTypeAndDayOfWeek(String eventType, Integer dayOfWeek);

    List<BotScheduleConfig> findByEventTypeOrderByEventDatetimeAsc(String eventType);
}
