package com.potato.peacehaven.service;

import com.potato.peacehaven.entity.Activity;
import com.potato.peacehaven.repository.ActivityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ActivityService {

    private final ActivityRepository activityRepository;

    /**
     * 根据当前时间推导活动状态
     */
    public String getStatus(Activity activity) {
        LocalDateTime now = LocalDateTime.now();
        if (activity.getStartDate() != null && now.isBefore(activity.getStartDate())) {
            return "UPCOMING";
        }
        if (activity.getEndDate() != null && now.isAfter(activity.getEndDate())) {
            return "ENDED";
        }
        return "ONGOING";
    }

    /**
     * 分页查询活动，支持状态筛选（通过时间推导）
     */
    public Page<Activity> getActivities(String status, int page, int size) {
        PageRequest pageRequest = PageRequest.of(page, size);
        LocalDateTime now = LocalDateTime.now();

        if ("ONGOING".equalsIgnoreCase(status)) {
            return activityRepository.findByStartDateBeforeAndEndDateAfter(now, now, pageRequest);
        }
        return activityRepository.findAllByOrderByStartDateDesc(pageRequest);
    }

    public Activity getActivityById(Long id) {
        return activityRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("活动不存在: " + id));
    }

    public Activity getActivityBySlug(String slug) {
        return activityRepository.findBySlug(slug)
                .orElseThrow(() -> new RuntimeException("活动不存在: " + slug));
    }

    public List<Activity> getRecentActivities() {
        return activityRepository.findTop4ByEndDateAfterOrderByStartDateAsc(LocalDateTime.now());
    }

    @Transactional
    public Activity createActivity(Activity activity) {
        return activityRepository.save(activity);
    }

    @Transactional
    public Activity updateActivity(Long id, Activity updated) {
        Activity activity = getActivityById(id);
        activity.setSlug(updated.getSlug());
        activity.setTitle(updated.getTitle());
        activity.setSummary(updated.getSummary());
        activity.setThumbnail(updated.getThumbnail());
        activity.setStartDate(updated.getStartDate());
        activity.setEndDate(updated.getEndDate());
        return activityRepository.save(activity);
    }

    @Transactional
    public void deleteActivity(Long id) {
        activityRepository.deleteById(id);
    }
}
