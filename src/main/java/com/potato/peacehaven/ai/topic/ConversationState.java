package com.potato.peacehaven.ai.topic;

import lombok.Data;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 单个群聊的对话状态 DTO
 * <p>
 * 追踪当前话题、持续次数、持续时间等指标。
 * 同时追踪 bot 回复行为（Behavior）和推进分数（ProgressionScore），
 * 用于对话推进决策。
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

    // ===== 对话推进追踪 =====

    /** 最近 bot 行为分类（保留最近 5 条，用于行为模式分析） */
    private List<BotBehavior> recentBehaviors = new ArrayList<>();

    /** 最近 bot 回复内容（保留最近 2 条，用于分类器上下文参考） */
    private List<String> recentBotReplies = new ArrayList<>();

    /** 当前话题下 bot 回复轮数 */
    private int botReplyCount = 0;

    /**
     * 推进分数（0.0 ~ 1.0+）
     * <p>
     * 连续分值，替代硬编码的三级阈值。
     * <ul>
     *   <li>0.0 ~ 0.3：正常</li>
     *   <li>0.3 ~ 0.6：轻提示（换一种表达）</li>
     *   <li>0.6 ~ 0.9：建议推进（改变态度）</li>
     *   <li>> 0.9：强制推进（必须改变）</li>
     * </ul>
     * </p>
     */
    private double progressionScore = 0.0;

    /**
     * 判断当前话题是否"过热"（持续次数超过阈值）
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

    /**
     * 记录一次 bot 回复及其行为分类
     *
     * @param reply    bot 回复内容
     * @param behavior 行为分类
     */
    public void recordBotReply(String reply, BotBehavior behavior) {
        if (behavior == null) behavior = BotBehavior.NEUTRAL;

        // 记录行为
        if (recentBehaviors == null) recentBehaviors = new ArrayList<>();
        recentBehaviors.add(behavior);
        while (recentBehaviors.size() > 5) {
            recentBehaviors.remove(0);
        }

        // 记录回复文本（用于分类器上下文）
        if (recentBotReplies == null) recentBotReplies = new ArrayList<>();
        if (reply != null && !reply.isBlank()) {
            recentBotReplies.add(reply);
            while (recentBotReplies.size() > 2) {
                recentBotReplies.remove(0);
            }
        }

        botReplyCount++;
    }

    /**
     * 话题切换时重置推进追踪
     */
    public void resetStanceTracking() {
        recentBehaviors = new ArrayList<>();
        recentBotReplies = new ArrayList<>();
        botReplyCount = 0;
        progressionScore = 0.0;
    }

    /**
     * 获取最近一条 bot 回复（用于分类器上下文）
     */
    public String getLastBotReply() {
        if (recentBotReplies == null || recentBotReplies.isEmpty()) return null;
        return recentBotReplies.get(recentBotReplies.size() - 1);
    }

    /**
     * 获取最近一条行为分类
     */
    public BotBehavior getLastBehavior() {
        if (recentBehaviors == null || recentBehaviors.isEmpty()) return null;
        return recentBehaviors.get(recentBehaviors.size() - 1);
    }

    /**
     * 统计最近 N 条行为中与指定行为相同的连续次数
     */
    public int countConsecutiveBehavior(BotBehavior behavior) {
        if (recentBehaviors == null || recentBehaviors.isEmpty()) return 0;
        int count = 0;
        for (int i = recentBehaviors.size() - 1; i >= 0; i--) {
            if (recentBehaviors.get(i) == behavior) {
                count++;
            } else {
                break;
            }
        }
        return count;
    }
}
