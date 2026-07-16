package com.potato.peacehaven.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Comment;

/**
 * 表达在不同场景中的使用计数（negative evidence）
 * <p>
 * 记录某表达在某场景下用过多少次，用于区分：
 * "他喜欢用牛福" vs "他在朋友场景用牛福，工作场景从不用"
 * <br>
 * 例：phrase="牛福", scene="friend", count=28; phrase="牛福", scene="work", count=0
 * </p>
 */
@Entity
@Table(name = "persona_expression_scene",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_pes_expr_scene",
                columnNames = {"expression_id", "scene_type"}
        ))
@Comment("表达场景使用计数（negative evidence）")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExpressionSceneUsage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 关联 ExpressionProfile.id */
    @Column(name = "expression_id", nullable = false)
    @Comment("关联 persona_expression.id")
    private Long expressionId;

    /** 场景类型（friend / work / family / stranger / private） */
    @Column(name = "scene_type", nullable = false, length = 50)
    @Comment("场景类型")
    private String sceneType;

    /** 该场景下使用次数 */
    @Comment("使用次数")
    private int usageCount;
}
