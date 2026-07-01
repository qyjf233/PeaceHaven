package com.potato.peacehaven.config;

import com.potato.peacehaven.entity.Activity;
import com.potato.peacehaven.entity.BuildingContestConfig;
import com.potato.peacehaven.repository.ActivityRepository;
import com.potato.peacehaven.repository.BuildingContestConfigRepository;
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
    }
}
