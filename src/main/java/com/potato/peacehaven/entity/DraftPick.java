package com.potato.peacehaven.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 点兵选人记录表
 * 每场比赛的每次选人操作记录，支持断线恢复和名单查询
 */
@Entity
@Table(name = "draft_pick",
       uniqueConstraints = @UniqueConstraint(columnNames = {"activity_id", "match_index", "user_id"}))
@Comment("点兵选人记录")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DraftPick {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("记录ID")
    private Long id;

    @Column(name = "activity_id", nullable = false)
    @Comment("关联活动ID")
    private Long activityId;

    @Column(name = "match_index", nullable = false)
    @Comment("比赛场次(0-3)")
    private Integer matchIndex;

    @Column(name = "user_id", nullable = false)
    @Comment("被选玩家用户ID")
    private Long userId;

    @Column(name = "user_name", nullable = false, length = 64)
    @Comment("被选玩家昵称")
    private String userName;

    @Column(name = "team_side", nullable = false, length = 4)
    @Comment("队伍(A/B)")
    private String teamSide;

    @Column(name = "job", length = 32)
    @Builder.Default
    @Comment("职业(步枪兵/狙击手/武士)")
    private String job = "";

    @Column(name = "pick_order", nullable = false)
    @Comment("选人顺序(该队第几个被选)")
    private Integer pickOrder;

    @CreationTimestamp
    @Column(name = "picked_at", updatable = false)
    @Comment("选人时间")
    private LocalDateTime pickedAt;
}
