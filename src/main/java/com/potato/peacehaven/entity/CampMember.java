package com.potato.peacehaven.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "camp_member")
@Comment("营地成员表")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CampMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("成员ID")
    private Long id;

    /** 成员昵称 */
    @Column(nullable = false, length = 50)
    @Comment("成员昵称")
    private String nickname;

    /** 排序权重，数字越小越靠前 */
    @Column(name = "sort_order", nullable = false)
    @Comment("排序权重")
    @Builder.Default
    private Integer sortOrder = 0;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    @Comment("创建时间")
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    @Comment("最后更新时间")
    private LocalDateTime updatedAt;
}
