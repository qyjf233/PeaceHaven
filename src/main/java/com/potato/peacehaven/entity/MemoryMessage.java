package com.potato.peacehaven.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 留言板 — 回忆留言
 */
@Entity
@Table(name = "memory_message")
@Comment("留言板留言表")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MemoryMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("留言ID")
    private Long id;

    @Column(nullable = false, length = 50)
    @Comment("昵称")
    private String nickname;

    @Column(nullable = false, length = 500)
    @Comment("留言内容")
    private String content;

    /** PENDING / APPROVED / REJECTED */
    @Column(nullable = false, length = 16)
    @Builder.Default
    @Comment("审核状态")
    private String status = "PENDING";

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    @Comment("创建时间")
    private LocalDateTime createdAt;

    @Column(name = "approved_at")
    @Comment("审核通过时间")
    private LocalDateTime approvedAt;
}
