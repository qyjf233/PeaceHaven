package com.potato.peacehaven.entity;

import com.potato.peacehaven.enums.ActivityStatus;
import com.potato.peacehaven.enums.TemplateType;
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

    /** 活动标题，必填 */
    @Column(nullable = false, length = 100)
    @Comment("活动标题")
    private String title;

    /** 活动简介，用于列表页一句话展示 */
    @Column(length = 500)
    @Comment("活动简介，列表页展示")
    private String summary;

    /** 活动详情正文，支持HTML富文本 */
    @Column(columnDefinition = "TEXT")
    @Comment("活动详情正文，支持HTML")
    private String content;

    /** 活动缩略图URL，可为OSS地址或站内路径 */
    @Column(length = 500)
    @Comment("活动缩略图URL")
    private String thumbnail;

    /** 详情页模板类型：BASIC-基础图文 / VOTE-投票 / LEADERBOARD-排行榜 */
    @Enumerated(EnumType.STRING)
    @Column(name = "template_type", nullable = false, length = 20)
    @Comment("模板类型：BASIC/VOTE/LEADERBOARD")
    private TemplateType templateType;

    /** 活动状态：UPCOMING-即将开始 / ONGOING-进行中 / ENDED-已结束 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Comment("活动状态：UPCOMING/ONGOING/ENDED")
    private ActivityStatus status;

    /** 活动开始时间 */
    @Column(name = "start_date")
    @Comment("活动开始时间")
    private LocalDateTime startDate;

    /** 活动结束时间 */
    @Column(name = "end_date")
    @Comment("活动结束时间")
    private LocalDateTime endDate;

    /** 模板扩展配置，JSON格式（如排行榜排序方向等） */
    @Column(name = "config_json", columnDefinition = "JSON")
    @Comment("模板扩展配置JSON")
    private String configJson;

    /** 活动结束后存档的结果数据，JSON格式 */
    @Column(name = "result_json", columnDefinition = "JSON")
    @Comment("活动结果存档JSON")
    private String resultJson;

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
