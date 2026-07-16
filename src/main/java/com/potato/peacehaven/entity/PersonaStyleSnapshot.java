package com.potato.peacehaven.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 人格/风格快照（Persona Engine v4.1 — Phase 4）
 * <p>
 * 每次学习产生一条快照，记录当时的风格参数，用于：
 * <ul>
 *   <li>DriftDetector 对比 30 天趋势</li>
 *   <li>双版本追踪（styleVersion vs personaVersion）</li>
 *   <li>效果回溯（哪个版本像本人）</li>
 * </ul>
 * </p>
 */
@Entity
@Table(name = "persona_style_snapshot")
@Comment("人格/风格快照（学习历史记录）")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PersonaStyleSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 风格版本（句式/用词变化） */
    @Comment("风格版本")
    private int styleVersion;

    /** 人格版本（价值观/性格变化） */
    @Comment("人格版本")
    private int personaVersion;

    // ===== Core Persona 维度 =====

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

    /** LLM 提炼的风格描述 */
    @Column(name = "style_description", columnDefinition = "TEXT")
    @Comment("风格描述")
    private String styleDescription;

    /** 学习置信度 */
    @Comment("学习置信度 0-1")
    private double learningConfidence;

    /** 样本数 */
    @Comment("样本数")
    private int sampleCount;

    /** 场景数 */
    @Comment("场景数")
    private int sceneCount;

    /** 触发原因（如"新增200条消息" / "humorScore变化>0.1"） */
    @Column(length = 200)
    @Comment("触发原因")
    private String trigger;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    @Comment("创建时间")
    private LocalDateTime createdAt;
}
