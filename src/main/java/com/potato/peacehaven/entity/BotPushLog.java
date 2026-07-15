package com.potato.peacehaven.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Comment;

import java.time.LocalDate;
import java.time.LocalTime;

@Entity
@Table(name = "bot_push_log",
        uniqueConstraints = @UniqueConstraint(
                columnNames = {"push_date", "timed_message_id", "schedule_config_id", "event_time"}))
@Comment("机器人推送记录表")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BotPushLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_type", nullable = false, length = 30)
    @Comment("事件类型")
    private String eventType;

    @Column(name = "timed_message_id", nullable = false)
    @Comment("定时消息ID")
    private Long timedMessageId;

    @Column(name = "schedule_config_id", nullable = false)
    @Comment("日程配置ID")
    private Long scheduleConfigId;

    @Column(name = "push_date", nullable = false)
    @Comment("推送日期")
    private LocalDate pushDate;

    @Column(name = "event_time", nullable = false)
    @Comment("当时的活动时间，用于防重复：日程时间改了旧记录不匹配")
    private LocalTime eventTime;

    @Column(name = "push_time", nullable = false)
    @Comment("推送时间")
    private LocalTime pushTime;

    @Column(nullable = false)
    @Comment("是否成功")
    private Boolean success;

    @Column(name = "error_message", length = 500)
    @Comment("错误信息")
    private String errorMessage;
}
