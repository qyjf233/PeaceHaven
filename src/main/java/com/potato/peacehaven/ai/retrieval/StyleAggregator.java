package com.potato.peacehaven.ai.retrieval;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 风格特征聚合器
 * <p>
 * 对一组 {@link StyleFeature}（来自同一人/同一场景的多条消息）做统计聚合，
 * 产出均值 + 方差。
 * <p>
 * 方差是真人标志：真人 = 低均值 + 高方差（大部分很短，偶尔长篇），
 * AI = 低方差（回复长度稳定在 50 字左右）。
 * </p>
 */
@Component
public class StyleAggregator {

    /**
     * 聚合结果
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AggregatedStyle {
        // Core Persona 均值
        private double humorAvg, sarcasmAvg, warmthAvg, directnessAvg;
        // Expression Mode 均值
        private double formalAvg, slangAvg, emojiAvg, punctuationAvg;
        private int lengthAvg;
        // 社交上下文均值
        private double intimacyHumorAvg, empathyHiddenAvg, teasingAllowedAvg;
        // ===== Variance（真人标志）=====
        /** 长度方差：真人高方差（1~300字），AI 低方差（40~60字） */
        private double lengthVariance;
        /** 表达形式方差：正式度波动 */
        private double expressionVariance;
        /** 情绪方差：幽默/温暖波动 */
        private double emotionVariance;
        // 统计
        private int sampleCount;
    }

    /**
     * 对一组 StyleFeature 做聚合统计
     *
     * @param features 多条消息的风格特征
     * @return 聚合结果（均值 + 方差），空列表返回 null
     */
    public AggregatedStyle aggregate(List<StyleFeature> features) {
        if (features == null || features.isEmpty()) {
            return null;
        }

        int n = features.size();

        // 均值计算
        double humorSum = 0, sarcasmSum = 0, warmthSum = 0, directnessSum = 0;
        double formalSum = 0, slangSum = 0, emojiSum = 0, punctuationSum = 0;
        double lengthSum = 0;
        double intimacySum = 0, empathySum = 0, teasingSum = 0;

        for (StyleFeature f : features) {
            humorSum += f.getHumorScore();
            sarcasmSum += f.getSarcasmScore();
            warmthSum += f.getWarmthScore();
            directnessSum += f.getDirectnessScore();
            formalSum += f.getFormalScore();
            slangSum += f.getSlangScore();
            emojiSum += f.getEmojiUsage();
            punctuationSum += f.getPunctuation();
            lengthSum += f.getLength();
            intimacySum += f.getIntimacyHumor();
            empathySum += f.getEmpathyHidden();
            teasingSum += f.getTeasingAllowed();
        }

        double humorAvg = humorSum / n;
        double sarcasmAvg = sarcasmSum / n;
        double warmthAvg = warmthSum / n;
        double directnessAvg = directnessSum / n;
        double formalAvg = formalSum / n;
        double slangAvg = slangSum / n;
        double emojiAvg = emojiSum / n;
        double punctuationAvg = punctuationSum / n;
        int lengthAvg = (int) (lengthSum / n);
        double intimacyAvg = intimacySum / n;
        double empathyAvg = empathySum / n;
        double teasingAvg = teasingSum / n;

        // 方差计算
        double lengthVarSum = 0, formalVarSum = 0, emotionVarSum = 0;
        for (StyleFeature f : features) {
            lengthVarSum += Math.pow(f.getLength() - lengthAvg, 2);
            formalVarSum += Math.pow(f.getFormalScore() - formalAvg, 2);
            // 情绪方差 = humor + warmth 的综合波动
            double emotion = (f.getHumorScore() + f.getWarmthScore()) / 2;
            double emotionAvgCombined = (humorAvg + warmthAvg) / 2;
            emotionVarSum += Math.pow(emotion - emotionAvgCombined, 2);
        }

        return AggregatedStyle.builder()
                .humorAvg(humorAvg)
                .sarcasmAvg(sarcasmAvg)
                .warmthAvg(warmthAvg)
                .directnessAvg(directnessAvg)
                .formalAvg(formalAvg)
                .slangAvg(slangAvg)
                .emojiAvg(emojiAvg)
                .punctuationAvg(punctuationAvg)
                .lengthAvg(lengthAvg)
                .intimacyHumorAvg(intimacyAvg)
                .empathyHiddenAvg(empathyAvg)
                .teasingAllowedAvg(teasingAvg)
                .lengthVariance(lengthVarSum / n)
                .expressionVariance(formalVarSum / n)
                .emotionVariance(emotionVarSum / n)
                .sampleCount(n)
                .build();
    }
}
