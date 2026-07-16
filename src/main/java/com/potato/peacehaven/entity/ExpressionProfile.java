package com.potato.peacehaven.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 表达资产档案（Persona Engine v4.1）
 * <p>
 * 记录用户每个特色表达的频率、意图、触发条件和使用场景。
 * 含 fatigue 机制：连续使用 → fatigueScore 升高 → Prompt 注入"避免使用"。
 * </p>
 * <p>
 * 例：phrase="不是哥们", intent="surprise/mock", triggerPattern="unexpected_statement",
 *     allowedScene="friend", fatigueScore=0.3
 * </p>
 */
@Entity
@Table(name = "persona_expression",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_pe_phrase",
                columnNames = {"phrase"}
        ))
@Comment("表达资产档案（特色表达频率/意图/疲劳度）")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExpressionProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 表达短语（如"不是哥们"） */
    @Column(nullable = false, length = 200)
    @Comment("表达短语")
    private String phrase;

    /** 全局出现频率 0-1 */
    @Comment("全局频率 0-1")
    private double frequency;

    /** 置信度 0-1 */
    @Comment("置信度 0-1")
    private double confidence;

    // ===== Intent（防止变词典）=====

    /** 意图标签（如"surprise/mock/confusion"） */
    @Column(length = 200)
    @Comment("意图标签")
    private String intent;

    /** 允许使用的场景（如"friend"） */
    @Column(name = "allowed_scene", length = 100)
    @Comment("允许使用的场景")
    private String allowedScene;

    /** 触发条件（如"unexpected_statement"） */
    @Column(name = "trigger_pattern", length = 200)
    @Comment("触发条件")
    private String triggerPattern;

    // ===== Fatigue 机制 =====

    /** 疲劳度 0-1（连续使用升高，不用则 *= 0.7 衰减） */
    @Comment("疲劳度 0-1")
    private double fatigueScore;

    /** 近期连续使用次数 */
    @Comment("连续使用次数")
    private int consecutiveUsed;

    /** 最后使用时间 */
    @Column(name = "last_used")
    @Comment("最后使用时间")
    private LocalDateTime lastUsed;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    @Comment("创建时间")
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    @Comment("更新时间")
    private LocalDateTime updatedAt;
}
