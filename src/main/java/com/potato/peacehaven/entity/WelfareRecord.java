package com.potato.peacehaven.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "welfare_record")
@Comment("福利发放记录表")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WelfareRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("记录ID")
    private Long id;

    /** 福利发放日期 */
    @Column(name = "welfare_date", nullable = false)
    @Comment("福利发放日期")
    private LocalDate welfareDate;

    /** 福利类型：月度幸运儿 / 最佳贡献 */
    @Column(name = "welfare_type", nullable = false, length = 20)
    @Comment("福利类型：月度幸运儿/最佳贡献")
    private String welfareType;

    /** 成员昵称（直接记录） */
    @Column(nullable = false, length = 50)
    @Comment("成员昵称")
    private String nickname;

    /** 贡献值（最佳贡献专用，月度幸运儿为空） */
    @Column(precision = 15, scale = 2)
    @Comment("贡献值（最佳贡献专用）")
    private BigDecimal contribution;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    @Comment("创建时间")
    private LocalDateTime createdAt;
}
