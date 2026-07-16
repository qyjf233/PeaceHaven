package com.potato.peacehaven.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 场景画像档案（Persona Engine v4.1 — Phase 3）
 * <p>
 * 按群聊/场景类型维护风格画像：朋友群、工作群、家庭群、私聊、陌生人。
 * <br>
 * 融合优先级低于 RelationshipProfile（per-person > per-group）。
 * </p>
 */
@Entity
@Table(name = "persona_scene_profile",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_psp_scene",
                columnNames = {"scene_type"}
        ))
@Comment("场景画像档案（per-group 交流风格）")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SceneProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 场景类型：friend_group / work_chat / family / private / stranger */
    @Column(name = "scene_type", nullable = false, length = 50)
    @Comment("场景类型")
    private String sceneType;

    // ===== Core Persona 维度（此场景下的交流风格）=====

    @Comment("幽默度 0-1")
    private double humorScore;

    @Comment("吐槽度 0-1")
    private double sarcasmScore;

    @Comment("温暖度 0-1")
    private double warmthScore;

    @Comment("随意度 0-1")
    private double casualScore;

    // ===== Expression Mode =====

    @Comment("正式度 0-1")
    private double formalScore;

    /** 样本数 */
    @Column(name = "sample_count")
    @Comment("样本数")
    private int sampleCount;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    @Comment("创建时间")
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    @Comment("更新时间")
    private LocalDateTime updatedAt;
}
