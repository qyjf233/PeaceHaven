package com.potato.peacehaven.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.CreationTimestamp;

/**
 * AI 分身白名单表
 * <p>
 * 配置哪些群聊或好友可以触发 AI 回复。
 * type="group" 时 wxid 为群聊 ID（xxx@chatroom），
 * type="friend" 时 wxid 为好友 wxid。
 * </p>
 */
@Entity
@Table(name = "bot_ai_whitelist",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_baw_type_wxid",
                columnNames = {"type", "wxid"}
        ))
@Comment("AI 分身白名单表")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BotAiWhitelist {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 类型：group（群聊）/ friend（好友） */
    @Column(name = "type", nullable = false, length = 20)
    @Comment("类型：group/friend")
    private String type;

    /** wxid（群聊 ID 或好友 wxid） */
    @Column(nullable = false, length = 100)
    @Comment("群聊 ID 或好友 wxid")
    private String wxid;

    /** 显示名称（群名 / 好友昵称） */
    @Column(length = 100)
    @Comment("显示名称")
    private String name;

    /** 是否启用 */
    @Column(nullable = false)
    @Builder.Default
    @Comment("是否启用")
    private Boolean enabled = true;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    @Comment("创建时间")
    private java.time.LocalDateTime createdAt;
}
