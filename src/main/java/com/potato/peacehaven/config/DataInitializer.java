package com.potato.peacehaven.config;

import com.potato.peacehaven.entity.*;
import com.potato.peacehaven.repository.*;
import com.potato.peacehaven.service.ContestService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements ApplicationRunner {

    private final ActivityRepository activityRepository;
    private final ActivityConfigRepository configRepository;
    private final ActivityJudgeRepository judgeRepository;
    private final UserRepository userRepository;
    private final BotScheduleConfigRepository botScheduleConfigRepository;

    @Override
    public void run(ApplicationArguments args) {
        initBuildingContest();
        initBattleShowdown();
        initBotSchedules();
    }

    /** 初始化机器人定时推送基础配置（首次启动时） */
    private void initBotSchedules() {
        if (botScheduleConfigRepository.count() > 0) return;

        botScheduleConfigRepository.save(BotScheduleConfig.builder()
                .eventType("zombie_horde").dayOfWeek(6).eventTime(LocalTime.of(20, 30)).build());
        botScheduleConfigRepository.save(BotScheduleConfig.builder()
                .eventType("iron_hand").dayOfWeek(7).eventTime(LocalTime.of(20, 30)).build());
        botScheduleConfigRepository.save(BotScheduleConfig.builder()
                .eventType("patrol").dayOfWeek(0).eventTime(LocalTime.of(19, 30)).build());

        log.info("已初始化机器人定时推送基础配置");
    }

    private void initBuildingContest() {
        String slug = "building-master-1";
        Activity activity = activityRepository.findBySlug(slug).orElse(null);
        if (activity == null) {
            activity = Activity.builder()
                    .slug(slug)
                    .title("长安建筑大赛")
                    .summary("展示建筑创意，交流建造技巧，评选最具创意与观赏性的庄园作品")
                    .startDate(LocalDateTime.of(2026, 7, 1, 0, 0))
                    .endDate(LocalDateTime.of(2026, 7, 31, 23, 59))
                    .hasWorkSubmission(true)
                    .build();
            activity = activityRepository.save(activity);
            log.info("已自动创建建筑大赛活动记录: slug={}", slug);
        }

        // 初始化时间配置（如果不存在）
        if (configRepository.findByActivityId(activity.getId()).isEmpty()) {
            Map<String, String> configData = new LinkedHashMap<>();
            configData.put("submitStart", "2026-07-05T00:00");
            configData.put("submitEnd", "2026-07-14T23:30");
            configData.put("judgeStart", "2026-07-15T00:00");
            configData.put("judgeEnd", "2026-07-15T23:59");
            configData.put("voteStart", "2026-07-16T00:00");
            configData.put("voteEnd", "2026-07-20T23:30");

            ActivityConfig config = ActivityConfig.builder()
                    .activityId(activity.getId())
                    .configJson(ContestService.mapToJson(configData))
                    .build();
            configRepository.save(config);
            log.info("已创建建筑大赛时间配置");
        }

        // 初始化裁判（按手机号指定）
        // initJudges(activity.getId());
    }

    private void initBattleShowdown() {
        String slug = "battle-showdown-1";
        Activity activity = activityRepository.findBySlug(slug).orElse(null);
        if (activity == null) {
            activity = Activity.builder()
                    .slug(slug)
                    .title("1v1 擂台赛")
                    .summary("瑞士轮积分赛 + 淘汰赛，谁是长安最强战力？")
                    .startDate(LocalDateTime.of(2026, 7, 1, 0, 0))
                    .endDate(LocalDateTime.of(2026, 7, 20, 23, 59))
                    .build();
            activity = activityRepository.save(activity);
            log.info("已自动创建擂台赛活动记录: slug={}", slug);
        }

        // 初始化时间配置（如果不存在）
        if (configRepository.findByActivityId(activity.getId()).isEmpty()) {
            String timelineJson = "["
                + "{\"label\":\"报名期\",\"icon\":\"📋\",\"phase\":\"register\",\"start\":\"2026-07-01T00:00\",\"end\":\"2026-07-05T23:59\"},"
                + "{\"label\":\"瑞士轮\",\"icon\":\"🔄\",\"phase\":\"swiss\",\"start\":\"2026-07-06T00:00\",\"end\":\"2026-07-12T23:59\"},"
                + "{\"label\":\"淘汰赛\",\"icon\":\"⚔️\",\"phase\":\"elimination\",\"start\":\"2026-07-13T00:00\",\"end\":\"2026-07-15T23:59\"},"
                + "{\"label\":\"决赛&颁奖\",\"icon\":\"🏆\",\"phase\":\"finals\",\"start\":\"2026-07-16T20:00\",\"end\":\"2026-07-16T23:59\"}"
                + "]";

            Map<String, String> configData = new LinkedHashMap<>();
            configData.put("timeline", timelineJson);

            ActivityConfig config = ActivityConfig.builder()
                    .activityId(activity.getId())
                    .configJson(ContestService.mapToJson(configData))
                    .build();
            configRepository.save(config);
            log.info("已创建擂台赛时间配置");
        }
    }

    /**
     * 初始化裁判：通过手机号查找用户并赋予裁判身份
     * 如需添加裁判，在此处配置手机号即可
     */
    private void initJudges(Long activityId) {
        // 配置裁判手机号列表（在此添加）
        String[] judgePhones = {};

        log.info("开始初始化裁判，活动ID: {}，当前已有裁判数: {}", activityId,
                judgeRepository.findByActivityIdOrderBySortOrderAsc(activityId).size());

        int sortOrder = 0;
        for (String phone : judgePhones) {
            User user = userRepository.findByPhone(phone).orElse(null);
            if (user == null) {
                log.warn("裁判初始化：手机号 {} 未找到对应用户，请先注册该手机号", phone);
                continue;
            }
            if (!judgeRepository.existsByActivityIdAndUserId(activityId, user.getId())) {
                ActivityJudge judge = ActivityJudge.builder()
                        .activityId(activityId)
                        .user(user)
                        .sortOrder(sortOrder++)
                        .build();
                judgeRepository.save(judge);
                log.info("已添加裁判: {} (ID:{}, 手机:{})", user.getNickname(), user.getId(), phone);
            } else {
                log.info("裁判已存在: {} (ID:{}, 手机:{})", user.getNickname(), user.getId(), phone);
            }
        }
    }
}
