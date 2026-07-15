package com.potato.peacehaven.repository;

import com.potato.peacehaven.entity.BotMessageTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BotMessageTemplateRepository extends JpaRepository<BotMessageTemplate, Long> {

    List<BotMessageTemplate> findByEventType(String eventType);

    Optional<BotMessageTemplate> findByEventTypeAndTimedMessageIdIsNull(String eventType);
}
