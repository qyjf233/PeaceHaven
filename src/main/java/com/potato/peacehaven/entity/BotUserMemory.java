package com.potato.peacehaven.entity;

import com.potato.peacehaven.ai.memory.MemoryEntry;
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
 * 为每个群聊成员维护一份画像（summary / tags / facts / structuredMemories），
 * AI 回复时加载对方画像作为 prompt 上下文，实现“了解对方”的效果。
 * </p>
 * <p>
 * 记忆存储双轨制：
 * <ul>
 *   <li>旧字段（summary/tags/facts）—— 向后兼容，简单场景仍可使用</li>
 *   <li>新字段（structuredMemories）—— 结构化记忆条目，含 importance/confidence/TTL</li>
 * </ul>
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

    // ===== 结构化记忆（新字段） =====

    /** 结构化记忆条目（JSON 数组，含 importance/confidence/TTL） */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "structured_memories", columnDefinition = "json")
    @Comment("结构化记忆条目 JSON 数组")
    @Builder.Default
    private List<MemoryEntry> structuredMemories = new ArrayList<>();

    // ===== 关系建模（新字段） =====

    /** 关系类型（朋友/同事/陌生人/家人等） */
    @Column(name = "relationship_type", length = 50)
    @Comment("关系类型")
    private String relationshipType;

    /** 亲密度 1-10 */
    @Column(name = "intimacy_score")
    @Comment("亲密度 1-10")
    private Integer intimacyScore;

    /** 交流风格描述（如“喜欢互相调侃”、“正式克制”） */
    @Column(name = "communication_style", length = 200)
    @Comment("交流风格描述")
    private String communicationStyle;

    @UpdateTimestamp
    @Column(name = "updated_at")
    @Comment("最后更新时间")
    private LocalDateTime updatedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    @Comment("创建时间")
    private LocalDateTime createdAt;
}
