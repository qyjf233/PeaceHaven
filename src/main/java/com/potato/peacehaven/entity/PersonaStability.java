package com.potato.peacehaven.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 人格稳定性追踪（Persona Engine v4.1 — Phase 4）
 * <p>
 * 记录 30 天内各维度变化幅度，用于 Stability 正则化。
 * <br>
 * Stability 公式（控制学习速度，非融合权重）：
 * <pre>
 * effectiveConfidence = learningConfidence * (1 - stabilityPenalty)
 * newPersona = oldPersona * (1 - effectiveConfidence) + learnedPersona * effectiveConfidence
 * </pre>
 * stability 高 → 学习速度慢（防突变）；stability 低 → 允许快速变化。
 * </p>
 */
@Entity
@Table(name = "persona_stability")
@Comment("人格稳定性追踪（30天变化幅度）")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PersonaStability {

    @Id
    private Long id = 1L;

    /** 幽默度稳定性 1.0=稳定, 0.0=剧烈波动 */
    @Comment("幽默度稳定性 0-1")
    private double humorStability;

    /** 吐槽度稳定性 */
    @Comment("吐槽度稳定性 0-1")
    private double sarcasmStability;

    /** 温暖度稳定性 */
    @Comment("温暖度稳定性 0-1")
    private double warmthStability;

    @UpdateTimestamp
    @Column(name = "updated_at")
    @Comment("更新时间")
    private LocalDateTime updatedAt;
}
