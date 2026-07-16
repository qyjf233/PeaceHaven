package com.potato.peacehaven.ai.review;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

/**
 * 回复审核结果
 */
@Data
@Builder
@AllArgsConstructor
public class ReviewResult {

    /** 是否通过审核 */
    private boolean approved;

    /** 审核原因（用于日志） */
    private String reason;

    /** 审核后的回复内容（可能被修改/过滤） */
    private String reply;

    public static ReviewResult pass(String reply) {
        return new ReviewResult(true, "通过", reply);
    }

    public static ReviewResult reject(String reason) {
        return new ReviewResult(false, reason, null);
    }
}
