package com.potato.peacehaven.ai.review;

/**
 * 回复审核服务接口
 * <p>
 * 预留审核层，后续可替换为：敏感词过滤、LLM 二次审核、人工审核队列等。
 * </p>
 */
public interface ReplyReviewService {

    /**
     * 审核 AI 生成的回复
     *
     * @param originalMessage 原始消息（用户发的）
     * @param aiReply         AI 生成的回复
     * @return 审核结果
     */
    ReviewResult review(String originalMessage, String aiReply);
}
