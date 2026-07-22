package com.potato.peacehaven.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 南希对抗赛 - 队伍选秀记录
 * 记录双将选人系统中的队伍信息和选秀过程
 */
@Entity
@Table(name = "nancy_draft_team",
       uniqueConstraints = @UniqueConstraint(
           name = "uk_nancy_draft_activity_side",
           columnNames = {"activity_id", "team_side"}),
       indexes = {
           @Index(name = "idx_nancy_draft_activity", columnList = "activity_id")
       })
@Comment("南希对抗赛队伍选秀记录")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NancyDraftTeam {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("记录ID")
    private Long id;

    /** 关联活动ID */
    @Column(name = "activity_id", nullable = false)
    @Comment("关联活动ID")
    private Long activityId;

    /** 队伍方: A / B */
    @Column(name = "team_side", nullable = false, length = 5)
    @Comment("队伍方: A/B")
    private String teamSide;

    /** 队伍名称 */
    @Column(name = "team_name", length = 50)
    @Comment("队伍名称")
    private String teamName;

    /** 队长用户ID */
    @Column(name = "captain_user_id")
    @Comment("队长用户ID")
    private Long captainUserId;

    /** 队长昵称 */
    @Column(name = "captain_name", length = 50)
    @Comment("队长昵称")
    private String captainName;

    /** 队长头像 */
    @Column(name = "captain_avatar", length = 500)
    @Comment("队长头像URL")
    private String captainAvatar;

    /**
     * 队员列表 JSON
     * 格式: [{"userId":123,"nickname":"xxx","avatar":"...","role":"突击手"}, ...]
     */
    @Column(name = "member_json", columnDefinition = "TEXT")
    @Comment("队员列表JSON")
    @Builder.Default
    private String memberJson = "[]";

    /** 当前已选人数 */
    @Column(name = "member_count", nullable = false)
    @Builder.Default
    @Comment("当前已选人数")
    private Integer memberCount = 0;

    /** 选秀状态: WAITING / DRAFTING / COMPLETED */
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    @Comment("选秀状态")
    private String status = "WAITING";

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    @Comment("创建时间")
    private LocalDateTime createdAt;
}
