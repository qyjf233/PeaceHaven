package com.potato.peacehaven.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "bot_message_template")
@Comment("机器人消息模板表")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BotMessageTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 关联的事件类型 */
    @Column(name = "event_type", nullable = false, length = 30)
    @Comment("关联事件类型")
    private String eventType;

    /** 关联的定时消息ID，null 表示默认模板 */
    @Column(name = "timed_message_id")
    @Comment("关联定时消息ID，null为默认模板")
    private Long timedMessageId;

    /** 模板文本，支持 ${time} 等变量 */
    @Column(name = "template_text", nullable = false, length = 500)
    @Comment("模板文本，支持变量如${time}")
    private String templateText;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
