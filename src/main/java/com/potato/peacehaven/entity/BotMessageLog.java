package com.potato.peacehaven.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 机器人回调消息日志表
 * <p>
 * 持久化 WechatApi 推送的每一条回调消息，用于排查、审计和去重。
 */
@Entity
@Table(name = "bot_message_log",
        indexes = {
                @Index(name = "idx_bml_new_msg_id", columnList = "new_msg_id"),
                @Index(name = "idx_bml_type_name", columnList = "type_name"),
                @Index(name = "idx_bml_received_at", columnList = "received_at")
        })
@Comment("机器人回调消息日志表")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BotMessageLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 事件类型：AddMsg / ModContacts / DelContacts / Offline */
    @Column(name = "type_name", nullable = false, length = 30)
    @Comment("事件类型")
    private String typeName;

    /** 消息类型（仅 AddMsg 时有值）：1/3/34/37/42/43/47/48/49/10000/10002 */
    @Column(name = "msg_type")
    @Comment("消息子类型（AddMsg时）")
    private Integer msgType;

    /** 设备 appId */
    @Column(name = "app_id", length = 100)
    @Comment("设备 appId")
    private String appId;

    /** 登录微信号 wxid */
    @Column(name = "wxid", length = 100)
    @Comment("登录微信号")
    private String wxid;

    /** 发送方 wxid（群消息时为 xxx@chatroom） */
    @Column(name = "from_wxid", length = 100)
    @Comment("发送方 wxid")
    private String fromWxid;

    /** 接收方 wxid */
    @Column(name = "to_wxid", length = 100)
    @Comment("接收方 wxid")
    private String toWxid;

    /** 消息正文（截取前 2000 字符，避免超长） */
    @Column(name = "content", length = 2000)
    @Comment("消息正文")
    private String content;

    /** 推送摘要（PushContent） */
    @Column(name = "push_content", length = 500)
    @Comment("推送摘要")
    private String pushContent;

    /** 微信 NewMsgId（用于去重） */
    @Column(name = "new_msg_id")
    @Comment("微信 NewMsgId，用于去重")
    private Long newMsgId;

    /** 是否为群消息 */
    @Column(name = "is_group", nullable = false)
    @Builder.Default
    @Comment("是否群消息")
    private Boolean isGroup = false;

    /** 群消息真实发送者 wxid（从 Content 切割） */
    @Column(name = "group_sender_wxid", length = 100)
    @Comment("群消息真实发送者 wxid")
    private String groupSenderWxid;

    /** 群聊 ID（@chatroom 结尾） */
    @Column(name = "chatroom_id", length = 100)
    @Comment("群聊 ID")
    private String chatroomId;

    /** 微信侧消息时间（Unix 时间戳） */
    @Column(name = "wx_create_time")
    @Comment("微信侧消息时间戳")
    private Long wxCreateTime;

    /** 服务端接收时间 */
    @CreationTimestamp
    @Column(name = "received_at", nullable = false, updatable = false)
    @Comment("服务端接收时间")
    private LocalDateTime receivedAt;
}
