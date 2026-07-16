package com.potato.peacehaven.ai.learning;

import com.potato.peacehaven.ai.retrieval.StyleFeature;
import com.potato.peacehaven.ai.retrieval.StyleTagger;
import com.potato.peacehaven.entity.ExpressionProfile;
import com.potato.peacehaven.repository.ExpressionProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 回复置信度追踪器 + Expression Fatigue 追踪
 * <p>
 * 内存滑动窗口（最近 100 条），追踪：
 * <ul>
 *   <li>replyConfidence：LLM 输出的 confidence 分数</li>
 *   <li>personaMatchScore：0.5 styleMatch + 0.3 behaviorMatch + 0.2 llmJudge</li>
 *   <li>Expression fatigue：AI 回复中使用了哪些特色表达</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConfidenceTracker {

    private final ExpressionProfileRepository expressionProfileRepo;
    private final StyleTagger styleTagger;

    /** 滑动窗口大小 */
    private static final int WINDOW_SIZE = 100;

    /** fatigue 使用增量 */
    private static final double FATIGUE_INCREMENT = 0.25;

    /** fatigue 衰减系数 */
    private static final double FATIGUE_DECAY = 0.7;

    /** fatigue 上限 */
    private static final double FATIGUE_MAX = 1.0;

    /** 滑动窗口记录 */
    private final Deque<ReplyRecord> window = new ConcurrentLinkedDeque<>();

    /**
     * 单条回复记录
     */
    public record ReplyRecord(
            double replyConfidence,
            double personaMatchScore,
            LocalDateTime timestamp
    ) {}

    /**
     * 记录一条 AI 回复的置信度
     */
    public void recordReply(double replyConfidence, double personaMatchScore) {
        window.addLast(new ReplyRecord(replyConfidence, personaMatchScore, LocalDateTime.now()));
        while (window.size() > WINDOW_SIZE) {
            window.pollFirst();
        }
    }

    /**
     * 获取窗口内平均置信度
     */
    public double getAverageConfidence() {
        if (window.isEmpty()) return 0.5;
        return window.stream().mapToDouble(ReplyRecord::replyConfidence).average().orElse(0.5);
    }

    /**
     * 获取窗口内平均 personaMatchScore
     */
    public double getAveragePersonaMatch() {
        if (window.isEmpty()) return 0.5;
        return window.stream().mapToDouble(ReplyRecord::personaMatchScore).average().orElse(0.5);
    }

    /**
     * 计算 personaMatchScore（三维）
     * <p>
     * personaMatchScore = 0.5 * styleMatch + 0.3 * behaviorMatch + 0.2 * llmJudge
     * </p>
     *
     * @param replyContent AI 回复内容
     * @param replyFeature 回复的 StyleFeature
     * @param personaHumor 人格 humor 值
     * @param personaSarcasm 人格 sarcasm 值
     * @param personaWarmth 人格 warmth 值
     * @param personaFormal 人格 formal 值
     * @param llmConfidence LLM 自评分数
     * @return personaMatchScore 0-1
     */
    public double computePersonaMatch(String replyContent,
                                       StyleFeature replyFeature,
                                       double personaHumor, double personaSarcasm,
                                       double personaWarmth, double personaFormal,
                                       double llmConfidence) {
        // styleMatch: 回复特征 vs 人格特征的匹配度
        double styleMatch = computeStyleMatch(replyFeature, personaHumor, personaSarcasm,
                personaWarmth, personaFormal);

        // behaviorMatch: 是否违反人格（如不客套的人说"非常感谢"→ 扣分）
        double behaviorMatch = computeBehaviorMatch(replyContent, personaFormal);

        return 0.5 * styleMatch + 0.3 * behaviorMatch + 0.2 * llmConfidence;
    }

    /**
     * 追踪 AI 回复中的特色表达（fatigue 机制）
     * <p>
     * 使用 → fatigue += 0.25, cap 1.0
     * 未使用 → fatigue *= 0.7（衰减）
     * </p>
     *
     * @param aiReply AI 回复内容
     */
    public void trackExpressionUsage(String aiReply) {
        if (aiReply == null || aiReply.isBlank()) return;

        List<ExpressionProfile> allExpressions;
        try {
            allExpressions = expressionProfileRepo.findAll();
        } catch (Exception e) {
            log.warn("[ConfidenceTracker] ExpressionProfile 加载失败: {}", e.getMessage());
            return;
        }

        for (ExpressionProfile ep : allExpressions) {
            boolean used = aiReply.contains(ep.getPhrase());
            if (used) {
                // 使用：fatigue 增加
                ep.setFatigueScore(Math.min(FATIGUE_MAX, ep.getFatigueScore() + FATIGUE_INCREMENT));
                ep.setConsecutiveUsed(ep.getConsecutiveUsed() + 1);
                ep.setLastUsed(LocalDateTime.now());
            } else {
                // 未使用：fatigue 衰减 + 重置连续计数
                ep.setFatigueScore(ep.getFatigueScore() * FATIGUE_DECAY);
                ep.setConsecutiveUsed(0);
            }
        }

        try {
            expressionProfileRepo.saveAll(allExpressions);
        } catch (Exception e) {
            log.warn("[ConfidenceTracker] fatigue 持久化失败: {}", e.getMessage());
        }
    }

    // ===== 内部计算 =====

    private double computeStyleMatch(StyleFeature reply, double pHumor, double pSarcasm,
                                      double pWarmth, double pFormal) {
        if (reply == null) return 0.5;

        double humorDiff = Math.abs(reply.getHumorScore() - pHumor);
        double sarcasmDiff = Math.abs(reply.getSarcasmScore() - pSarcasm);
        double warmthDiff = Math.abs(reply.getWarmthScore() - pWarmth);
        double formalDiff = Math.abs(reply.getFormalScore() - pFormal);

        // 平均偏差 → 匹配度（偏差越大，匹配越低）
        double avgDiff = (humorDiff + sarcasmDiff + warmthDiff + formalDiff) / 4;
        return Math.max(0, 1.0 - avgDiff);
    }

    private double computeBehaviorMatch(String reply, double personaFormal) {
        if (reply == null || reply.isBlank()) return 0.5;

        double score = 1.0;

        // 检测"客套"表达：如果 persona 不客套（formal 低），但回复很客套 → 扣分
        if (personaFormal < 0.3) {
            if (reply.contains("非常感谢") || reply.contains("感谢您的认可") ||
                    reply.contains("我会继续努力") || reply.contains("请您放心")) {
                score -= 0.4;
            }
        }

        // 检测 AI 味道重的表达
        if (reply.contains("作为") && reply.contains("我认为")) score -= 0.2;
        if (reply.contains("首先") && reply.contains("其次") && reply.contains("最后")) score -= 0.3;

        return Math.max(0, score);
    }
}
