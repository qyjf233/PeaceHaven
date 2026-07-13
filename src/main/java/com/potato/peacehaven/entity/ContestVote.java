package com.potato.peacehaven.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "contest_vote",
       uniqueConstraints = @UniqueConstraint(columnNames = {"work_id", "user_id"}))
@Comment("参赛作品投票记录表")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ContestVote {

    /** 投票记录ID */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("投票记录ID")
    private Long id;

    /** 关联作品ID */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "work_id", nullable = false)
    @Comment("关联作品")
    private ContestWork work;

    /** 投票用户ID */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    @Comment("投票用户")
    private User user;

    /** 投票时间 */
    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    @Comment("投票时间")
    private LocalDateTime createdAt;
}
