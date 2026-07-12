package com.potato.peacehaven.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 通用活动裁判表
 * 适用于任何活动的裁判管理
 */
@Entity
@Table(name = "activity_judge",
       uniqueConstraints = @UniqueConstraint(columnNames = {"activity_id", "user_id"}))
@Comment("通用活动裁判表")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ActivityJudge {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("裁判记录ID")
    private Long id;

    /** 关联活动ID */
    @Column(name = "activity_id", nullable = false)
    @Comment("关联活动ID")
    private Long activityId;

    /** 裁判用户 */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "user_id", nullable = false)
    @Comment("裁判用户")
    private User user;

    /** 裁判角色/头衔（如：主裁判、副裁判） */
    @Column(name = "role_title", length = 50)
    @Comment("裁判角色头衔")
    private String roleTitle;

    /** 排序权重（越小越靠前） */
    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    @Comment("排序权重")
    private Integer sortOrder = 0;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    @Comment("创建时间")
    private LocalDateTime createdAt;
}
