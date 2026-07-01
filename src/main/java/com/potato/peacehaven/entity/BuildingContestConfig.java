package com.potato.peacehaven.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Comment;

import java.time.LocalDateTime;

/**
 * 建筑大赛时间配置
 * 存储各阶段时间节点，驱动功能开放/关闭
 */
@Entity
@Table(name = "building_contest_config")
@Comment("建筑大赛时间配置表")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BuildingContestConfig {

    /** 配置ID */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("配置ID")
    private Long id;

    /** 关联活动ID */
    @Column(name = "activity_id", nullable = false, unique = true)
    @Comment("关联活动ID")
    private Long activityId;

    /** 投稿开始时间 */
    @Column(name = "submit_start")
    @Comment("投稿开始时间")
    private LocalDateTime submitStart;

    /** 投稿截止时间 */
    @Column(name = "submit_end")
    @Comment("投稿截止时间")
    private LocalDateTime submitEnd;

    /** 评委打分开始时间 */
    @Column(name = "judge_start")
    @Comment("评委打分开始时间")
    private LocalDateTime judgeStart;

    /** 评委打分截止时间 */
    @Column(name = "judge_end")
    @Comment("评委打分截止时间")
    private LocalDateTime judgeEnd;

    /** 投票开始时间 */
    @Column(name = "vote_start")
    @Comment("投票开始时间")
    private LocalDateTime voteStart;

    /** 投票截止时间 */
    @Column(name = "vote_end")
    @Comment("投票截止时间")
    private LocalDateTime voteEnd;

    /**
     * 大赛阶段枚举
     */
    public enum ContestPhase {
        /** 活动未开始 */
        BEFORE_START,
        /** 投稿阶段 */
        SUBMISSION,
        /** 投稿截止~评委打分开始（审核期） */
        REVIEW,
        /** 评委打分阶段（分数隐藏） */
        JUDGING,
        /** 评委打分截止~投票开始（等待投票） */
        PRE_VOTE,
        /** 投票阶段（票数公开） */
        VOTING,
        /** 投票截止（公布所有分数） */
        RESULTS
    }

    /**
     * 根据当前时间推导大赛阶段
     */
    public ContestPhase getCurrentPhase() {
        LocalDateTime now = LocalDateTime.now();

        if (submitStart == null || now.isBefore(submitStart)) {
            return ContestPhase.BEFORE_START;
        }
        if (submitEnd != null && now.isBefore(submitEnd)) {
            return ContestPhase.SUBMISSION;
        }
        if (judgeStart != null && now.isBefore(judgeStart)) {
            return ContestPhase.REVIEW;
        }
        if (judgeEnd != null && now.isBefore(judgeEnd)) {
            return ContestPhase.JUDGING;
        }
        if (voteStart != null && now.isBefore(voteStart)) {
            return ContestPhase.PRE_VOTE;
        }
        if (voteEnd != null && now.isBefore(voteEnd)) {
            return ContestPhase.VOTING;
        }
        return ContestPhase.RESULTS;
    }
}
