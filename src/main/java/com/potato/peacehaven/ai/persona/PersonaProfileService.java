package com.potato.peacehaven.ai.persona;

import com.potato.peacehaven.config.AiProperties;
import com.potato.peacehaven.entity.*;
import com.potato.peacehaven.repository.*;
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
 * 1. Base(YAML) + LearnedStyleConfig(DB) → CorePersona（confidence 加权融合）
 * 2. RelationshipProfile → per-person 上下文微调
 * 3. SceneProfile → per-group 上下文微调
 * 4. CurrentStateProfile → 状态 modifier（精力/压力影响回复风格）
 * 5. ExpressionProfile → expressions + avoidExpressions（按 fatigue 分流）
 * </pre>
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PersonaProfileService {

    private final AiProperties aiProps;
    private final ExpressionProfileRepository expressionProfileRepo;
    private final LearnedStyleConfigRepository learnedStyleRepo;
    private final RelationshipProfileRepository relationshipProfileRepo;
    private final SceneProfileRepository sceneProfileRepo;
    private final CurrentStateProfileRepository currentStateRepo;

    /** fatigue 阈值：超过此值的表达加入 avoidExpressions */
    private static final double FATIGUE_THRESHOLD = 0.7;

    /** LearnedStyleConfig 置信度阈值：低于此值不使用 DB 数据 */
    private static final double LEARNING_CONFIDENCE_THRESHOLD = 0.1;

    /**
     * 融合出有效人格画像
     *
     * @param senderNick 对方昵称（用于 RelationshipProfile 查询）
     * @param roomId     群聊 ID（用于 SceneProfile 查询，暂未使用）
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

        double coreHumor = baseHumor;
        double coreSarcasm = baseSarcasm;
        double coreCasual = baseCasual;
        double coreWarmth = baseWarmth;
        double formalScore = 0.1;
        double slangScore = 0.4;
        int typicalLength = 8;
        double lengthVariance = 0.0;
        double expressionVariance = 0.0;
        double intimacyHumor = 0.0;
        double empathyHidden = 0.0;
        double teasingAllowed = 0.0;
        int styleVersion = 0;
        int personaVersion = 0;
        String source = "yaml_base";

        // ===== 2. LearnedStyleConfig (DB) — confidence 加权融合 =====
        try {
            LearnedStyleConfig lsc = learnedStyleRepo.findById(1L).orElse(null);
            if (lsc != null && lsc.getLearningConfidence() > LEARNING_CONFIDENCE_THRESHOLD) {
                double w = lsc.getLearningConfidence(); // DB 权重
                double b = 1.0 - w;                     // YAML 权重
                coreHumor = b * baseHumor + w * lsc.getHumorScore();
                coreSarcasm = b * baseSarcasm + w * lsc.getSarcasmScore();
                coreCasual = b * baseCasual + w * lsc.getCasualScore();
                coreWarmth = b * baseWarmth + w * lsc.getWarmthScore();
                formalScore = lsc.getFormalScore();
                slangScore = lsc.getSlangScore();
                typicalLength = lsc.getAvgLength() > 0 ? lsc.getAvgLength() : typicalLength;
                lengthVariance = lsc.getLengthVariance();
                expressionVariance = lsc.getExpressionVariance();
                intimacyHumor = lsc.getIntimacyHumor();
                empathyHidden = lsc.getEmpathyHidden();
                teasingAllowed = lsc.getTeasingAllowed();
                styleVersion = lsc.getStyleVersion();
                personaVersion = lsc.getPersonaVersion();
                // DB 有风格描述时覆盖 YAML
                if (lsc.getStyleDescription() != null && !lsc.getStyleDescription().isBlank()) {
                    styleDesc = lsc.getStyleDescription();
                }
                source = "yaml+" + String.format("%.0f%%db", w * 100);
            }
        } catch (Exception e) {
            log.warn("[Persona] LearnedStyleConfig 加载失败: {}", e.getMessage());
        }

        // ===== 3. Expression Profile =====
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

        // ===== 4. Relationship Profile (per-person 微调) =====
        List<EffectivePersonaProfile.RelationshipItem> relationships = new ArrayList<>();
        if (senderNick != null && !senderNick.isBlank()) {
            try {
                relationshipProfileRepo.findByContactName(senderNick).ifPresent(rp -> {
                    relationships.add(EffectivePersonaProfile.RelationshipItem.builder()
                            .contactName(rp.getContactName())
                            .relationshipType(rp.getRelationshipType())
                            .intimacyLevel(rp.getIntimacyLevel())
                            .humorScore(rp.getHumorScore())
                            .sarcasmScore(rp.getSarcasmScore())
                            .warmthScore(rp.getWarmthScore())
                            .formalScore(rp.getFormalScore())
                            .communicationStyle(rp.getCommunicationStyle())
                            .build());
                });
            } catch (Exception e) {
                log.warn("[Persona] RelationshipProfile 加载失败: {}", e.getMessage());
            }
        }

        // ===== 5. Scene Profile (per-group 微调) =====
        List<EffectivePersonaProfile.SceneItem> roomScenes = new ArrayList<>();
        try {
            sceneProfileRepo.findBySampleCountGreaterThan(5).forEach(sp -> {
                roomScenes.add(EffectivePersonaProfile.SceneItem.builder()
                        .sceneType(sp.getSceneType())
                        .humorScore(sp.getHumorScore())
                        .sarcasmScore(sp.getSarcasmScore())
                        .warmthScore(sp.getWarmthScore())
                        .formalScore(sp.getFormalScore())
                        .build());
            });
        } catch (Exception e) {
            log.warn("[Persona] SceneProfile 加载失败: {}", e.getMessage());
        }

        // ===== 6. CurrentState modifier =====
        int lengthAdjust = 0;
        double humorAdjust = 0.0;
        try {
            CurrentStateProfile cs = currentStateRepo.findById(1L).orElse(null);
            if (cs != null) {
                // 高精力 → 回复可更长；低精力 → 回复更短
                if (cs.getEnergy() > 0.7) lengthAdjust = 5;
                else if (cs.getEnergy() < 0.3) lengthAdjust = -5;
                // 高压力 → 略降低幽默
                if (cs.getStress() > 0.7) humorAdjust = -0.1;
                // 高社交模式 → 略提升幽默
                if (cs.getSocialMode() > 0.7) humorAdjust = 0.05;
            }
        } catch (Exception e) {
            log.warn("[Persona] CurrentStateProfile 加载失败: {}", e.getMessage());
        }

        // ===== 7. 组装 =====
        double emojiUsage = 0.05;

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
                // Variance
                .lengthVariance(lengthVariance)
                .expressionVariance(expressionVariance)
                // 社交上下文
                .intimacyHumor(intimacyHumor)
                .empathyHidden(empathyHidden)
                .teasingAllowed(teasingAllowed)
                // State
                .lengthAdjust(lengthAdjust)
                .humorAdjust(humorAdjust)
                // Expression
                .expressions(expressions)
                .avoidExpressions(avoidExpressions)
                // 场景
                .relationships(relationships)
                .roomScenes(roomScenes)
                // 版本
                .styleVersion(styleVersion)
                .personaVersion(personaVersion)
                .sceneVersion(roomScenes.size())
                .expressionVersion(expressions.size())
                .stateVersion(lengthAdjust != 0 || humorAdjust != 0 ? 1 : 0)
                .source(source)
                .build();

        log.debug("[Persona] resolve() sender={}, expressions={}, avoid={}, rels={}, scenes={}, source={}",
                senderNick, expressions.size(), avoidExpressions.size(),
                relationships.size(), roomScenes.size(), profile.getSource());

        return profile;
    }

    /**
     * 将 high/medium/low 等级映射为 0-1 分数
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
