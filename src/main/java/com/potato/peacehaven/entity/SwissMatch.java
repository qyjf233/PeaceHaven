package com.potato.peacehaven.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Swiss 瑞士轮 + Elimination 淘汰赛 比赛记录表
 * 每场比赛记录两名选手的对阵信息、裁判、状态和结果
 * 通过 stage 字段区分瑞士轮(SWISS)和淘汰赛(QUARTER_FINAL/SEMI_FINAL/FINAL/THIRD_PLACE)
 */
@Entity
@Table(name = "swiss_match",
       uniqueConstraints = @UniqueConstraint(
           name = "uk_swiss_match_round_order",
           columnNames = {"activity_id", "round_number", "match_order"}),
       indexes = {
           @Index(name = "idx_swiss_match_activity_round", columnList = "activity_id, round_number"),
           @Index(name = "idx_swiss_match_activity_status", columnList = "activity_id, status"),
           @Index(name = "idx_swiss_match_activity_stage", columnList = "activity_id, stage")
       })
@Comment("Swiss瑞士轮比赛记录表")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SwissMatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("比赛ID")
    private Long id;

    /** 关联活动ID */
    @Column(name = "activity_id", nullable = false)
    @Comment("关联活动ID")
    private Long activityId;

    /** 轮次号（从1开始） */
    @Column(name = "round_number", nullable = false)
    @Comment("轮次号")
    private Integer roundNumber;

    /** 本场在当前轮的排序序号 */
    @Column(name = "match_order", nullable = false)
    @Comment("当前轮内排序序号")
    private Integer matchOrder;

    /** 选手1 ID */
    @Column(name = "player1_id", nullable = false)
    @Comment("选手1用户ID")
    private Long player1Id;

    /** 选手2 ID */
    @Column(name = "player2_id", nullable = false)
    @Comment("选手2用户ID")
    private Long player2Id;

    /** 选手1昵称（冗余，避免 join） */
    @Column(name = "player1_name", length = 50)
    @Comment("选手1昵称")
    private String player1Name;

    /** 选手2昵称（冗余） */
    @Column(name = "player2_name", length = 50)
    @Comment("选手2昵称")
    private String player2Name;

    /** 比赛开始时选手1的积分 */
    @Column(name = "player1_score", nullable = false)
    @Builder.Default
    @Comment("比赛时选手1积分")
    private Integer player1Score = 0;

    /** 比赛开始时选手2的积分 */
    @Column(name = "player2_score", nullable = false)
    @Builder.Default
    @Comment("比赛时选手2积分")
    private Integer player2Score = 0;

    /** 胜者ID（null=未决出） */
    @Column(name = "winner_id")
    @Comment("胜者用户ID")
    private Long winnerId;

    /** 胜者昵称 */
    @Column(name = "winner_name", length = 50)
    @Comment("胜者昵称")
    private String winnerName;

    /** 分配的裁判用户ID */
    @Column(name = "referee_id")
    @Comment("裁判用户ID")
    private Long refereeId;

    /** 裁判昵称 */
    @Column(name = "referee_name", length = 50)
    @Comment("裁判昵称")
    private String refereeName;

    /** 比赛状态: WAITING / ONGOING / COMPLETED */
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    @Comment("比赛状态: WAITING/ONGOING/COMPLETED")
    private String status = "WAITING";

    /** 比赛开始时间 */
    @Column(name = "start_time")
    @Comment("比赛开始时间")
    private LocalDateTime startTime;

    /** 比赛结束时间 */
    @Column(name = "end_time")
    @Comment("比赛结束时间")
    private LocalDateTime endTime;

    // ==================== 淘汰赛扩展字段 ====================

    /** 比赛阶段: SWISS(默认) / QUARTER_FINAL / SEMI_FINAL / FINAL / THIRD_PLACE */
    @Column(name = "stage", length = 20)
    @Builder.Default
    @Comment("比赛阶段: SWISS/QUARTER_FINAL/SEMI_FINAL/FINAL/THIRD_PLACE")
    private String stage = "SWISS";

    /** BO赛制: null(Swiss) / 1(BO1) / 3(BO3) */
    @Column(name = "best_of")
    @Comment("BO赛制: null/1/3")
    private Integer bestOf;

    /** 选手1小局胜场(BO3) */
    @Column(name = "player1_game_win")
    @Builder.Default
    @Comment("选手1小局胜场")
    private Integer player1GameWin = 0;

    /** 选手2小局胜场(BO3) */
    @Column(name = "player2_game_win")
    @Builder.Default
    @Comment("选手2小局胜场")
    private Integer player2GameWin = 0;

    /** 当前BO进行到第几局 */
    @Column(name = "current_game_index")
    @Builder.Default
    @Comment("当前BO第几局")
    private Integer currentGameIndex = 0;

    /** 对阵分组: QF_A/QF_B/QF_C/QF_D/SF1/SF2/FINAL/THIRD */
    @Column(name = "bracket_group", length = 20)
    @Comment("对阵分组标识")
    private String bracketGroup;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    @Comment("创建时间")
    private LocalDateTime createdAt;
}
