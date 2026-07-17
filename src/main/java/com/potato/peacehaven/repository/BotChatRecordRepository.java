package com.potato.peacehaven.repository;

import com.potato.peacehaven.entity.BotChatRecord;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

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

    /** 查询指定发送者的最近消息（按时间正序，用于画像补课） */
    List<BotChatRecord> findBySenderWxidAndIsSelfFalseAndIsBotReplyFalseOrderByCreateTimeAsc(
            String senderWxid, Pageable pageable);

    /** 查询所有非本人、非AI回复的不同发送者 wxid（去重） */
    @Query("SELECT DISTINCT r.senderWxid FROM BotChatRecord r WHERE r.isSelf = false AND r.isBotReply = false")
    List<String> findDistinctNonSelfSenderWxids();

    /** 查询指定群聊在某个时间点之前的最近 N 条文本消息（用于表情包上下文采集） */
    @Query("SELECT r FROM BotChatRecord r WHERE r.roomId = :roomId AND r.msgType = 1 AND r.createTime < :before ORDER BY r.createTime DESC")
    List<BotChatRecord> findRecentTextBefore(String roomId, Long before, Pageable pageable);
}
