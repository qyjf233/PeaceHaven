package com.potato.peacehaven.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 点兵演武战绩记录表
 */
@Entity
@Table(name = "draft_battle_record",
       uniqueConstraints = @UniqueConstraint(columnNames = {"activity_id", "user_id", "game_id"}))
@Comment("点兵演武战绩记录")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DraftBattleRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("记录ID")
    private Long id;

    @Column(name = "activity_id", nullable = false)
    @Comment("关联活动ID")
    private Long activityId;

    @Column(name = "user_id", nullable = false)
    @Comment("玩家用户ID")
    private Long userId;

    @Column(name = "user_name", nullable = false, length = 64)
    @Comment("玩家昵称")
    private String userName;

    @Column(name = "game_id", nullable = false)
    @Comment("场次(1-4)")
    private Integer gameId;

    @Column(name = "team", nullable = false, length = 16)
    @Comment("队伍(teamA/teamB)")
    private String team;

    @Column(name = "kills", nullable = false)
    @Builder.Default
    @Comment("击杀数")
    private Integer kills = 0;

    @Column(name = "deaths", nullable = false)
    @Builder.Default
    @Comment("死亡数")
    private Integer deaths = 0;

    @Column(name = "assists", nullable = false)
    @Builder.Default
    @Comment("助攻数")
    private Integer assists = 0;

    @Column(name = "damage", nullable = false)
    @Builder.Default
    @Comment("本场伤害")
    private Long damage = 0L;

    @Column(name = "job", nullable = false, length = 32)
    @Comment("职业(步枪兵/狙击手/武士)")
    private String job;

    @Column(name = "result", nullable = false, length = 8)
    @Comment("结果(WIN/LOSS)")
    private String result;

    @Column(name = "kda", nullable = false)
    @Builder.Default
    @Comment("KDA = (kills+assists)/deaths，deaths=0时除1")
    private Double kda = 0.0;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    @Comment("录入时间")
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    @Comment("更新时间")
    private LocalDateTime updatedAt;

    /** 根据 kills/assists/deaths 自动计算 KDA */
    public void calculateKda() {
        int divisor = (deaths == null || deaths == 0) ? 1 : deaths;
        this.kda = (double) ((kills == null ? 0 : kills) + (assists == null ? 0 : assists)) / divisor;
    }
}
