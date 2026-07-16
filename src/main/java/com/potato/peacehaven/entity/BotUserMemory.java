package com.potato.peacehaven.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 聊天对象长期记忆表
 * <p>
 * 为每个群聊成员维护一份画像（summary / tags / facts），
 * AI 回复时加载对方画像作为 prompt 上下文，实现"了解对方"的效果。
 * </p>
 */
@Entity
@Table(name = "bot_user_memory",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_bum_wxid",
                columnNames = {"wxid"}
        ))
@Comment("聊天对象长期记忆表（用户画像）")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BotUserMemory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 用户 wxid（唯一） */
    @Column(nullable = false, length = 100)
    @Comment("用户 wxid")
    private String wxid;

    /** 群内昵称 */
    @Column(length = 100)
    @Comment("群内昵称")
    private String nickname;

    /** 综合画像描述（如：一个喜欢打游戏的程序员，说话直接，偶尔吐槽） */
    @Column(columnDefinition = "TEXT")
    @Comment("综合画像描述")
    private String summary;

    /** 标签（JSON 数组：["Java", "杭州", "LOL"]） */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "json")
    @Comment("标签 JSON 数组")
    @Builder.Default
    private List<String> tags = new ArrayList<>();

    /** 事实（JSON 数组：["养了一只猫叫小花", "喜欢深夜写代码"]） */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "json")
    @Comment("事实 JSON 数组")
    @Builder.Default
    private List<String> facts = new ArrayList<>();

    @UpdateTimestamp
    @Column(name = "updated_at")
    @Comment("最后更新时间")
    private LocalDateTime updatedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    @Comment("创建时间")
    private LocalDateTime createdAt;
}
