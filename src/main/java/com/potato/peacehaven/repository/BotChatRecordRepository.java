package com.potato.peacehaven.repository;

import com.potato.peacehaven.entity.BotChatRecord;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface BotChatRecordRepository extends JpaRepository<BotChatRecord, Long> {

    /** 去重：msgId + appId 联合唯一 */
    boolean existsByMsgIdAndAppId(Long msgId, String appId);

    /** 查询指定群聊的聊天记录（按时间倒序） */
    List<BotChatRecord> findByRoomIdOrderByCreateTimeDesc(String roomId, Pageable pageable);

    /** 查询未向量化的记录（用于批量处理） */
    List<BotChatRecord> findByProcessedFalseOrderByCreateTimeAsc(Pageable pageable);

    /** 查询未向量化的本人真实发言（排除 AI 回复，防止风格回流） */
    List<BotChatRecord> findByProcessedFalseAndIsSelfTrueAndIsBotReplyFalseOrderByCreateTimeAsc(Pageable pageable);

    /** 统计指定时间后的记录数 */
    long countByCreatedAtAfter(LocalDateTime after);

    /** 统计未向量化的记录数 */
    long countByProcessedFalse();

    /** 按 roomId 统计记录数 */
    long countByRoomId(String roomId);

    /** 查询最近的本人真实消息（学习用，排除 AI 回复） */
    List<BotChatRecord> findByIsSelfTrueAndIsBotReplyFalseOrderByCreatedAtDesc(Pageable pageable);

    /** 统计本人真实消息总数（Bootstrap 检查用） */
    long countByIsSelfTrueAndIsBotReplyFalse();

    /** 按 roomId 分组统计本人消息数 */
    long countByIsSelfTrueAndIsBotReplyFalseAndRoomId(String roomId);
}
