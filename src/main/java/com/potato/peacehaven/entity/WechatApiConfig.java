package com.potato.peacehaven.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * WechatApi 配置（单行记录）
 * 运行时可热更新，无需重启服务
 */
@Entity
@Table(name = "wechat_api_config")
@Comment("WechatApi 微信机器人配置表")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WechatApiConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** API 基础地址（如 https://api.wechatapi.net） */
    @Column(name = "base_url", length = 255)
    @Comment("API 基础地址")
    private String baseUrl;

    /** 账户级鉴权 Token */
    @Column(name = "token", length = 255)
    @Comment("鉴权 Token")
    private String token;

    /** 设备 ID（appId） */
    @Column(name = "app_id", length = 100)
    @Comment("设备 ID")
    private String appId;

    /** Webhook 回调地址 */
    @Column(name = "callback_url", length = 500)
    @Comment("Webhook 回调地址")
    private String callbackUrl;

    /** 目标群聊 ID（格式：xxxxxxxx@chatroom） */
    @Column(name = "group_id", length = 100)
    @Comment("目标群聊 ID")
    private String groupId;

    @UpdateTimestamp
    @Column(name = "updated_at")
    @Comment("最后更新时间")
    private LocalDateTime updatedAt;
}
