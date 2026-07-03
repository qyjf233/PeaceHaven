package com.potato.peacehaven.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 首页营地简介统计数据（单行配置表，只维护 id=1 这一条记录）
 * 管理员直接在数据库中更新数值和图片URL即可
 */
@Entity
@Table(name = "site_stats")
@Comment("首页营地简介统计数据（单行配置）")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SiteStats {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("主键ID，固定为1")
    private Long id;

    /** 营地成员数 */
    @Column(name = "member_count", nullable = false)
    @Comment("营地成员数")
    @Builder.Default
    private Integer memberCount = 0;

    /** 活动举办数 */
    @Column(name = "event_count", nullable = false)
    @Comment("活动举办数")
    @Builder.Default
    private Integer eventCount = 0;

    /** 参与战役数 */
    @Column(name = "battle_count", nullable = false)
    @Comment("参与战役数")
    @Builder.Default
    private Integer battleCount = 0;

    /** 营地简介配图URL */
    @Column(name = "about_image", length = 500)
    @Comment("营地简介配图URL")
    private String aboutImage;

    /** 最后更新时间，自动维护 */
    @UpdateTimestamp
    @Column(name = "updated_at")
    @Comment("最后更新时间")
    private LocalDateTime updatedAt;
}
