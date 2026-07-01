package com.potato.peacehaven.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "activity")
@Comment("营地活动主表")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Activity {

    /** 活动ID，自增主键 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("活动ID")
    private Long id;

    /** 活动唯一标识，用于URL路由和模板映射，如 changan-building-contest */
    @Column(nullable = false, unique = true, length = 100)
    @Comment("活动唯一标识（slug），用于URL路由")
    private String slug;

    /** 活动标题，必填 */
    @Column(nullable = false, length = 100)
    @Comment("活动标题")
    private String title;

    /** 活动简介，用于列表页一句话展示 */
    @Column(length = 500)
    @Comment("活动简介，列表页展示")
    private String summary;

    /** 活动缩略图URL，可为OSS地址或站内路径 */
    @Column(length = 500)
    @Comment("活动缩略图URL")
    private String thumbnail;

    /** 活动开始时间 */
    @Column(name = "start_date")
    @Comment("活动开始时间")
    private LocalDateTime startDate;

    /** 活动结束时间 */
    @Column(name = "end_date")
    @Comment("活动结束时间")
    private LocalDateTime endDate;

    /** 记录创建时间，自动生成 */
    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    @Comment("创建时间")
    private LocalDateTime createdAt;

    /** 记录最后更新时间，自动维护 */
    @UpdateTimestamp
    @Column(name = "updated_at")
    @Comment("最后更新时间")
    private LocalDateTime updatedAt;
}
