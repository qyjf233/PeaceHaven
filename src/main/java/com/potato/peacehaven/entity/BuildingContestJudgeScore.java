package com.potato.peacehaven.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 建筑大赛裁判评分记录
 * 每位裁判对每个作品的独立评分，打分后不可修改
 */
@Entity
@Table(name = "building_contest_judge_score",
       uniqueConstraints = @UniqueConstraint(columnNames = {"work_id", "judge_id"}))
@Comment("建筑大赛裁判评分表")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BuildingContestJudgeScore {

    /** 评分记录ID */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("评分记录ID")
    private Long id;

    /** 关联作品 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "work_id", nullable = false)
    @Comment("关联作品")
    private BuildingContestWork work;

    /** 裁判用户 */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "judge_id", nullable = false)
    @Comment("裁判用户")
    private User judge;

    /** 评分（0.0~10.0，精度小数点后1位） */
    @Column(nullable = false)
    @Comment("评分（0~10，精度1位小数）")
    private Double score;

    /** 打分时间 */
    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    @Comment("打分时间")
    private LocalDateTime createdAt;
}
