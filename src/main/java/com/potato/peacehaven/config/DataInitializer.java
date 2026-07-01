package com.potato.peacehaven.config;

import com.potato.peacehaven.entity.Activity;
import com.potato.peacehaven.entity.BuildingContestConfig;
import com.potato.peacehaven.entity.BuildingContestJudge;
import com.potato.peacehaven.entity.User;
import com.potato.peacehaven.repository.ActivityRepository;
import com.potato.peacehaven.repository.BuildingContestConfigRepository;
import com.potato.peacehaven.repository.BuildingContestJudgeRepository;
import com.potato.peacehaven.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements ApplicationRunner {

    private final ActivityRepository activityRepository;
    private final BuildingContestConfigRepository configRepository;
    private final BuildingContestJudgeRepository judgeRepository;
    private final UserRepository userRepository;

    @Override
    public void run(ApplicationArguments args) {
        initBuildingContest();
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
                    .build();
            activity = activityRepository.save(activity);
            log.info("已自动创建建筑大赛活动记录: slug={}", slug);
        }

        // 初始化时间配置（如果不存在）
        if (configRepository.findByActivityId(activity.getId()).isEmpty()) {
            BuildingContestConfig config = BuildingContestConfig.builder()
                    .activityId(activity.getId())
                    .submitStart(LocalDateTime.of(2026, 7, 5, 0, 0))
                    .submitEnd(LocalDateTime.of(2026, 7, 14, 23, 30))
                    .judgeStart(LocalDateTime.of(2026, 7, 15, 0, 0))
                    .judgeEnd(LocalDateTime.of(2026, 7, 15, 23, 59))
                    .voteStart(LocalDateTime.of(2026, 7, 16, 0, 0))
                    .voteEnd(LocalDateTime.of(2026, 7, 20, 23, 30))
                    .build();
            configRepository.save(config);
            log.info("已创建建筑大赛时间配置");
        }

        // 初始化裁判（按手机号指定）
        initJudges(activity.getId());
    }

    /**
     * 初始化裁判：通过手机号查找用户并赋予裁判身份
     * 如需添加裁判，在此处配置手机号即可
     */
    private void initJudges(Long activityId) {
        // 配置裁判手机号列表（在此添加）
        String[] judgePhones = {"13586619697"};

        log.info("开始初始化裁判，活动ID: {}，当前已有裁判数: {}", activityId,
                judgeRepository.findByActivityId(activityId).size());

        for (String phone : judgePhones) {
            User user = userRepository.findByPhone(phone).orElse(null);
            if (user == null) {
                log.warn("裁判初始化：手机号 {} 未找到对应用户，请先注册该手机号", phone);
                continue;
            }
            if (!judgeRepository.existsByActivityIdAndUserId(activityId, user.getId())) {
                BuildingContestJudge judge = BuildingContestJudge.builder()
                        .activityId(activityId)
                        .user(user)
                        .build();
                judgeRepository.save(judge);
                log.info("已添加裁判: {} (ID:{}, 手机:{})", user.getNickname(), user.getId(), phone);
            } else {
                log.info("裁判已存在: {} (ID:{}, 手机:{})", user.getNickname(), user.getId(), phone);
            }
        }
    }
}
