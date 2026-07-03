package com.potato.peacehaven.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 建筑大赛裁判身份映射
 * 将用户与特定活动关联为裁判角色
 */
@Entity
@Table(name = "building_contest_judge",
       uniqueConstraints = @UniqueConstraint(columnNames = {"activity_id", "user_id"}))
@Comment("建筑大赛裁判映射表")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BuildingContestJudge {

    /** 裁判记录ID */
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

    /** 创建时间 */
    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    @Comment("创建时间")
    private LocalDateTime createdAt;

    /** 直播间链接（主播裁判专属，非空时显示「前往直播间」按钮） */
    @Column(name = "live_room_url", length = 500)
    @Comment("直播间链接，非空时显示前往直播间按钮")
    private String liveRoomUrl;
}
