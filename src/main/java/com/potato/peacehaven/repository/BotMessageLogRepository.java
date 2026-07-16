package com.potato.peacehaven.repository;

import com.potato.peacehaven.entity.BotMessageLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;

public interface BotMessageLogRepository extends JpaRepository<BotMessageLog, Long> {

    /**
     * 去重判断：检查是否已处理过该消息
     */
    boolean existsByNewMsgIdAndAppId(Long newMsgId, String appId);

    /**
     * 按时间范围清理旧日志（防止无限膨胀）
     */
    void deleteByReceivedAtBefore(LocalDateTime before);

    /**
     * 统计某时间段内的消息数量
     */
    long countByReceivedAtAfter(LocalDateTime after);
}
