package com.potato.peacehaven.ai.summary;

import com.potato.peacehaven.ai.retrieval.ContextRetrievalService.ContextMessage;

import java.util.List;

/**
 * 对话摘要服务接口
 * <p>
 * 将最近 N 条聊天消息压缩为简短摘要（2-3 句），
 * 替代直接将原始消息拼接到 Prompt 中，减少话题锚定风险。
 * </p>
 */
public interface ConversationSummaryService {

    /**
     * 生成对话摘要
     *
     * @param chatroomId     群聊 ID
     * @param recentMessages 最近消息列表（按时间正序）
     * @return 摘要文本，生成失败时返回 fallback 文本
     */
    String summarize(String chatroomId, List<ContextMessage> recentMessages);
}
