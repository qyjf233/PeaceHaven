package com.potato.peacehaven.ai.retrieval;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 客观风格特征聚合器
 * <p>
 * 对一组 {@link StyleFeature}（来自同一人/同一场景的多条消息）做统计聚合，
 * 产出客观均值 + 方差。
 * <p>
 * 方差是真人标志：真人 = 低均值 + 高方差（大部分很短，偶尔长篇），
 * AI = 低方差（回复长度稳定在 50 字左右）。
 * </p>
 * <p>
 * 主观语义维度（humor/sarcasm/warmth 等）不再由规则聚合，
 * 改由 LLM Observation 从批量消息中直接生成。
 * </p>
 */
@Component
public class StyleAggregator {

    /**
     * 聚合结果（仅客观特征）
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AggregatedStyle {
        // ===== 客观 Expression Mode 均值 =====
        private double formalAvg;
        private double slangAvg;
        private double emojiAvg;
        private double punctuationAvg;
        private int lengthAvg;

        // ===== Variance（真人标志）=====
        /** 长度方差：真人高方差（1~300字），AI 低方差（40~60字） */
        private double lengthVariance;
        /** 表达形式方差：正式度波动 */
        private double expressionVariance;

        // ===== 统计 =====
        private int sampleCount;
    }

    /**
     * 对一组 StyleFeature 做客观统计聚合
     *
     * @param features 多条消息的风格特征
     * @return 聚合结果（客观均值 + 方差），空列表返回 null
     */
    public AggregatedStyle aggregate(List<StyleFeature> features) {
        if (features == null || features.isEmpty()) {
            return null;
        }

        int n = features.size();

        // 均值计算
        double formalSum = 0, slangSum = 0, emojiSum = 0, punctuationSum = 0;
        double lengthSum = 0;

        for (StyleFeature f : features) {
            formalSum += f.getFormalScore();
            slangSum += f.getSlangScore();
            emojiSum += f.getEmojiUsage();
            punctuationSum += f.getPunctuation();
            lengthSum += f.getLength();
        }

        double formalAvg = formalSum / n;
        double slangAvg = slangSum / n;
        double emojiAvg = emojiSum / n;
        double punctuationAvg = punctuationSum / n;
        int lengthAvg = (int) (lengthSum / n);

        // 方差计算
        double lengthVarSum = 0, formalVarSum = 0;
        for (StyleFeature f : features) {
            lengthVarSum += Math.pow(f.getLength() - lengthAvg, 2);
            formalVarSum += Math.pow(f.getFormalScore() - formalAvg, 2);
        }

        return AggregatedStyle.builder()
                .formalAvg(formalAvg)
                .slangAvg(slangAvg)
                .emojiAvg(emojiAvg)
                .punctuationAvg(punctuationAvg)
                .lengthAvg(lengthAvg)
                .lengthVariance(lengthVarSum / n)
                .expressionVariance(formalVarSum / n)
                .sampleCount(n)
                .build();
    }
}
