package com.potato.peacehaven.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "sms_verification_code", indexes = {
        @Index(name = "idx_phone_used", columnList = "phone, used")
})
@Comment("短信验证码记录表")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SmsVerificationCode {

    /** 记录ID，自增主键 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("验证码记录ID")
    private Long id;

    /** 接收验证码的手机号 */
    @Column(nullable = false, length = 20)
    @Comment("接收手机号")
    private String phone;

    /** 6位数字验证码 */
    @Column(nullable = false, length = 6)
    @Comment("验证码")
    private String code;

    /** 过期时间（创建后5分钟） */
    @Column(name = "expire_at", nullable = false)
    @Comment("过期时间")
    private LocalDateTime expireAt;

    /** 是否已被使用，防止重复验证 */
    @Column(nullable = false)
    @Comment("是否已使用")
    @Builder.Default
    private Boolean used = false;

    /** 创建时间，自动生成 */
    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    @Comment("创建时间")
    private LocalDateTime createdAt;
}
