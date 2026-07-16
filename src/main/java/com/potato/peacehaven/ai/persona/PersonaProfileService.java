package com.potato.peacehaven.ai.persona;

import com.potato.peacehaven.config.AiProperties;
import com.potato.peacehaven.entity.ExpressionProfile;
import com.potato.peacehaven.repository.ExpressionProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 人格画像融合中枢（Persona Engine v4.1）
 * <p>
 * 融合逻辑：
 * <pre>
 * 1. Base(YAML) + LongTerm(DB) → CorePersona（confidence 加权）
 * 2. CurrentState → StateAdjustment（modifier）
 * 3. ExpressionProfile → expressions + avoidExpressions（按 fatigue 分流）
 * 4. ReplyStyle 动态权重（Phase 2-3 有数据后生效）
 * </pre>
 * <p>
 * Phase 1 阶段：只融合 YAML Base + ExpressionProfile。
 * Phase 2-4 会逐步加入 Relationship / Scene / Stability / DriftDetector。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PersonaProfileService {

    private final AiProperties aiProps;
    private final ExpressionProfileRepository expressionProfileRepo;

    /** fatigue 阈值：超过此值的表达加入 avoidExpressions */
    private static final double FATIGUE_THRESHOLD = 0.7;

    /**
     * 融合出有效人格画像
     * <p>
     * 目前 Phase 1 只使用 YAML Base + ExpressionProfile。
     * 后续 Phase 会注入 LearnedStyleConfig / RelationshipProfile / SceneProfile / CurrentState。
     * </p>
     *
     * @param senderNick 对方昵称（Phase 2 用于 RelationshipProfile 查询）
     * @param roomId     群聊 ID（Phase 3 用于 SceneProfile 查询）
     * @return 有效人格画像
     */
    public EffectivePersonaProfile resolve(String senderNick, String roomId) {
        // ===== 1. Base Persona from YAML =====
        AiProperties.PersonalityConfig personality = aiProps.getPrompt().getPersonality();
        String styleDesc = aiProps.getPrompt().getStyleDescription();

        double baseHumor = levelToScore(personality != null ? personality.getHumorLevel() : null, 0.7);
        double baseSarcasm = levelToScore(personality != null ? personality.getSarcasmLevel() : null, 0.4);
        double baseCasual = levelToScore(personality != null ? personality.getCasualLevel() : null, 0.8);
        double baseWarmth = levelToScore(personality != null ? personality.getWarmthLevel() : null, 0.5);

        // Phase 1: Core = Base (no DB data yet, confidence = 0)
        double coreHumor = baseHumor;
        double coreSarcasm = baseSarcasm;
        double coreCasual = baseCasual;
        double coreWarmth = baseWarmth;

        // ===== 2. Expression Profile =====
        List<EffectivePersonaProfile.ExpressionItem> expressions = new ArrayList<>();
        List<EffectivePersonaProfile.ExpressionItem> avoidExpressions = new ArrayList<>();

        try {
            List<ExpressionProfile> allExpressions = expressionProfileRepo.findAll();
            for (ExpressionProfile ep : allExpressions) {
                EffectivePersonaProfile.ExpressionItem item = EffectivePersonaProfile.ExpressionItem.builder()
                        .phrase(ep.getPhrase())
                        .intent(ep.getIntent())
                        .triggerPattern(ep.getTriggerPattern())
                        .allowedScene(ep.getAllowedScene())
                        .frequency(ep.getFrequency())
                        .fatigueScore(ep.getFatigueScore())
                        .build();

                if (ep.getFatigueScore() > FATIGUE_THRESHOLD) {
                    avoidExpressions.add(item);
                } else {
                    expressions.add(item);
                }
            }
        } catch (Exception e) {
            log.warn("[Persona] ExpressionProfile 加载失败: {}", e.getMessage());
        }

        // 按频率排序，高频在前
        expressions.sort((a, b) -> Double.compare(b.getFrequency(), a.getFrequency()));

        // ===== 3. Expression Mode defaults（Phase 1 使用经验值）=====
        double formalScore = 0.1;   // 真人默认低正式
        double slangScore = 0.4;
        int typicalLength = 8;
        double emojiUsage = 0.05;

        // ===== 4. 组装 =====
        EffectivePersonaProfile profile = EffectivePersonaProfile.builder()
                .styleDescription(styleDesc)
                // Core Persona
                .humorScore(coreHumor)
                .sarcasmScore(coreSarcasm)
                .casualScore(coreCasual)
                .warmthScore(coreWarmth)
                // Expression Mode
                .formalScore(formalScore)
                .slangScore(slangScore)
                .typicalLength(typicalLength)
                .emojiUsage(emojiUsage)
                // Variance（Phase 4 填充）
                .lengthVariance(0.0)
                .expressionVariance(0.0)
                // 社交上下文（Phase 4 填充）
                .intimacyHumor(0.0)
                .empathyHidden(0.0)
                .teasingAllowed(0.0)
                // State（Phase 4 填充）
                .lengthAdjust(0)
                .humorAdjust(0.0)
                // Expression
                .expressions(expressions)
                .avoidExpressions(avoidExpressions)
                // 场景（Phase 2-3 填充）
                .relationships(Collections.emptyList())
                .roomScenes(Collections.emptyList())
                // 版本
                .styleVersion(0)
                .personaVersion(0)
                .sceneVersion(0)
                .expressionVersion(expressions.size())
                .stateVersion(0)
                .source("yaml_base")
                .build();

        log.debug("[Persona] resolve() sender={}, expressions={}, avoid={}, source={}",
                senderNick, expressions.size(), avoidExpressions.size(), profile.getSource());

        return profile;
    }

    /**
     * 将 high/medium/low 等级映射为 0-1 分数
     *
     * @param level    等级字符串（high/medium/low），可为 null
     * @param fallback 默认值
     * @return 0-1 分数
     */
    private double levelToScore(String level, double fallback) {
        if (level == null || level.isBlank()) return fallback;
        return switch (level.toLowerCase().trim()) {
            case "high" -> 0.8;
            case "medium" -> 0.5;
            case "low" -> 0.2;
            default -> fallback;
        };
    }
}
