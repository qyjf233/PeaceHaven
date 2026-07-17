package com.potato.peacehaven.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 用户手动状态（临时）
 * <p>
 * 用户主动告诉 AI 的近况，如"今天请假""最近头疼"。
 * AI 无法从聊天记录推断这类信息，需要手动注入。
 * </p>
 */
@Entity
@Table(name = "user_manual_status")
@Comment("用户手动临时状态（AI上下文注入）")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserManualStatus {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 状态描述，如"今天请假在家" */
    @Column(name = "status_text", length = 200, nullable = false)
    @Comment("状态描述")
    private String statusText;

    /** 创建时间 */
    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    @Comment("创建时间")
    private LocalDateTime createdAt;

    /** 过期时间（null = 永不过期，需手动删除） */
    @Column(name = "expires_at")
    @Comment("过期时间（null=永不过期）")
    private LocalDateTime expiresAt;
}
