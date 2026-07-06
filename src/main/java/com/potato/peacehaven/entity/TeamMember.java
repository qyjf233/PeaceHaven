package com.potato.peacehaven.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 管理组成员表
 * 管理员直接在数据库中增删改记录即可更新首页展示
 */
@Entity
@Table(name = "team_member")
@Comment("管理组成员表")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TeamMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("成员ID")
    private Long id;

    /** 头像URL（OSS地址或站内路径） */
    @Column(name = "avatar", length = 500)
    @Comment("头像URL")
    private String avatar;

    /** 关联用户ID（对应 user 表 id） */
    @Column(name = "user_id", nullable = false)
    @Comment("关联用户ID")
    @Builder.Default
    private Long userId = 0L;

    /** 头衔/职位，如：市长、副市长、后勤部长 */
    @Column(name = "role", nullable = false, length = 50)
    @Comment("头衔/职位")
    private String role;

    /** 座右铭（不含「」括号，模板渲染时自动包裹） */
    @Column(name = "motto", length = 100)
    @Comment("座右铭")
    private String motto;

    /** 标签，多个用逗号分隔，如：创始人,传奇耐串王,会喵喵叫 */
    @Column(name = "tags", length = 200)
    @Comment("标签，多个用逗号分隔")
    private String tags;

    /** 排序权重，数值越小越靠前 */
    @Column(name = "sort_order", nullable = false)
    @Comment("排序权重，数值越小越靠前")
    @Builder.Default
    private Integer sortOrder = 0;

    /** 记录创建时间 */
    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    @Comment("创建时间")
    private LocalDateTime createdAt;

    /** 记录最后更新时间 */
    @UpdateTimestamp
    @Column(name = "updated_at")
    @Comment("最后更新时间")
    private LocalDateTime updatedAt;
}
