package com.potato.peacehaven.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 关系画像档案（Persona Engine v4.1 — Phase 2）
 * <p>
 * 为每个联系人维护独立画像：与本人的交流风格、亲密度、幽默/吐槽/温暖/正式度。
 * <br>
 * 融合优先级：Relationship Scene > Room Scene > Core Persona。
 * 真人是"看到某个人决定怎么说话"，不是"进入某个群决定怎么说话"。
 * </p>
 */
@Entity
@Table(name = "persona_relationship_profile",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_prp_contact",
                columnNames = {"contact_name"}
        ))
@Comment("关系画像档案（per-person 交流风格）")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RelationshipProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 联系人名称 */
    @Column(name = "contact_name", nullable = false, length = 100)
    @Comment("联系人名称")
    private String contactName;

    /** 关系类型：friend / colleague / family / stranger */
    @Column(name = "relationship_type", length = 50)
    @Comment("关系类型")
    private String relationshipType;

    /** 亲密度 1-10 */
    @Column(name = "intimacy_level")
    @Comment("亲密度 1-10")
    private int intimacyLevel;

    // ===== Core Persona 维度（与此人的交流风格）=====

    @Comment("幽默度 0-1")
    private double humorScore;

    @Comment("吐槽度 0-1")
    private double sarcasmScore;

    @Comment("温暖度 0-1")
    private double warmthScore;

    // ===== Expression Mode =====

    @Comment("正式度 0-1（随环境变）")
    private double formalScore;

    /** 交流风格描述（如"互相嘲讽"） */
    @Column(name = "communication_style", length = 200)
    @Comment("交流风格描述")
    private String communicationStyle;

    /** 样本数（用于计算 confidence） */
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
