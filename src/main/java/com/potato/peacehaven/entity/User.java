package com.potato.peacehaven.entity;

import com.potato.peacehaven.enums.UserRole;
import com.potato.peacehaven.enums.UserStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "user")
@Comment("用户表")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    /** 用户ID，自增主键 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("用户ID")
    private Long id;

    /** 手机号，唯一索引，用于登录 */
    @Column(nullable = false, unique = true, length = 20)
    @Comment("手机号，登录凭证")
    private String phone;

    /** 用户昵称，默认"用户+手机后四位" */
    @Column(length = 50)
    @Comment("用户昵称")
    private String nickname;

    /** 头像URL */
    @Column(length = 500)
    @Comment("头像URL")
    private String avatar;

    /** 角色：USER-普通用户 / ADMIN-管理员 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Comment("角色：USER/ADMIN")
    @Builder.Default
    private UserRole role = UserRole.USER;

    /** 状态：ACTIVE-正常 / BANNED-封禁 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Comment("状态：ACTIVE/BANNED")
    @Builder.Default
    private UserStatus status = UserStatus.ACTIVE;

    /** 最后登录时间 */
    @Column(name = "last_login_at")
    @Comment("最后登录时间")
    private LocalDateTime lastLoginAt;

    /** 注册时间，自动生成 */
    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    @Comment("注册时间")
    private LocalDateTime createdAt;

    /** 最后更新时间，自动维护 */
    @UpdateTimestamp
    @Column(name = "updated_at")
    @Comment("最后更新时间")
    private LocalDateTime updatedAt;
}
