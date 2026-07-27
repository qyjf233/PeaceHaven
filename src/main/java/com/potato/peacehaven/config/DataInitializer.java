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
        initBuildingContest2();
        initBattleShowdown();
        initDraftBattle();
        initBotSchedules();
    }

    /** 初始化机器人定时推送基础配置（首次启动时） */
    private void initBotSchedules() {
        if (botScheduleConfigRepository.count() > 0) return;

        botScheduleConfigRepository.save(BotScheduleConfig.builder()
                .eventType("尸潮").dayOfWeek(6).eventTime(LocalTime.of(20, 30)).build());
        botScheduleConfigRepository.save(BotScheduleConfig.builder()
                .eventType("铁手").dayOfWeek(7).eventTime(LocalTime.of(20, 30)).build());
        botScheduleConfigRepository.save(BotScheduleConfig.builder()
                .eventType("巡逻").dayOfWeek(0).eventTime(LocalTime.of(19, 30)).build());
        botScheduleConfigRepository.save(BotScheduleConfig.builder()
                .eventType("资源战").dayOfWeek(1).build());
        botScheduleConfigRepository.save(BotScheduleConfig.builder()
                .eventType("争霸赛").dayOfWeek(5).build());
        botScheduleConfigRepository.save(BotScheduleConfig.builder()
                .eventType("争霸赛").dayOfWeek(6).build());

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

    private void initBuildingContest2() {
        String slug = "building-master-2";
        Activity activity = activityRepository.findBySlug(slug).orElse(null);
        if (activity == null) {
            activity = Activity.builder()
                    .slug(slug)
                    .title("长安建筑大赛·第二届")
                    .summary("展示建筑创意，交流建造技巧，评选最具创意与观赏性的庄园作品")
                    .startDate(LocalDateTime.of(2026, 7, 25, 0, 0))
                    .endDate(LocalDateTime.of(2026, 8, 15, 23, 59))
                    .hasWorkSubmission(true)
                    .build();
            activity = activityRepository.save(activity);
            log.info("已自动创建第二届建筑大赛活动记录: slug={}", slug);
        }

        // 初始化时间配置（如果不存在）
        if (configRepository.findByActivityId(activity.getId()).isEmpty()) {
            Map<String, String> configData = new LinkedHashMap<>();
            configData.put("submitStart", "2026-07-25T00:00");
            configData.put("submitEnd", "2026-08-08T23:30");
            configData.put("judgeStart", "2026-08-09T00:00");
            configData.put("judgeEnd", "2026-08-09T23:59");
            configData.put("voteStart", "2026-08-10T00:00");
            configData.put("voteEnd", "2026-08-15T23:30");

            ActivityConfig config = ActivityConfig.builder()
                    .activityId(activity.getId())
                    .configJson(ContestService.mapToJson(configData))
                    .build();
            configRepository.save(config);
            log.info("已创建第二届建筑大赛时间配置");
        }
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

    private void initDraftBattle() {
        String slug = "draft-battle-1";
        Activity activity = activityRepository.findBySlug(slug).orElse(null);
        if (activity == null) {
            activity = Activity.builder()
                    .slug(slug)
                    .title("第一届 · 点兵演武 · 南希对抗赛")
                    .summary("双将点兵，一战定胜负。长安营地 12v12 南希市控团队竞技对抗赛")
                    .startDate(LocalDateTime.of(2026, 8, 1, 0, 0))
                    .endDate(LocalDateTime.of(2026, 8, 31, 23, 59))
                    .build();
            activity = activityRepository.save(activity);
            log.info("已自动创建南希对抗赛活动记录: slug={}", slug);
        }

        if (configRepository.findByActivityId(activity.getId()).isEmpty()) {
            // 赛程时间线
            String timelineJson = "["
                + "{\"label\":\"第一轮报名\",\"icon\":\"📋\",\"phase\":\"register1\",\"start\":\"2026-07-27T00:00\",\"end\":\"2026-07-31T23:30\"},"
                + "{\"label\":\"第一场\",\"icon\":\"⚔️\",\"phase\":\"firstRound\",\"start\":\"2026-08-01T00:00\",\"end\":\"2026-08-01T23:59\"},"
                + "{\"label\":\"第二场\",\"icon\":\"⚔️\",\"phase\":\"secondRound\",\"start\":\"2026-08-02T00:00\",\"end\":\"2026-08-02T23:59\"},"
                + "{\"label\":\"第二轮报名\",\"icon\":\"📋\",\"phase\":\"register2\",\"start\":\"2026-08-03T00:00\",\"end\":\"2026-08-07T23:30\"},"
                + "{\"label\":\"第三场\",\"icon\":\"⚔️\",\"phase\":\"thirdRound\",\"start\":\"2026-08-08T00:00\",\"end\":\"2026-08-08T23:59\"},"
                + "{\"label\":\"第四场\",\"icon\":\"⚔️\",\"phase\":\"fourthRound\",\"start\":\"2026-08-09T20:00\",\"end\":\"2026-08-09T22:00\"},"
                + "{\"label\":\"颁奖典礼\",\"icon\":\"🏆\",\"phase\":\"awards\",\"start\":\"2026-08-09T22:01\",\"end\":\"2026-08-09T23:59\"}"
                + "]";

            // 比赛日程
            String scheduleJson = "["
                + "{\"round\":\"第一场\",\"date\":\"2026-08-01\",\"time\":\"00:00\",\"teamA\":\"薯家军\",\"teamB\":\"嘟家军\",\"status\":\"WAITING\"},"
                + "{\"round\":\"第二场\",\"date\":\"2026-08-02\",\"time\":\"00:00\",\"teamA\":\"薯家军\",\"teamB\":\"嘟家军\",\"status\":\"WAITING\"},"
                + "{\"round\":\"第三场\",\"date\":\"2026-08-08\",\"time\":\"00:00\",\"teamA\":\"薯家军\",\"teamB\":\"嘟家军\",\"status\":\"WAITING\"},"
                + "{\"round\":\"第四场\",\"date\":\"2026-08-09\",\"time\":\"00:00\",\"teamA\":\"薯家军\",\"teamB\":\"嘟家军\",\"status\":\"WAITING\"}"
                + "]";

            // 战绩记录
            String matchHistoryJson = "["
                + "{\"index\":0,\"round\":\"第一场\",\"teamAName\":\"薯家军\",\"teamBName\":\"嘟家军\",\"scoreA\":0,\"scoreB\":0,\"winner\":null,\"status\":\"WAITING\",\"date\":\"2026-08-01\"},"
                + "{\"index\":1,\"round\":\"第二场\",\"teamAName\":\"薯家军\",\"teamBName\":\"嘟家军\",\"scoreA\":0,\"scoreB\":0,\"winner\":null,\"status\":\"WAITING\",\"date\":\"2026-08-02\"},"
                + "{\"index\":2,\"round\":\"第三场\",\"teamAName\":\"薯家军\",\"teamBName\":\"嘟家军\",\"scoreA\":0,\"scoreB\":0,\"winner\":null,\"status\":\"WAITING\",\"date\":\"2026-08-08\"},"
                + "{\"index\":3,\"round\":\"第四场\",\"teamAName\":\"薯家军\",\"teamBName\":\"嘟家军\",\"scoreA\":0,\"scoreB\":0,\"winner\":null,\"status\":\"WAITING\",\"date\":\"2026-08-09\"}"
                + "]";

            // 排行榜
            String rankingsJson = "{\"personal\":[],\"teams\":[]}";

            // 荣誉殿堂（预设占位，颁奖阶段按title匹配更新name）
            String honorsJson = "["
                + "{\"icon\":\"📊\",\"title\":\"人口调控办主任\",\"name\":\"待定\",\"prizeAmount\":\"8.88\",\"detail\":\"单场输出最高\"},"
                + "{\"icon\":\"💀\",\"title\":\"阎王殿优秀员工奖\",\"name\":\"待定\",\"prizeAmount\":\"8.88\",\"detail\":\"单场击杀最高\"},"
                + "{\"icon\":\"🔄\",\"title\":\"复活点尊享会员\",\"name\":\"待定\",\"prizeAmount\":\"8.88\",\"detail\":\"累计死亡最高\"},"
                + "{\"icon\":\"🔫\",\"title\":\"突突突神教教主\",\"name\":\"待定\",\"prizeAmount\":\"8.88\",\"detail\":\"步枪兵单场KD最高\"},"
                + "{\"icon\":\"🎯\",\"title\":\"八百里外包邮王\",\"name\":\"待定\",\"prizeAmount\":\"8.88\",\"detail\":\"狙击手单场KD最高\"},"
                + "{\"icon\":\"🤗\",\"title\":\"贴贴不需要同意奖\",\"name\":\"待定\",\"prizeAmount\":\"8.88\",\"detail\":\"武士单场KD最高\"},"
                + "{\"icon\":\"🏆\",\"title\":\"长安南希诸葛亮\",\"name\":\"待定\",\"prizeAmount\":\"38.88\",\"isCaptain\":true,\"detail\":\"胜方将领独享\"},"
                + "{\"icon\":\"🛡️\",\"title\":\"最佳抗压将领奖\",\"name\":\"待定\",\"prizeAmount\":\"18.88\",\"isCaptain\":true,\"detail\":\"败方将领获得\"},"
                + "{\"icon\":\"⚔️\",\"title\":\"谁也不服谁奖\",\"name\":\"待定\",\"prizeAmount\":\"28.88\",\"isCaptain\":true,\"detail\":\"2:2平局时双方将领各获\"}"
                + "]";

            // 队伍与将领配置
            String teamConfigJson = "{\"teamA\":{\"name\":\"薯家军\",\"captainUserId\":16},\"teamB\":{\"name\":\"嘟家军\",\"captainUserId\":55}}";

            Map<String, String> configData = new LinkedHashMap<>();
            configData.put("timeline", timelineJson);
            configData.put("schedule", scheduleJson);
            configData.put("matchHistory", matchHistoryJson);
            configData.put("rankings", rankingsJson);
            configData.put("honors", honorsJson);
            configData.put("teamConfig", teamConfigJson);

            ActivityConfig config = ActivityConfig.builder()
                    .activityId(activity.getId())
                    .configJson(ContestService.mapToJson(configData))
                    .build();
            configRepository.save(config);
            log.info("已创建南希对抗赛配置");
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
