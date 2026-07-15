package com.potato.peacehaven.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "bot_timed_message")
@Comment("机器人定时消息配置表")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BotTimedMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 关联的事件类型：尸潮 / 铁手 / 资源战 / 巡逻 / 争霸赛 / 约战 */
    @Column(name = "event_type", nullable = false, length = 30)
    @Comment("关联事件类型")
    private String eventType;

    /** 提前分钟数（如 30 = 活动开始前30分钟推送） */
    @Column(name = "advance_minutes", nullable = false)
    @Comment("提前分钟数")
    private Integer advanceMinutes;

    /** 是否 @全体 */
    @Column(name = "mention_all", nullable = false)
    @Comment("是否@全体")
    private Boolean mentionAll;

    /** 自定义消息文本（可选） */
    @Column(name = "message_text", length = 500)
    @Comment("自定义消息文本")
    private String messageText;

    /** 是否启用 */
    @Column(nullable = false)
    @Builder.Default
    private Boolean enabled = true;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
