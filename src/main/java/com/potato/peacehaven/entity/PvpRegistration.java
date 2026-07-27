package com.potato.peacehaven.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * PVP 通用报名表
 * 适用于擂台赛、锦标赛等 PVP 类活动
 */
@Entity
@Table(name = "pvp_registration",
       uniqueConstraints = @UniqueConstraint(columnNames = {"activity_id", "user_id", "round_id"}))
@Comment("PVP通用报名表")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PvpRegistration {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("报名ID")
    private Long id;

    /** 关联活动ID */
    @Column(name = "activity_id", nullable = false)
    @Comment("关联活动ID")
    private Long activityId;

    /** 场次ID（用于区分不同轮次报名） */
    @Column(name = "round_id", nullable = false)
    @Builder.Default
    @Comment("场次ID")
    private Integer roundId = 1;

    /** 报名用户 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    @Comment("报名用户")
    private User user;

    /** 胜场数 */
    @Column(name = "wins", nullable = false)
    @Builder.Default
    @Comment("胜场数")
    private Integer wins = 0;

    /** 负场数 */
    @Column(name = "losses", nullable = false)
    @Builder.Default
    @Comment("负场数")
    private Integer losses = 0;

    /** 积分 */
    @Column(name = "points", nullable = false)
    @Builder.Default
    @Comment("积分")
    private Integer points = 0;

    /** 排名（0=未排名） */
    @Column(name = "rank_num", nullable = false)
    @Builder.Default
    @Comment("排名")
    private Integer rankNum = 0;

    /** 完成度（0.0 ~ 1.0，表示比赛进度） */
    @Column(name = "completion", nullable = false)
    @Builder.Default
    @Comment("完成度(0~1)")
    private Double completion = 0.0;

    /** 职业（步枪兵/狙击手/武士） */
    @Column(name = "job", length = 32)
    @Builder.Default
    @Comment("职业(步枪兵/狙击手/武士)")
    private String job = "";

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    @Comment("报名时间")
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    @Comment("最后更新时间")
    private LocalDateTime updatedAt;
}
