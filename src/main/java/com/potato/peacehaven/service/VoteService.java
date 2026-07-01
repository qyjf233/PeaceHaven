package com.potato.peacehaven.service;

import com.potato.peacehaven.entity.Activity;
import com.potato.peacehaven.entity.VoteOption;
import com.potato.peacehaven.entity.VoteRecord;
import com.potato.peacehaven.enums.ActivityStatus;
import com.potato.peacehaven.repository.ActivityRepository;
import com.potato.peacehaven.repository.VoteOptionRepository;
import com.potato.peacehaven.repository.VoteRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class VoteService {

    private final ActivityRepository activityRepository;
    private final VoteOptionRepository voteOptionRepository;
    private final VoteRecordRepository voteRecordRepository;

    /**
     * Submit a vote for an activity option.
     * Each voter can only vote once per activity.
     */
    @Transactional
    public String submitVote(Long activityId, Long optionId, String voterName) {
        // Check activity exists and is ongoing
        Activity activity = activityRepository.findById(activityId)
                .orElseThrow(() -> new RuntimeException("活动不存在"));

        if (activity.getStatus() == ActivityStatus.ENDED) {
            return "该活动已结束，无法投票";
        }

        if (activity.getStatus() == ActivityStatus.UPCOMING) {
            return "该活动尚未开始";
        }

        // Check duplicate vote
        if (voteRecordRepository.existsByActivityIdAndVoterName(activityId, voterName)) {
            return "你已经投过票了，每人每活动限投一票";
        }

        // Get option and increment vote count
        VoteOption option = voteOptionRepository.findById(optionId)
                .orElseThrow(() -> new RuntimeException("投票选项不存在"));

        // Verify option belongs to this activity
        if (!option.getActivity().getId().equals(activityId)) {
            return "无效的投票选项";
        }

        // Increment vote count
        option.setVoteCount(option.getVoteCount() + 1);
        voteOptionRepository.save(option);

        // Record the vote
        VoteRecord record = VoteRecord.builder()
                .activity(activity)
                .option(option)
                .voterName(voterName)
                .build();
        voteRecordRepository.save(record);

        return "投票成功！";
    }
}
