package com.potato.peacehaven.ai.persona;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 有效人格画像（Persona Engine v4.1 融合输出）
 * <p>
 * 由 {@link PersonaProfileService} 融合后产出，供 PromptBuilder 消费。
 * <pre>
 * Core Persona   = Base(YAML) * (1-C) + LongTerm(DB) * C
 * Reply Style    = dynamicWeight * Relationship + ... * Room + ... * Core
 * Effective      = ReplyStyle + StateModifier + ExpressionHints
 * </pre>
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EffectivePersonaProfile {

    // ===== Core Persona（humor/sarcasm/warmth/casual）=====

    private String styleDescription;
    private double humorScore;
    private double sarcasmScore;
    private double casualScore;
    private double warmthScore;

    // ===== Expression Mode（formal/slang/length/emoji，随环境变）=====

    private double formalScore;
    private double slangScore;
    private int typicalLength;
    private double emojiUsage;

    // ===== Variance（真人标志）=====

    private double lengthVariance;
    private double expressionVariance;

    // ===== 社交上下文 =====

    private double intimacyHumor;
    private double empathyHidden;
    private double teasingAllowed;

    // ===== State Adjustment（modifier，不参与人格融合）=====

    /** 长度调整百分比（如 -20 表示比平时更短） */
    private int lengthAdjust;
    /** 幽默度调整（如 +0.2 表示今天更活泼） */
    private double humorAdjust;

    // ===== Expression Hints =====

    /** 可用表达（fatigue < 0.7） */
    private List<ExpressionItem> expressions;
    /** 需避免表达（fatigue > 0.7） */
    private List<ExpressionItem> avoidExpressions;

    // ===== 场景（Phase 2-3 填充）=====

    /** 关系画像列表 */
    private List<RelationshipItem> relationships;
    /** 房间场景列表 */
    private List<SceneItem> roomScenes;

    // ===== 版本（5 维缓存 key）=====

    private int styleVersion;
    private int personaVersion;
    private int sceneVersion;
    private int expressionVersion;
    private int stateVersion;

    /** 数据来源标识 */
    private String source;

    // ===== 内嵌 DTO =====

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ExpressionItem {
        private String phrase;
        private String intent;
        private String triggerPattern;
        private String allowedScene;
        private double frequency;
        private double fatigueScore;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RelationshipItem {
        private String contactName;
        private String relationshipType;
        private int intimacyLevel;
        private double humorScore;
        private double sarcasmScore;
        private double warmthScore;
        private double formalScore;
        private String communicationStyle;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SceneItem {
        private String sceneType;
        private double humorScore;
        private double sarcasmScore;
        private double warmthScore;
        private double formalScore;
    }
}
