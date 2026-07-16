package com.potato.peacehaven.ai.retrieval;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单条消息的多维风格特征（由 StyleTagger.analyze 产出）
 * <p>
 * 分为两层：
 * <ul>
 *   <li>Core Persona 维度：humor / sarcasm / warmth / directness —— 稳定人格特质</li>
 *   <li>Expression Mode：formal / slang / length / emoji / punctuation —— 随环境变化的表达模式</li>
 * </ul>
 * <p>
 * 注意：variance（方差）不在此类中计算。单条消息无法产生方差。
 * 由 {@link StyleAggregator} 对一组 StyleFeature 做聚合后产出 variance。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StyleFeature {

    // ===== Core Persona 维度 0-1 =====

    /** 幽默感：段子、玩笑、搞笑表达 */
    private double humorScore;

    /** 讽刺/吐槽：攻击性幽默、反话 */
    private double sarcasmScore;

    /** 温暖度：关心、共情、支持 */
    private double warmthScore;

    /** 直接度：不绕弯子、不客套 */
    private double directnessScore;

    // ===== Expression Mode（非人格，随环境变）=====

    /** 正式程度：真人 ~0.1，AI 默认 ~0.8 */
    private double formalScore;

    /** 俚语/网络用语密度 */
    private double slangScore;

    /** 消息长度（字符数） */
    private int length;

    /** Emoji 使用密度 0-1 */
    private double emojiUsage;

    /** 标点密度（标点字符占比） */
    private double punctuation;

    // ===== 社交上下文维度 0-1 =====

    /** 亲密关系中允许的攻击性幽默 */
    private double intimacyHumor;

    /** 隐藏的共情表达（吐槽中的关心） */
    private double empathyHidden;

    /** 调侃许可度 */
    private double teasingAllowed;

    // ===== 兼容旧逻辑 =====

    /** 风格标签：common / catchphrase / humor / rare */
    private String category;
}
