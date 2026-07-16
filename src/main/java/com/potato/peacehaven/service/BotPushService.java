package com.potato.peacehaven.service;

import com.potato.peacehaven.config.WechatApiProperties;
import com.potato.peacehaven.entity.BotMessageTemplate;
import com.potato.peacehaven.entity.BotPushLog;
import com.potato.peacehaven.entity.BotScheduleConfig;
import com.potato.peacehaven.entity.BotTimedMessage;
import com.potato.peacehaven.repository.BotMessageTemplateRepository;
import com.potato.peacehaven.repository.BotPushLogRepository;
import com.potato.peacehaven.repository.BotScheduleConfigRepository;
import com.potato.peacehaven.repository.BotTimedMessageRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 机器人定时推送调度服务
 * <p>
 * 每分钟扫描一次日程配置，匹配定时消息，渲染模板并发送到群聊。
 * <p>
 * 调度流程：
 * <ol>
 *   <li>检查 bot 是否在线，离线则跳过</li>
 *   <li>查询今日匹配的日程（dayOfWeek=0 表示每天）</li>
 *   <li>对每个日程查对应的定时消息</li>
 *   <li>判断当前时间是否命中发送窗口（1分钟容差）</li>
 *   <li>渲染模板 → 发送到群 → 记录推送日志</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BotPushService {

    private final WechatApiProperties props;
    private final WechatApiService wechatApiService;
    private final BotScheduleConfigRepository scheduleRepo;
    private final BotTimedMessageRepository messageRepo;
    private final BotMessageTemplateRepository templateRepo;
    private final BotPushLogRepository pushLogRepo;
    private final AdminOperationLogService logService;

    /** 推送记录保留天数 */
    private static final int LOG_RETAIN_DAYS = 7;

    @PostConstruct
    void cleanup() {
        LocalDate cutoff = LocalDate.now().minusDays(LOG_RETAIN_DAYS);
        try {
            pushLogRepo.deleteByPushDateBefore(cutoff);
            log.info("已清理 {} 天前的推送记录", LOG_RETAIN_DAYS);
        } catch (Exception e) {
            log.warn("清理推送记录失败: {}", e.getMessage());
        }
    }

    /**
     * 每分钟执行一次推送检查
     */
    @Scheduled(fixedRate = 60_000)
    public void checkAndPush() {
        // 推送开关检查
        if (!props.isPushEnabled()) return;
        // 仅做本地配置检查，不发 HTTP 请求
        if (!props.isConfigured() || !props.isDeviceBound()) return;
        String groupId = props.getGroupId();
        if (groupId == null || groupId.isBlank()) return;

        LocalDate today = LocalDate.now();
        LocalTime now = LocalTime.now().truncatedTo(ChronoUnit.MINUTES);
        int todayDow = today.getDayOfWeek().getValue(); // MONDAY=1 .. SUNDAY=7

        // 查今日匹配的日程（dayOfWeek=0 表示每天，也要匹配）
        List<BotScheduleConfig> schedules = scheduleRepo.findByDayOfWeekIn(List.of(0, todayDow));
        if (schedules.isEmpty()) return;

        // 延迟在线检查：只在真正有消息要发时才检查
        Boolean[] onlineCache = {null};

        for (BotScheduleConfig schedule : schedules) {
            processSchedule(schedule, today, now, onlineCache);
        }
    }

    private void processSchedule(BotScheduleConfig schedule, LocalDate today, LocalTime now, Boolean[] onlineCache) {
        if (schedule.getEventTime() == null) return; // 时间未设置（资源战/争霸赛待手动配置）

        LocalTime eventTime = schedule.getEventTime();
        String eventType = schedule.getEventType();

        // 查该事件类型的所有已启用定时消息
        List<BotTimedMessage> messages = messageRepo.findByEventTypeAndEnabledTrue(eventType);

        for (BotTimedMessage msg : messages) {
            // 计算发送时间 = 活动时间 - 提前分钟数
            LocalTime sendTime = eventTime.minusMinutes(msg.getAdvanceMinutes());

            // 判断当前时间是否命中发送窗口 [sendTime, sendTime+1min)
            if (!isInWindow(now, sendTime)) continue;

            // 检查是否已推送（匹配当前 eventTime，日程时间改了旧日志不匹配）
            if (pushLogRepo.existsByPushDateAndTimedMessageIdAndScheduleConfigIdAndEventTime(
                    today, msg.getId(), schedule.getId(), eventTime)) continue;

            // 确认有消息要发，才检查在线状态（同一次执行周期内只检查一次）
            if (onlineCache[0] == null) {
                onlineCache[0] = checkOnline();
            }
            if (!onlineCache[0]) {
                log.debug("Bot 离线，跳过本次推送");
                return; // 整个 schedule 跳过
            }

            // 渲染消息内容
            String content = renderTemplate(eventType, msg.getId(), msg.getAdvanceMinutes());

            // 发送
            boolean success = sendMessage(content, msg.getMentionAll());

            // 记录日志
            recordPush(eventType, msg.getId(), schedule.getId(), today, now, eventTime, success);

            // 记录操作日志
            if (success) {
                logService.recordBySystem("机器人", "机器人配置", "消息推送",
                        eventType + " 提前" + msg.getAdvanceMinutes() + "分钟提醒" + (msg.getMentionAll() ? " @全体" : ""));
            }
        }
    }

    /**
     * 判断当前时间是否在 [target, target+1min) 窗口内
     */
    private boolean isInWindow(LocalTime now, LocalTime target) {
        long nowMinutes = now.getHour() * 60L + now.getMinute();
        long targetMinutes = target.getHour() * 60L + target.getMinute();
        return nowMinutes == targetMinutes;
    }

    /**
     * 渲染消息模板
     * <p>优先级：专属模板 > 默认模板 > 兜底文本
     */
    private String renderTemplate(String eventType, Long timedMessageId, int advanceMinutes) {
        // 1. 查专属模板
        List<BotMessageTemplate> all = templateRepo.findByEventType(eventType);
        BotMessageTemplate specific = all.stream()
                .filter(t -> t.getTimedMessageId() != null && t.getTimedMessageId().equals(timedMessageId))
                .findFirst()
                .orElse(null);

        if (specific != null) {
            return applyVariables(specific.getTemplateText(), eventType, advanceMinutes);
        }

        // 2. 查默认模板
        BotMessageTemplate defaultTpl = all.stream()
                .filter(t -> t.getTimedMessageId() == null)
                .findFirst()
                .orElse(null);

        if (defaultTpl != null) {
            return applyVariables(defaultTpl.getTemplateText(), eventType, advanceMinutes);
        }

        // 3. 兜底
        return eventType + " 还有 " + advanceMinutes + " 分钟开始，大家记得准时上线！";
    }

    /**
     * 替换模板变量
     */
    private String applyVariables(String template, String eventType, int advanceMinutes) {
        return template
                .replace("${time}", String.valueOf(advanceMinutes))
                .replace("${type}", eventType);
    }

    /**
     * 发送消息到群聊
     */
    private boolean sendMessage(String content, boolean mentionAll) {
        try {
            WechatApiResponse resp = wechatApiService.sendTextToGroup(content, null, mentionAll);
            if (resp.isSuccess()) {
                log.info("推送成功: {}", content);
                return true;
            } else {
                log.warn("推送失败: {}", resp.getMsg());
                return false;
            }
        } catch (Exception e) {
            log.error("推送异常: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 记录推送日志
     */
    private void recordPush(String eventType, Long timedMessageId, Long scheduleConfigId,
                            LocalDate pushDate, LocalTime pushTime, LocalTime eventTime, boolean success) {
        BotPushLog logEntry = BotPushLog.builder()
                .eventType(eventType)
                .timedMessageId(timedMessageId)
                .scheduleConfigId(scheduleConfigId)
                .pushDate(pushDate)
                .pushTime(pushTime)
                .eventTime(eventTime)
                .success(success)
                .build();
        try {
            pushLogRepo.save(logEntry);
        } catch (Exception e) {
            log.warn("推送日志记录失败: {}", e.getMessage());
        }
    }

    /**
     * 检查 bot 是否在线
     */
    private boolean checkOnline() {
        try {
            WechatApiResponse resp = wechatApiService.checkOnline();
            return WechatApiService.isOnlineResponse(resp);
        } catch (Exception e) {
            log.warn("检查在线状态失败: {}", e.getMessage());
            return false;
        }
    }
}
