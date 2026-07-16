package com.potato.peacehaven.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 当前状态画像（Persona Engine v4.1 — Phase 4）
 * <p>
 * 单行实体（id=1），记录近 7 天的状态指标。
 * 作为 modifier 注入 Prompt，不参与人格融合。
 * <br>
 * 更新频率：每天最多一次（stateVersion 每天最多 +1）。
 * </p>
 */
@Entity
@Table(name = "current_state_profile")
@Comment("当前状态画像（近7天状态 modifier）")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CurrentStateProfile {

    @Id
    private Long id = 1L;

    /** 精力 0-1（高=活跃，低=低落） */
    @Comment("精力 0-1")
    private double energy;

    /** 压力 0-1（高=压力大） */
    @Comment("压力 0-1")
    private double stress;

    /** 社交模式 0-1（高=社交活跃，低=独处） */
    @Comment("社交模式 0-1")
    private double socialMode;

    /** 近 7 天消息数 */
    @Column(name = "message_count_7d")
    @Comment("近7天消息数")
    private int messageCount7d;

    /** 状态版本号（每天最多 +1） */
    @Column(name = "state_version")
    @Comment("状态版本号")
    private int stateVersion;

    @UpdateTimestamp
    @Column(name = "updated_at")
    @Comment("更新时间")
    private LocalDateTime updatedAt;
}
