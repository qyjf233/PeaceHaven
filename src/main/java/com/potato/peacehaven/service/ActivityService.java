package com.potato.peacehaven.service;

import com.potato.peacehaven.entity.Activity;
import com.potato.peacehaven.entity.LeaderboardEntry;
import com.potato.peacehaven.entity.VoteOption;
import com.potato.peacehaven.enums.ActivityStatus;
import com.potato.peacehaven.repository.ActivityRepository;
import com.potato.peacehaven.repository.LeaderboardEntryRepository;
import com.potato.peacehaven.repository.VoteOptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ActivityService {

    private final ActivityRepository activityRepository;
    private final VoteOptionRepository voteOptionRepository;
    private final LeaderboardEntryRepository leaderboardEntryRepository;

    public Page<Activity> getActivities(ActivityStatus status, int page, int size) {
        PageRequest pageRequest = PageRequest.of(page, size);
        if (status != null) {
            return activityRepository.findByStatusOrderByStartDateDesc(status, pageRequest);
        }
        return activityRepository.findAllByOrderByStartDateDesc(pageRequest);
    }

    public Activity getActivityById(Long id) {
        return activityRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("活动不存在: " + id));
    }

    public List<Activity> getRecentActivities() {
        return activityRepository.findTop4ByStatusInOrderByStartDateDesc(
                List.of(ActivityStatus.ONGOING, ActivityStatus.UPCOMING));
    }

    @Transactional
    public Activity createActivity(Activity activity) {
        if (activity.getStatus() == null) {
            activity.setStatus(ActivityStatus.UPCOMING);
        }
        return activityRepository.save(activity);
    }

    @Transactional
    public Activity updateActivity(Long id, Activity updated) {
        Activity activity = getActivityById(id);
        activity.setTitle(updated.getTitle());
        activity.setSummary(updated.getSummary());
        activity.setContent(updated.getContent());
        activity.setThumbnail(updated.getThumbnail());
        activity.setTemplateType(updated.getTemplateType());
        activity.setStartDate(updated.getStartDate());
        activity.setEndDate(updated.getEndDate());
        activity.setConfigJson(updated.getConfigJson());
        return activityRepository.save(activity);
    }

    @Transactional
    public void endActivity(Long id) {
        Activity activity = getActivityById(id);
        activity.setStatus(ActivityStatus.ENDED);
        activityRepository.save(activity);
    }

    @Transactional
    public void deleteActivity(Long id) {
        activityRepository.deleteById(id);
    }

    // === Vote Options Management ===

    public List<VoteOption> getVoteOptions(Long activityId) {
        return voteOptionRepository.findByActivityIdOrderBySortOrderAsc(activityId);
    }

    @Transactional
    public VoteOption addVoteOption(Long activityId, VoteOption option) {
        Activity activity = getActivityById(activityId);
        option.setActivity(activity);
        return voteOptionRepository.save(option);
    }

    @Transactional
    public void deleteVoteOption(Long optionId) {
        voteOptionRepository.deleteById(optionId);
    }

    // === Leaderboard Management ===

    public List<LeaderboardEntry> getLeaderboard(Long activityId) {
        return leaderboardEntryRepository.findByActivityIdOrderByRankPositionAsc(activityId);
    }

    @Transactional
    public LeaderboardEntry addLeaderboardEntry(Long activityId, LeaderboardEntry entry) {
        Activity activity = getActivityById(activityId);
        entry.setActivity(activity);
        return leaderboardEntryRepository.save(entry);
    }

    @Transactional
    public void deleteLeaderboardEntry(Long entryId) {
        leaderboardEntryRepository.deleteById(entryId);
    }
}
