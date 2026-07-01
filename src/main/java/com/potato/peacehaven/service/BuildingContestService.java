package com.potato.peacehaven.service;

import com.potato.peacehaven.entity.BuildingContestVote;
import com.potato.peacehaven.entity.BuildingContestWork;
import com.potato.peacehaven.entity.User;
import com.potato.peacehaven.repository.BuildingContestVoteRepository;
import com.potato.peacehaven.repository.BuildingContestWorkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class BuildingContestService {

    private final BuildingContestWorkRepository workRepository;
    private final BuildingContestVoteRepository voteRepository;

    /**
     * 投稿作品
     */
    @Transactional
    public BuildingContestWork submitWork(Long activityId, User user, String title, String description, String imageUrl) {
        // 检查是否已投稿
        if (workRepository.findByActivityIdAndUserId(activityId, user.getId()).isPresent()) {
            throw new RuntimeException("你已经投稿过了，每位玩家仅限一次投稿");
        }

        BuildingContestWork work = BuildingContestWork.builder()
                .activityId(activityId)
                .user(user)
                .title(title)
                .description(description)
                .imageUrl(imageUrl)
                .build();

        return workRepository.save(work);
    }

    /**
     * 为作品投票
     */
    @Transactional
    public void voteForWork(Long workId, User user) {
        BuildingContestWork work = workRepository.findById(workId)
                .orElseThrow(() -> new RuntimeException("作品不存在"));

        if (work.getStatus() != BuildingContestWork.WorkStatus.APPROVED) {
            throw new RuntimeException("该作品尚未通过审核");
        }

        if (voteRepository.existsByWorkIdAndUserId(workId, user.getId())) {
            throw new RuntimeException("你已经投过票了");
        }

        // 记录投票
        BuildingContestVote vote = BuildingContestVote.builder()
                .work(work)
                .user(user)
                .build();
        voteRepository.save(vote);

        // 更新票数
        work.setVoteCount(work.getVoteCount() + 1);
        workRepository.save(work);

        log.info("用户 {} 为作品 {} 投票", user.getNickname(), work.getTitle());
    }

    /**
     * 查询已通过审核的作品列表（按票数降序）
     */
    public List<BuildingContestWork> getApprovedWorks(Long activityId) {
        return workRepository.findByActivityIdAndStatusOrderByVoteCountDesc(
                activityId, BuildingContestWork.WorkStatus.APPROVED);
    }

    /**
     * 检查用户是否已对某作品投票
     */
    public boolean hasVoted(Long workId, Long userId) {
        return voteRepository.existsByWorkIdAndUserId(workId, userId);
    }

    /**
     * 查询用户在指定活动的投稿
     */
    public BuildingContestWork getUserWork(Long activityId, Long userId) {
        return workRepository.findByActivityIdAndUserId(activityId, userId).orElse(null);
    }

    /**
     * 根据票数排名计算网络投票得分
     * 第1名30分，第2名28分，第3名26分，第4名24分，第5名22分，
     * 第6~10名20分，第11~20名18分，第21~30名16分，其余有效作品15分
     *
     * @param rank 票数排名（从1开始）
     * @return 网络投票得分
     */
    public int calculateVoteScore(int rank) {
        if (rank <= 0) {
            return 0;
        } else if (rank <= 5) {
            return 32 - rank * 2;  // 30, 28, 26, 24, 22
        } else if (rank <= 10) {
            return 20;
        } else if (rank <= 20) {
            return 18;
        } else if (rank <= 30) {
            return 16;
        } else {
            return 15;
        }
    }
}
