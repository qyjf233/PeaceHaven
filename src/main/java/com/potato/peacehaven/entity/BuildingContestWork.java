package com.potato.peacehaven.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "building_contest_work",
       uniqueConstraints = @UniqueConstraint(columnNames = {"activity_id", "user_id"}))
@Comment("建筑大赛投稿作品表")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BuildingContestWork {

    /** 作品ID，自增主键 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("作品ID")
    private Long id;

    /** 关联活动ID */
    @Column(name = "activity_id", nullable = false)
    @Comment("关联活动ID")
    private Long activityId;

    /** 投稿用户ID */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "user_id", nullable = false)
    @Comment("投稿用户")
    private User user;

    /** 作品标题 */
    @Column(nullable = false, length = 100)
    @Comment("作品标题")
    private String title;

    /** 作品简介 */
    @Column(length = 500)
    @Comment("作品简介")
    private String description;

    /** 作品图片URL（OSS地址） */
    @Column(name = "image_url", nullable = false, length = 500)
    @Comment("作品图片URL")
    private String imageUrl;

    /** 审核状态：PENDING-待审核 / APPROVED-已通过 / REJECTED-已拒绝 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Comment("审核状态：PENDING/APPROVED/REJECTED")
    @Builder.Default
    private WorkStatus status = WorkStatus.PENDING;

    /** 网络投票票数 */
    @Column(name = "vote_count", nullable = false)
    @Comment("网络投票票数")
    @Builder.Default
    private Integer voteCount = 0;

    /** 裁判平均分（满分10分，暂未评分为null） */
    @Column(name = "judge_score")
    @Comment("裁判平均分（满分10）")
    private Double judgeScore;

    /** 最终得分（裁判70% + 投票30%） */
    @Column(name = "final_score")
    @Comment("最终得分")
    private Double finalScore;

    /** 创建时间，自动生成 */
    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    @Comment("投稿时间")
    private LocalDateTime createdAt;

    /** 最后更新时间 */
    @UpdateTimestamp
    @Column(name = "updated_at")
    @Comment("最后更新时间")
    private LocalDateTime updatedAt;

    /** 作品审核状态枚举 */
    public enum WorkStatus {
        PENDING, APPROVED, REJECTED
    }
}
