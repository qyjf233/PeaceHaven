package com.potato.peacehaven.enums;

/**
 * 活动阶段枚举（通用，适用于各类活动）
 * 具体由哪个阶段驱动由活动自身的 config_json 决定
 */
public enum ContestPhase {
    /** 活动未开始 */
    BEFORE_START,
    /** 投稿/报名阶段 */
    SUBMISSION,
    /** 投稿截止~下一阶段开始（审核/准备期） */
    REVIEW,
    /** 评委打分/小组赛阶段 */
    JUDGING,
    /** 打分截止~投票开始（等待投票） */
    PRE_VOTE,
    /** 投票阶段（票数公开） */
    VOTING,
    /** 投票截止/结果公布 */
    RESULTS
}
