package com.potato.peacehaven.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 抽奖参与者
 */
@Entity
@Table(name = "lottery_participant",
       uniqueConstraints = @UniqueConstraint(columnNames = {"lottery_id", "user_id"}))
@Comment("抽奖参与者表")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LotteryParticipant {

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

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    @Comment("参与时间")
    private LocalDateTime createdAt;
}
