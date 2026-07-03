package com.potato.peacehaven.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 建筑大赛抽象票记录
 * 每人每个活动只能投一票抽象票，通过 (activity_id, user_id) 唯一约束保证
 */
@Entity
@Table(name = "building_contest_abstract_vote",
       uniqueConstraints = @UniqueConstraint(columnNames = {"activity_id", "user_id"}))
@Comment("建筑大赛抽象票记录（每人每活动限一票）")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BuildingContestAbstractVote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("抽象票记录ID")
    private Long id;

    /** 关联活动ID */
    @Column(name = "activity_id", nullable = false)
    @Comment("关联活动ID")
    private Long activityId;

    /** 投给的作品 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "work_id", nullable = false)
    @Comment("投给的作品")
    private BuildingContestWork work;

    /** 投票用户 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    @Comment("投票用户")
    private User user;

    /** 投票时间 */
    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    @Comment("投票时间")
    private LocalDateTime createdAt;

    /** 最后更新时间（改投时更新） */
    @UpdateTimestamp
    @Column(name = "updated_at")
    @Comment("最后更新时间")
    private LocalDateTime updatedAt;
}
