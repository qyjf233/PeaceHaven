package com.potato.peacehaven.ai.topic;

import lombok.Data;

import java.time.Instant;

/**
 * 单个群聊的对话状态 DTO
 * <p>
 * 实时追踪当前话题、持续次数、持续时间等指标，
 * 用于判断话题是否"过热"（锚定检测）。
 * </p>
 */
@Data
public class ConversationState {

    /** 当前话题关键词 */
    private String currentTopic;

    /** 当前话题被连续提及次数 */
    private int topicMentionCount;

    /** 话题开始时间 */
    private Instant topicStartTime;

    /** 上次话题切换时间 */
    private Instant lastTopicChangeTime;

    /** 上一个话题（用于对比是否切换） */
    private String previousTopic;

    /**
     * 判断当前话题是否"过热"（持续次数超过阈值）
     *
     * @param threshold 阈值
     * @return true=话题过热
     */
    public boolean isTopicStale(int threshold) {
        return currentTopic != null && topicMentionCount >= threshold;
    }

    /**
     * 当前话题持续时间（秒）
     */
    public long topicDurationSeconds() {
        if (topicStartTime == null) return 0;
        return Instant.now().getEpochSecond() - topicStartTime.getEpochSecond();
    }
}
