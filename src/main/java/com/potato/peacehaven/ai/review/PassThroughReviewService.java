package com.potato.peacehaven.ai.review;

import org.springframework.stereotype.Service;

/**
 * 默认审核实现：直接放行
 * <p>
 * 后续可替换为更严格的审核策略（敏感词过滤、LLM 二次审核等）。
 * </p>
 */
@Service
public class PassThroughReviewService implements ReplyReviewService {

    @Override
    public ReviewResult review(String originalMessage, String aiReply) {
        if (aiReply == null || aiReply.isBlank()) {
            return ReviewResult.reject("AI 回复为空");
        }
        return ReviewResult.pass(aiReply);
    }
}
