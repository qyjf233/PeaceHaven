package com.potato.peacehaven.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "page_visit", indexes = {
        @Index(name = "idx_visit_created_at", columnList = "createdAt"),
        @Index(name = "idx_visit_page", columnList = "page")
})
@Comment("页面访问记录表")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PageVisit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 访问IP */
    @Column(length = 50, nullable = false)
    @Comment("访问IP")
    private String ip;

    /** 访问页面标识 */
    @Column(length = 100, nullable = false)
    @Comment("页面标识：首页/活动列表/活动详情等")
    private String page;

    /** 访问路径 */
    @Column(length = 500, nullable = false)
    @Comment("原始URL路径")
    private String path;

    /** 用户昵称（已登录时记录） */
    @Column(length = 50)
    @Comment("用户昵称")
    private String nickname;

    /** 来源页 */
    @Column(length = 500)
    @Comment("Referer来源")
    private String referer;

    /** UserAgent */
    @Column(length = 500)
    @Comment("浏览器UA")
    private String userAgent;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    @Comment("访问时间")
    private LocalDateTime createdAt;
}
