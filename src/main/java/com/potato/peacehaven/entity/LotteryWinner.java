package com.potato.peacehaven.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 抽奖中奖者
 */
@Entity
@Table(name = "lottery_winner")
@Comment("抽奖中奖者表")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LotteryWinner {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("记录ID")
    private Long id;

    @Column(name = "lottery_id", nullable = false)
    @Comment("关联抽奖ID")
    private Long lotteryId;

    @Column(name = "user_id", nullable = false)
    @Comment("用户ID")
    private Long userId;

    @Column(name = "user_name", nullable = false, length = 64)
    @Comment("用户昵称")
    private String userName;

    @Column(length = 200)
    @Comment("收货地址")
    private String address;

    @Column(length = 20)
    @Comment("联系电话")
    private String phone;

    @Column(name = "shipping_filled")
    @Builder.Default
    @Comment("收货信息是否已填写")
    private Boolean shippingFilled = false;

    @Column(name = "filled_at")
    @Comment("收货信息填写时间")
    private LocalDateTime filledAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    @Comment("中奖时间")
    private LocalDateTime createdAt;
}
