package com.potato.peacehaven.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 战斗组成员表
 * 通过 userId 关联 User 表，存储战斗组额外信息
 */
@Entity
@Table(name = "combat_member")
@Comment("战斗组成员表")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CombatMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("成员记录ID")
    private Long id;

    /** 关联用户ID（对应 user 表 id） */
    @Column(name = "user_id", nullable = false, unique = true)
    @Comment("关联用户ID")
    private Long userId;

    /** 职业：步枪兵/狙击手/武士 */
    @Column(name = "job_class", nullable = false, length = 20)
    @Comment("职业：步枪兵/狙击手/武士")
    private String jobClass;

    /**
     * 套装组合，逗号分隔，按数字降序排列
     * 格式示例："4精准,2御敌" 或 "3追猎"
     * 每个条目：数字(2/3/4) + 套装名(精准/御敌/追猎/速射/暴徒/决斗)
     */
    @Column(name = "set_bonuses", length = 200)
    @Comment("套装组合，逗号分隔，如4精准,2御敌")
    @Builder.Default
    private String setBonuses = "";

    /** 分组标签：小团/大团先锋/大团/预备役 */
    @Column(name = "group_tag", nullable = false, length = 20)
    @Comment("分组标签：小团/大团先锋/大团/预备役")
    private String groupTag;

    /** 面板图片URL（16:9） */
    @Column(name = "panel_image", length = 500)
    @Comment("面板图片URL（16:9）")
    private String panelImage;

    /** 排序权重，数值越小越靠前 */
    @Column(name = "sort_order", nullable = false)
    @Comment("排序权重，数值越小越靠前")
    @Builder.Default
    private Integer sortOrder = 0;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    @Comment("创建时间")
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    @Comment("最后更新时间")
    private LocalDateTime updatedAt;
}
