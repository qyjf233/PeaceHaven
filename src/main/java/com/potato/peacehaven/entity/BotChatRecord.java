package com.potato.peacehaven.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 群聊聊天记录长期存储表
 * <p>
 * 仅存储配置的"目标群聊"中符合条件的消息（默认只保留文本消息）。
 * 后续用于生成向量数据库，支持 RAG 模式训练 AI 分身。
 * <p>
 * 设计要点：
 * <ul>
 *   <li>msgId + appId 联合唯一约束，防止重复入库</li>
 *   <li>processed 标记用于追踪向量化进度（false=未处理 / true=已向量化）</li>
 *   <li>rawContent 保留原始报文，用于排查解析异常</li>
 *   <li>content 为清洗后的纯文本，是向量化的主要数据源</li>
 * </ul>
 */
@Entity
@Table(name = "bot_chat_record",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_bcr_msg_app",
                columnNames = {"msg_id", "app_id"}
        ),
        indexes = {
                @Index(name = "idx_bcr_room_time", columnList = "room_id, create_time"),
                @Index(name = "idx_bcr_processed", columnList = "processed"),
                @Index(name = "idx_bcr_sender", columnList = "sender_wxid"),
                @Index(name = "idx_bcr_create_time", columnList = "create_time")
        })
@Comment("群聊聊天记录表（用于 RAG 向量化训练）")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BotChatRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 微信 NewMsgId（消息唯一标识） */
    @Column(name = "msg_id", nullable = false)
    @Comment("微信 NewMsgId，与 app_id 联合唯一")
    private Long msgId;

    /** 设备 appId（标识哪个设备会话） */
    @Column(name = "app_id", nullable = false, length = 100)
    @Comment("设备 appId")
    private String appId;

    /** 群聊 wxid（xxx@chatroom） */
    @Column(name = "room_id", nullable = false, length = 100)
    @Comment("群聊 wxid")
    private String roomId;

    /** 群聊名称（缓存，便于展示） */
    @Column(name = "room_name", length = 200)
    @Comment("群聊名称")
    private String roomName;

    /** 消息发送者 wxid */
    @Column(name = "sender_wxid", nullable = false, length = 100)
    @Comment("发送者 wxid")
    private String senderWxid;

    /** 发送者昵称（从 bot_group_member 解析） */
    @Column(name = "sender_nick", length = 100)
    @Comment("发送者昵称")
    private String senderNick;

    /** 是否由机器人自己发送 */
    @Column(name = "is_self", nullable = false)
    @Builder.Default
    @Comment("是否自己发送")
    private Boolean isSelf = false;

    /** 消息类型：1=文本 / 3=图片 / 34=语音 / 43=视频 / 49=复合消息 / 47=Emoji */
    @Column(name = "msg_type", nullable = false)
    @Comment("消息类型")
    private Integer msgType;

    /** 清洗后的纯文本消息内容（向量化主要数据源） */
    @Column(name = "content", columnDefinition = "TEXT")
    @Comment("清洗后的纯文本消息内容")
    private String content;

    /** 引用回复消息内容（appmsg type=57 时提取） */
    @Column(name = "quote_content", columnDefinition = "TEXT")
    @Comment("引用回复消息内容")
    private String quoteContent;

    /** 原始完整报文（含 XML，用于调试） */
    @Column(name = "raw_content", columnDefinition = "MEDIUMTEXT")
    @Comment("原始完整报文（含 XML）")
    private String rawContent;

    /** 媒体 URL（图片/视频/文件的 OSS 地址，后续扩展） */
    @Column(name = "media_url", length = 1000)
    @Comment("媒体文件 URL")
    private String mediaUrl;

    /** 微信侧消息时间戳（Unix 秒） */
    @Column(name = "create_time")
    @Comment("微信侧消息时间戳")
    private Long createTime;

    /** 是否已完成向量化处理（false=待处理 / true=已向量化） */
    @Column(name = "processed", nullable = false)
    @Builder.Default
    @Comment("是否已向量化")
    private Boolean processed = false;

    /** 服务端入库时间 */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    @Comment("入库时间")
    private LocalDateTime createdAt;
}
