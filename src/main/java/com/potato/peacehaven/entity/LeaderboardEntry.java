package com.potato.peacehaven.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "activity_leaderboard_entry")
@Comment("排行榜记录表")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LeaderboardEntry {

    /** 记录ID，自增主键 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("排行榜记录ID")
    private Long id;

    /** 所属活动，多对一关联 activity 表 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "activity_id", nullable = false)
    @Comment("所属活动ID")
    private Activity activity;

    /** 玩家游戏名称 */
    @Column(name = "player_name", nullable = false, length = 50)
    @Comment("玩家游戏名称")
    private String playerName;

    /** 成绩分数（支持小数，如耗时秒数） */
    @Column(nullable = false)
    @Comment("成绩分数")
    private Double score;

    /** 排名位置，数字越小越靠前 */
    @Column(name = "rank_position")
    @Comment("排名位置")
    private Integer rankPosition;

    /** 记录提交时间，自动生成 */
    @CreationTimestamp
    @Column(name = "submitted_at", updatable = false)
    @Comment("记录提交时间")
    private LocalDateTime submittedAt;
}
