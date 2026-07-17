package com.potato.peacehaven.ai.topic;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 对话推进服务（Conversation Progression Manager）
 * <p>
 * 解决的问题：bot 在同一话题上反复给出相同行为的回复（如连续 REFUSE），
 * 缺少"推进剧情"的能力，导致对话不像真人。
 * <p>
 * 核心机制：
 * <ol>
 *   <li>行为分类：每次 bot 回复后，用 LLM 分类行为（REFUSE/ACCEPT/JOKE 等）</li>
 *   <li>推进分数：连续分值（0~1+），根据行为模式动态累加/衰减</li>
 *   <li>观察式提示：当分数超过阈值，注入观察式提示（而非指令式），引导 LLM 自然变化</li>
 * </ol>
 * <p>
 * 分数更新规则：
 * <ul>
 *   <li>行为与上次相同：+0.25</li>
 *   <li>行为与上次不同：-0.5（打断重复模式）</li>
 *   <li>bot 主动推进（ACCEPT/COUNTER/CHANGE_TOPIC/END）：-0.6</li>
 *   <li>分数下限 0.0</li>
 * </ul>
 * <p>
 * 提示阈值：
 * <ul>
 *   <li>0.0 ~ 0.3：正常，不提示</li>
 *   <li>0.3 ~ 0.6：轻提示（换一种表达）</li>
 *   <li>0.6 ~ 0.9：建议推进（改变态度/策略）</li>
 *   <li>> 0.9：强制推进（话题已僵住）</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationProgressionService {

    private final ConversationStateManager conversationStateManager;
    private final BotBehaviorClassifier behaviorClassifier;

    // ===== 分数更新参数 =====

    /** 行为与上次相同时的分数增量 */
    private static final double SAME_BEHAVIOR_INCREMENT = 0.25;

    /** 连续同行为的额外递增（每多一次连续 +0.05） */
    private static final double CONSECUTIVE_BONUS = 0.05;

    /** 行为与上次不同时的分数衰减 */
    private static final double DIFFERENT_BEHAVIOR_DECAY = -0.5;

    /** bot 主动推进时的分数衰减 */
    private static final double ACTIVE_PROGRESSION_DECAY = -0.6;

    /** 分数下限 */
    private static final double MIN_SCORE = 0.0;

    // ===== 提示阈值 =====

    /** 轻提示阈值 */
    private static final double LIGHT_THRESHOLD = 0.3;

    /** 建议推进阈值 */
    private static final double MEDIUM_THRESHOLD = 0.6;

    /** 强制推进阈值 */
    private static final double STRONG_THRESHOLD = 0.9;

    /**
     * 分析 bot 回复并更新推进状态（在 bot 发送成功后调用）
     * <p>
     * 步骤：
     * 1. 用 LLM 分类 bot 回复的行为
     * 2. 记录行为到 ConversationState
     * 3. 根据行为变化更新 progressionScore
     * </p>
     *
     * @param chatroomId 群聊 ID
     * @param botReply   本次 bot 回复内容
     * @param userMsg    触发回复的用户消息（用于分类上下文）
     */
    public void analyzeAndUpdateProgression(String chatroomId, String botReply, String userMsg) {
        if (chatroomId == null || botReply == null || botReply.isBlank()) return;

        ConversationState state = conversationStateManager.getState(chatroomId);
        if (state == null) return;

        // 1. 行为分类（轻量 LLM 调用）—— 在记录新回复前获取上一条
        String lastBotReply = state.getLastBotReply();
        BotBehavior behavior = behaviorClassifier.classify(botReply, userMsg, lastBotReply);

        // 2. 记录行为到 ConversationState
        conversationStateManager.recordBotReply(chatroomId, botReply, behavior);

        // 3. 更新 progressionScore
        double scoreDelta = computeScoreDelta(state, behavior);
        double newScore = Math.max(MIN_SCORE, state.getProgressionScore() + scoreDelta);
        state.setProgressionScore(newScore);

        int consecutiveCount = state.countConsecutiveBehavior(behavior);

        log.info("[Progression] chatroom={}, behavior={}, consecutive={}, score={} -> {}, delta={}",
                chatroomId, behavior, consecutiveCount,
                String.format("%.2f", state.getProgressionScore() - scoreDelta),
                String.format("%.2f", newScore),
                String.format("%.2f", scoreDelta));
    }

    /**
     * 在构建 Prompt 前获取当前推进提示（不更新状态）
     *
     * @param chatroomId 群聊 ID
     * @return 观察式推进提示（null 表示不需要推进）
     */
    public String getProgressionHint(String chatroomId) {
        if (chatroomId == null) return null;

        ConversationState state = conversationStateManager.getState(chatroomId);
        if (state == null) return null;

        double score = state.getProgressionScore();
        if (score < LIGHT_THRESHOLD) return null;

        BotBehavior dominantBehavior = getDominantRecentBehavior(state);
        int consecutiveCount = dominantBehavior != null ? state.countConsecutiveBehavior(dominantBehavior) : 0;

        String hint = generateObservationHint(score, dominantBehavior, consecutiveCount, state);

        log.debug("[Progression] 生成推进提示 chatroom={}, score={}, behavior={}, consecutive={}",
                chatroomId, String.format("%.2f", score), dominantBehavior, consecutiveCount);

        return hint;
    }

    // ===== 内部方法 =====

    /**
     * 计算分数变化量
     */
    private double computeScoreDelta(ConversationState state, BotBehavior currentBehavior) {
        // 获取倒数第二条行为（上一条）
        var behaviors = state.getRecentBehaviors();
        if (behaviors == null || behaviors.size() < 2) return 0;
        BotBehavior prevBehavior = behaviors.get(behaviors.size() - 2);

        // bot 主动推进：ACCEPT / COUNTER / CHANGE_TOPIC / END
        if (isProactiveBehavior(currentBehavior)) {
            return ACTIVE_PROGRESSION_DECAY;
        }

        // 行为相同：递增
        if (currentBehavior.isSameCategory(prevBehavior)) {
            int consecutive = state.countConsecutiveBehavior(currentBehavior);
            double bonus = Math.max(0, (consecutive - 1)) * CONSECUTIVE_BONUS;
            return SAME_BEHAVIOR_INCREMENT + bonus;
        }

        // 行为不同：衰减
        return DIFFERENT_BEHAVIOR_DECAY;
    }

    /**
     * 判断是否是主动推进型行为
     */
    private boolean isProactiveBehavior(BotBehavior behavior) {
        return behavior == BotBehavior.ACCEPT
                || behavior == BotBehavior.COUNTER
                || behavior == BotBehavior.CHANGE_TOPIC
                || behavior == BotBehavior.END;
    }

    /**
     * 获取最近行为中出现最多的（主导行为）
     */
    private BotBehavior getDominantRecentBehavior(ConversationState state) {
        var behaviors = state.getRecentBehaviors();
        if (behaviors == null || behaviors.isEmpty()) return null;

        // 简单策略：取最近一条非 NEUTRAL 行为
        for (int i = behaviors.size() - 1; i >= 0; i--) {
            if (behaviors.get(i) != BotBehavior.NEUTRAL && behaviors.get(i) != BotBehavior.UNKNOWN) {
                return behaviors.get(i);
            }
        }
        return null;
    }

    /**
     * 生成观察式推进提示（非指令式）
     * <p>
     * 原则：
     * - 描述观察到的现象，不下达指令
     * - 提示可能的变化方向，不强制要求
     * - 越高分越具体，但始终保持观察语气
     * </p>
     */
    private String generateObservationHint(double score, BotBehavior behavior,
                                            int consecutiveCount, ConversationState state) {
        StringBuilder sb = new StringBuilder();

        // 基础观察：话题持续时间
        long durationSec = state.topicDurationSeconds();
        String behaviorDesc = describeBehavior(behavior);

        if (score >= STRONG_THRESHOLD) {
            // 强制推进级（>0.9）：明确指出僵局
            sb.append("当前话题已经持续了较长时间");
            if (durationSec > 0) {
                sb.append("（约").append(durationSec / 60).append("分钟）");
            }
            sb.append("，且最近的回应方式比较一致");
            if (behaviorDesc != null) {
                sb.append("（").append(behaviorDesc).append("）");
            }
            sb.append("。这种情况下聊天容易陷入僵局。");
            sb.append("真人一般会在这个节点让对话产生新的变化——");
            sb.append("可能是态度上的转变，可能是用不同方式回应，也可能是自然地把话题带向别处。");

        } else if (score >= MEDIUM_THRESHOLD) {
            // 建议推进级（0.6~0.9）：温和提示
            sb.append("这个话题已经来回聊了好几轮");
            if (behaviorDesc != null) {
                sb.append("，而且回应方式一直比较相似（").append(behaviorDesc).append("）");
            }
            sb.append("。如果继续保持同样的回应，对方可能会觉得聊不动。");
            sb.append("真人通常会在坚持一下之后，尝试换个角度或者稍微松口。");

        } else {
            // 轻提示级（0.3~0.6）：轻微提示
            sb.append("最近几次回复的思路比较接近");
            if (behaviorDesc != null) {
                sb.append("，都偏向").append(behaviorDesc);
            }
            sb.append("。可以自然地让回应有些变化，避免聊天变得单调。");
        }

        return sb.toString();
    }

    /**
     * 将行为枚举转为自然语言描述（用于提示文本）
     */
    private String describeBehavior(BotBehavior behavior) {
        if (behavior == null) return null;
        return switch (behavior) {
            case REFUSE -> "拒绝/推辞";
            case ACCEPT -> "接受/配合";
            case JOKE -> "调侃/开玩笑";
            case COUNTER -> "反问/反客为主";
            case CHANGE_TOPIC -> "转移话题";
            case END -> "收尾/结束话题";
            case NEUTRAL, UNKNOWN -> null;
        };
    }
}
