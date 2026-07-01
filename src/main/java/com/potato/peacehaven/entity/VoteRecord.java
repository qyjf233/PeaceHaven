package com.potato.peacehaven.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "activity_vote_record",
       uniqueConstraints = @UniqueConstraint(columnNames = {"activity_id", "voter_name"}))
@Comment("投票记录表，每人每活动限投一票")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VoteRecord {

    /** 记录ID，自增主键 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("投票记录ID")
    private Long id;

    /** 所属活动，多对一关联 activity 表 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "activity_id", nullable = false)
    @Comment("所属活动ID")
    private Activity activity;

    /** 投给的选项，多对一关联 activity_vote_option 表 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "option_id", nullable = false)
    @Comment("投给的选项ID")
    private VoteOption option;

    /** 投票人游戏名称，与activity_id联合唯一，防重复投票 */
    @Column(name = "voter_name", nullable = false, length = 50)
    @Comment("投票人游戏名称")
    private String voterName;

    /** 投票时间，自动生成 */
    @CreationTimestamp
    @Column(name = "voted_at", updatable = false)
    @Comment("投票时间")
    private LocalDateTime votedAt;
}
