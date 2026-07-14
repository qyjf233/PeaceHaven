package com.potato.peacehaven.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "admin_operation_log", indexes = {
        @Index(name = "idx_op_log_module", columnList = "module"),
        @Index(name = "idx_op_log_created_at", columnList = "createdAt")
})
@Comment("管理员操作日志表")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AdminOperationLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("日志ID")
    private Long id;

    /** 操作人昵称 */
    @Column(nullable = false, length = 50)
    @Comment("操作人昵称")
    private String operator;

    /** 操作模块：活动管理、营地成员、营地事务、福利系统、用户管理、作品审核 */
    @Column(nullable = false, length = 20)
    @Comment("操作模块")
    private String module;

    /** 操作动作：新增、修改、删除、审核通过、审核拒绝、抽奖 等 */
    @Column(nullable = false, length = 20)
    @Comment("操作动作")
    private String action;

    /** 操作详情 */
    @Column(length = 500)
    @Comment("操作详情")
    private String detail;

    /** 操作人IP */
    @Column(length = 50)
    @Comment("操作人IP")
    private String ip;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    @Comment("操作时间")
    private LocalDateTime createdAt;
}
