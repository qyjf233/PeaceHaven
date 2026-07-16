package com.potato.peacehaven.ai.decision;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

/**
 * 回复决策结果
 */
@Data
@Builder
@AllArgsConstructor
public class ReplyDecision {

    /** 是否应该回复 */
    private boolean shouldReply;

    /** 决策原因（用于日志） */
    private String reason;

    public static ReplyDecision reply(String reason) {
        return new ReplyDecision(true, reason);
    }

    public static ReplyDecision skip(String reason) {
        return new ReplyDecision(false, reason);
    }
}
