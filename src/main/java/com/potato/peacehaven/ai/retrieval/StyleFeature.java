package com.potato.peacehaven.ai.retrieval;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单条消息的客观风格特征（由 StyleTagger.analyze 产出）
 * <p>
 * 只包含可量化、跨模型一致的客观特征：
 * <ul>
 *   <li>length — 消息字符数</li>
 *   <li>emojiUsage — emoji 密度</li>
 *   <li>punctuation — 标点密度</li>
 *   <li>formalScore — 正式词密度（正则匹配，客观）</li>
 *   <li>slangScore — 俚语密度（正则匹配，客观）</li>
 *   <li>category — 风格标签（common/catchphrase/humor/rare，用于 RAG 多样性）</li>
 * </ul>
 * 主观语义维度（humor/sarcasm/warmth/directness）已由 LLM Observation 替代，
 * 不再由规则打分。参见 {@link com.potato.peacehaven.ai.learning.StyleLearningService#generatePersonaObservation}。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StyleFeature {

    // ===== 客观 Expression Mode =====

    /** 正式词密度（正则匹配，客观） */
    private double formalScore;

    /** 俚语/网络用语密度（正则匹配，客观） */
    private double slangScore;

    /** 消息长度（字符数） */
    private int length;

    /** Emoji 使用密度 0-1 */
    private double emojiUsage;

    /** 标点密度（标点字符占比） */
    private double punctuation;

    // ===== 风格标签（用于 RAG 多样性，不用于打分）=====

    /** 风格标签：common / catchphrase / humor / rare */
    private String category;
}
