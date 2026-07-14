package com.potato.peacehaven.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "camp_affair")
@Comment("营地事务记录表")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CampAffair {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("记录ID")
    private Long id;

    /** 成员昵称（直接记录，不关联成员表） */
    @Column(nullable = false, length = 50)
    @Comment("成员昵称")
    private String nickname;

    /** 事务类型：资源战 / 尸潮 / 铁手 / 巡逻 */
    @Column(name = "affair_type", nullable = false, length = 20)
    @Comment("事务类型：资源战/尸潮/铁手/巡逻")
    private String affairType;

    /** 事务日期（仅日期，不含时间） */
    @Column(name = "affair_date", nullable = false)
    @Comment("事务日期")
    private LocalDate affairDate;

    /** 营地排名（全服排名，非必填，同一批次相同） */
    @Column(name = "camp_ranking")
    @Comment("营地排名（全服排名，非必填）")
    private Integer campRanking;

    /** 成员个人排名（非必填，1~N） */
    @Column(name = "member_ranking")
    @Comment("成员个人排名（非必填）")
    private Integer memberRanking;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    @Comment("创建时间")
    private LocalDateTime createdAt;
}
