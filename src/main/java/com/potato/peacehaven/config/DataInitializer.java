package com.potato.peacehaven.config;

import com.potato.peacehaven.entity.Activity;
import com.potato.peacehaven.repository.ActivityRepository;
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

    @Override
    public void run(ApplicationArguments args) {
        initBuildingContest();
    }

    private void initBuildingContest() {
        String slug = "building-master-1";
        if (activityRepository.findBySlug(slug).isPresent()) {
            return;
        }

        Activity activity = Activity.builder()
                .slug(slug)
                .title("长安建筑大赛")
                .summary("展示建筑创意，交流建造技巧，评选最具创意与观赏性的庄园作品")
                .startDate(LocalDateTime.of(2026, 7, 1, 0, 0))
                .endDate(LocalDateTime.of(2026, 7, 31, 23, 59))
                .build();

        activityRepository.save(activity);
        log.info("已自动创建建筑大赛活动记录: slug={}", slug);
    }
}
