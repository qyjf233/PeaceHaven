package com.potato.peacehaven.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 学习风格配置（Persona Engine v4.1 — Phase 4）
 * <p>
 * 单行实体（id=1），存储从真实聊天中学到的风格参数。
 * 由 StyleLearningService 定时更新。
 * <br>
 * confidence 公式（含 distributionFactor）：
 * <pre>
 * confidence = sampleFactor * timeFactor * sceneFactor * distributionFactor
 * sampleFactor       = min(count / 200, 1.0)
 * timeSpanFactor     = min(days / 90, 1.0)
 * timeFactor         = 0.5 + 0.5 * timeSpanFactor
 * sceneFactor        = min(distinctSceneTypes / 4, 1.0)
 * distributionFactor = 消息天数分布均匀度
 * </pre>
 * </p>
 */
@Entity
@Table(name = "learned_style_config")
@Comment("学习风格配置（从真实聊天中学到的风格参数）")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LearnedStyleConfig {

    @Id
    private Long id = 1L;

    /** LLM 提炼的风格描述文本 */
    @Column(name = "style_description", columnDefinition = "TEXT")
    @Comment("LLM 提炼的风格描述")
    private String styleDescription;

    /** LLM 生成的人格观察（Persona Observation，核心驱动） */
    @Column(name = "persona_observation", columnDefinition = "TEXT")
    @Comment("LLM 生成的人格观察（核心驱动，替代分数）")
    private String personaObservation;

    /** 源数据 hash（消息 id 列表 hash，相同则跳过 LLM） */
    @Column(name = "style_source_hash", length = 64)
    @Comment("源数据 hash")
    private String styleSourceHash;

    // ===== Core Persona 维度（不含 formal，formal 在 Expression Mode）=====

    @Comment("幽默度 0-1")
    private double humorScore;

    @Comment("吐槽度 0-1")
    private double sarcasmScore;

    @Comment("随意度 0-1")
    private double casualScore;

    @Comment("温暖度 0-1")
    private double warmthScore;

    // ===== Expression Mode =====

    @Comment("正式度 0-1")
    private double formalScore;

    @Comment("俚语密度 0-1")
    private double slangScore;

    @Comment("平均长度")
    private int avgLength;

    // ===== Variance（真人标志）=====

    @Comment("长度方差")
    private double lengthVariance;

    @Comment("表达形式方差")
    private double expressionVariance;

    // ===== 社交上下文维度 =====

    @Comment("亲密幽默度 0-1")
    private double intimacyHumor;

    @Comment("隐藏共情 0-1")
    private double empathyHidden;

    @Comment("调侃许可度 0-1")
    private double teasingAllowed;

    // ===== 多维置信度 =====

    @Comment("总置信度 0-1")
    private double learningConfidence;

    @Comment("样本因子")
    private double sampleFactor;

    @Comment("时间跨度因子")
    private double timeSpanFactor;

    @Comment("场景因子（distinctSceneTypes/4）")
    private double sceneFactor;

    @Comment("分布均匀度因子")
    private double distributionFactor;

    // ===== 统计 =====

    @Comment("样本总数")
    private int sampleCount;

    @Comment("不同场景类型数")
    private int sceneCount;

    // ===== 双版本 =====

    /** 句式/用词变化版本 */
    @Comment("风格版本")
    private int styleVersion;

    /** 价值观/性格变化版本 */
    @Comment("人格版本")
    private int personaVersion;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    @Comment("创建时间")
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    @Comment("更新时间")
    private LocalDateTime updatedAt;
}
