package com.potato.peacehaven.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 抽奖活动表
 */
@Entity
@Table(name = "lottery")
@Comment("抽奖活动表")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Lottery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("抽奖ID")
    private Long id;

    @Column(nullable = false, length = 100)
    @Comment("奖品名称")
    private String title;

    @Column(length = 500)
    @Comment("奖品描述")
    private String description;

    @Column(name = "image_url", length = 500)
    @Comment("奖品图片URL")
    private String imageUrl;

    @Column(name = "total_prizes", nullable = false)
    @Comment("奖品份数")
    private Integer totalPrizes;

    @Column(name = "start_date", nullable = false)
    @Comment("开始时间")
    private LocalDateTime startDate;

    @Column(name = "end_date", nullable = false)
    @Comment("截止时间")
    private LocalDateTime endDate;

    /** OPEN / DRAWN */
    @Column(nullable = false, length = 16)
    @Builder.Default
    @Comment("状态：OPEN/DRAWN")
    private String status = "OPEN";

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    @Comment("创建时间")
    private LocalDateTime createdAt;
}
