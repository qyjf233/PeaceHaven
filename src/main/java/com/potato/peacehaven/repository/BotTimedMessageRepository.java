package com.potato.peacehaven.repository;

import com.potato.peacehaven.entity.BotTimedMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BotTimedMessageRepository extends JpaRepository<BotTimedMessage, Long> {

    List<BotTimedMessage> findByEventTypeOrderByAdvanceMinutesDesc(String eventType);
}
