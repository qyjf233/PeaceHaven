package com.potato.peacehaven.ai.retrieval;

import com.potato.peacehaven.entity.BotChatRecord;
import com.potato.peacehaven.repository.BotChatRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 最近上下文拉取服务
 * <p>
 * 从 bot_chat_record 查询指定群聊的最近 N 条消息，
 * 格式化为 "发送者: 内容" 的形式，作为 prompt 的上下文窗口。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContextRetrievalService {

    private final BotChatRecordRepository chatRecordRepo;

    /**
     * 获取指定群聊的最近消息上下文
     *
     * @param chatroomId 群聊 ID
     * @param limit      拉取条数
     * @return 格式化的上下文列表（按时间正序，旧的在前）
     */
    public List<ContextMessage> getRecentContext(String chatroomId, int limit) {
        if (chatroomId == null || chatroomId.isBlank() || limit <= 0) {
            return Collections.emptyList();
        }

        List<BotChatRecord> records = chatRecordRepo.findByRoomIdOrderByCreateTimeDesc(
                chatroomId, PageRequest.of(0, limit));

        if (records.isEmpty()) {
            return Collections.emptyList();
        }

        // 反转为时间正序（DB 查出来是倒序）
        Collections.reverse(records);

        return records.stream()
                .filter(r -> r.getContent() != null && !r.getContent().isBlank())
                .map(r -> ContextMessage.builder()
                        .senderNick(r.getSenderNick() != null ? r.getSenderNick() : r.getSenderWxid())
                        .senderWxid(r.getSenderWxid())
                        .content(r.getContent())
                        .isSelf(r.getIsSelf() != null && r.getIsSelf())
                        .build())
                .collect(Collectors.toList());
    }

    /**
     * 将上下文列表格式化为 prompt 文本片段
     * <p>
     * 输出示例：
     * <pre>
     * 小明: 今天天气不错啊
     * 小红: 是啊适合出去走走
     * 我: 确实
     * </pre>
     * </p>
     */
    public String formatContextForPrompt(List<ContextMessage> messages) {
        if (messages == null || messages.isEmpty()) return "";

        return messages.stream()
                .map(m -> {
                    String prefix = m.isSelf() ? "我" : m.getSenderNick();
                    return prefix + ": " + m.getContent();
                })
                .collect(Collectors.joining("\n"));
    }

    /**
     * 持久化 bot 的 AI 回复到聊天记录表（用于下次上下文拉取，保持话题延续性）
     * <p>
     * 标记 isSelf=true + isBotReply=true，这样：
     * - 上下文拉取时可以看到 bot 刚才说了什么（话题延续）
     * - Memory RAG / Style RAG 不会把 AI 回复当成本人真实发言
     * </p>
     */
    public void saveBotReply(String roomId, String roomName, String content) {
        try {
            BotChatRecord record = BotChatRecord.builder()
                    .msgId(System.currentTimeMillis()) // bot 回复无真实 msgId，用时间戳
                    .appId("bot-reply")
                    .roomId(roomId)
                    .roomName(roomName)
                    .senderWxid("self")
                    .senderNick("我")
                    .isSelf(true)
                    .isBotReply(true)
                    .msgType(1)
                    .content(content)
                    .createTime(System.currentTimeMillis() / 1000)
                    .build();
            chatRecordRepo.save(record);
            log.debug("[Context] 已存储 bot 回复 roomId={}, content={}",
                    roomId, content.length() > 50 ? content.substring(0, 50) + "..." : content);
        } catch (Exception e) {
            log.warn("[Context] 存储 bot 回复失败: {}", e.getMessage());
        }
    }

    /**
     * 上下文消息 DTO
     */
    @lombok.Data
    @lombok.Builder
    public static class ContextMessage {
        private String senderNick;
        private String senderWxid;
        private String content;
        private boolean isSelf;
    }
}
