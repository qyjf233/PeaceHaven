package com.potato.peacehaven.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Comment;

import java.time.LocalDateTime;

@Entity
@Table(name = "bot_group_member",
        uniqueConstraints = @UniqueConstraint(columnNames = {"wxid"}))
@Comment("群聊成员表（从 WechatApi 同步）")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BotGroupMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 群成员 wxid */
    @Column(nullable = false, length = 100)
    @Comment("群成员 wxid")
    private String wxid;

    /** 微信昵称 */
    @Column(length = 100)
    @Comment("微信昵称")
    private String nickName;

    /** 群内昵称（群名片） */
    @Column(name = "display_name", length = 100)
    @Comment("群内昵称")
    private String displayName;

    @Column(name = "big_head_img_url", length = 500)
    @Comment("大头像 URL")
    private String bigHeadImgUrl;

    @Column(name = "small_head_img_url", length = 500)
    @Comment("小头像 URL")
    private String smallHeadImgUrl;

    /** 角色: owner / admin / member */
    @Column(length = 20)
    @Comment("群角色: owner/admin/member")
    private String role;

    @Column(name = "synced_at", nullable = false)
    @Comment("最后同步时间")
    private LocalDateTime syncedAt;

    /**
     * 获取有效昵称（displayName 优先，fallback nickName）
     */
    public String getEffectiveName() {
        if (displayName != null && !displayName.isBlank()) {
            return displayName;
        }
        return nickName != null ? nickName : wxid;
    }
}
