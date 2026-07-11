package com.potato.peacehaven.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 通用活动配置表
 * 每个活动一条记录，配置以 JSON 格式存储，支持任意活动类型
 */
@Entity
@Table(name = "activity_config")
@Comment("通用活动配置表（JSON存储）")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ActivityConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("配置ID")
    private Long id;

    /** 关联活动ID（一对一） */
    @Column(name = "activity_id", nullable = false, unique = true)
    @Comment("关联活动ID")
    private Long activityId;

    /**
     * 活动配置（JSON格式）
     * 不同活动存储不同结构，例如：
     * - 建筑大赛：{"submitStart":"...", "submitEnd":"...", "judgeStart":"...", ...}
     * - 擂台赛：  {"registerStart":"...", "swissRoundStart":"...", ...}
     */
    @Column(name = "config_json", columnDefinition = "TEXT")
    @Comment("活动配置JSON")
    @Builder.Default
    private String configJson = "{}";

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    @Comment("创建时间")
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    @Comment("最后更新时间")
    private LocalDateTime updatedAt;
}
