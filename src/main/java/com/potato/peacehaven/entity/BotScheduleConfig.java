package com.potato.peacehaven.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Entity
@Table(name = "bot_schedule_config")
@Comment("机器人定时推送日程配置表")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BotScheduleConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 事件类型：zombie_horde / iron_hand / resource_war / patrol / championship / battle_appointment */
    @Column(nullable = false, length = 30)
    @Comment("事件类型")
    private String eventType;

    /** 星期几（1=周一 ... 7=周日），仅周期型事件使用 */
    @Column(name = "day_of_week")
    @Comment("星期几 1=周一~7=周日")
    private Integer dayOfWeek;

    /** 时刻，仅周期型事件使用 */
    @Column(name = "event_time")
    @Comment("事件时刻")
    private LocalTime eventTime;

    /** 具体日期，仅一次型事件使用 */
    @Column(name = "event_date")
    @Comment("事件日期（一次型）")
    private LocalDate eventDate;

    /** 具体日期时间，仅一次型事件使用（约战等） */
    @Column(name = "event_datetime")
    @Comment("事件日期时间（一次型）")
    private LocalDateTime eventDatetime;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
