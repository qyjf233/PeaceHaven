package com.potato.peacehaven.service;

import com.potato.peacehaven.entity.BuildingContestConfig;
import com.potato.peacehaven.entity.BuildingContestConfig.ContestPhase;
import com.potato.peacehaven.entity.BuildingContestJudge;
import com.potato.peacehaven.entity.BuildingContestJudgeScore;
import com.potato.peacehaven.entity.BuildingContestVote;
import com.potato.peacehaven.entity.BuildingContestWork;
import com.potato.peacehaven.entity.User;
import com.potato.peacehaven.repository.BuildingContestConfigRepository;
import com.potato.peacehaven.repository.BuildingContestJudgeRepository;
import com.potato.peacehaven.repository.BuildingContestJudgeScoreRepository;
import com.potato.peacehaven.repository.BuildingContestVoteRepository;
import com.potato.peacehaven.repository.BuildingContestWorkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class BuildingContestService {

    private final BuildingContestWorkRepository workRepository;
    private final BuildingContestVoteRepository voteRepository;
    private final BuildingContestConfigRepository configRepository;
    private final BuildingContestJudgeRepository judgeRepository;
    private final BuildingContestJudgeScoreRepository judgeScoreRepository;

    /** 每人最多投票数 */
    public static final int MAX_VOTES_PER_USER = 3;

    /**
     * 投稿作品（受阶段控制）
     */
    @Transactional
    public BuildingContestWork submitWork(Long activityId, User user, String title, String description, String imageUrl) {
        // 裁判不可投稿
        if (isJudge(activityId, user.getId())) {
            throw new RuntimeException("裁判不可投稿");
        }

        // 阶段检查
        ContestPhase phase = getCurrentPhase(activityId);
        if (phase != ContestPhase.SUBMISSION) {
            throw new RuntimeException(getPhaseRestrictionMessage(phase, "投稿"));
        }

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
     * 为作品投票（受阶段控制）
     */
    @Transactional
    public void voteForWork(Long workId, User user) {
        BuildingContestWork work = workRepository.findById(workId)
                .orElseThrow(() -> new RuntimeException("作品不存在"));

        // 裁判不可投票
        if (isJudge(work.getActivityId(), user.getId())) {
            throw new RuntimeException("裁判不可投票");
        }

        // 阶段检查
        ContestPhase phase = getCurrentPhase(work.getActivityId());
        if (phase != ContestPhase.VOTING) {
            throw new RuntimeException(getPhaseRestrictionMessage(phase, "投票"));
        }

        if (work.getStatus() != BuildingContestWork.WorkStatus.APPROVED) {
            throw new RuntimeException("该作品尚未通过审核");
        }

        if (voteRepository.existsByWorkIdAndUserId(workId, user.getId())) {
            throw new RuntimeException("你已经投过票了");
        }

        // 检查总投票数限制
        long totalVotes = getUserVoteCount(work.getActivityId(), user.getId());
        if (totalVotes >= MAX_VOTES_PER_USER) {
            throw new RuntimeException("每人最多只能投" + MAX_VOTES_PER_USER + "票，你的票数已用完");
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

        log.info("用户 {} 为作品 {} 投票，剩余票数: {}",
                user.getNickname(), work.getTitle(), MAX_VOTES_PER_USER - totalVotes - 1);
    }

    /**
     * 撤回投票
     */
    @Transactional
    public void retractVote(Long workId, User user) {
        BuildingContestVote vote = voteRepository.findByWorkIdAndUserId(workId, user.getId())
                .orElseThrow(() -> new RuntimeException("你还没有对这个作品投票"));

        BuildingContestWork work = vote.getWork();

        // 删除投票记录
        voteRepository.delete(vote);

        // 更新票数
        work.setVoteCount(Math.max(0, work.getVoteCount() - 1));
        workRepository.save(work);

        log.info("用户 {} 撤回了对作品 {} 的投票", user.getNickname(), work.getTitle());
    }

    /**
     * 查询已通过审核的作品列表（按票数降序）
     */
    public List<BuildingContestWork> getApprovedWorks(Long activityId) {
        return workRepository.findByActivityIdAndStatusOrderByVoteCountDesc(
                activityId, BuildingContestWork.WorkStatus.APPROVED);
    }

    /**
     * 查询用户在指定活动的已用票数
     */
    public long getUserVoteCount(Long activityId, Long userId) {
        return voteRepository.countByUserIdAndWorkActivityId(userId, activityId);
    }

    /**
     * 查询用户在指定活动的剩余票数
     */
    public int getRemainingVotes(Long activityId, Long userId) {
        long used = getUserVoteCount(activityId, userId);
        return Math.max(0, MAX_VOTES_PER_USER - (int) used);
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
     * 删除用户自己的作品（及关联投票记录）
     * 评委打分开始后不允许删除
     * @return true 如果作品之前是已通过状态（有投票记录被删除）
     */
    @Transactional
    public boolean deleteOwnWork(Long activityId, Long userId) {
        // 阶段检查：评委打分开始后不允许删除
        ContestPhase phase = getCurrentPhase(activityId);
        if (phase == ContestPhase.JUDGING || phase == ContestPhase.PRE_VOTE
                || phase == ContestPhase.VOTING || phase == ContestPhase.RESULTS) {
            throw new RuntimeException("评委打分已开始，无法删除作品");
        }

        BuildingContestWork work = workRepository.findByActivityIdAndUserId(activityId, userId)
                .orElseThrow(() -> new RuntimeException("你还没有投稿作品"));

        boolean wasApproved = (work.getStatus() == BuildingContestWork.WorkStatus.APPROVED);

        // 始终先删除关联的投票记录，避免外键约束冲突
        voteRepository.deleteByWorkId(work.getId());

        // 再删除作品
        workRepository.delete(work);
        log.info("用户 {} 删除了作品 {}", userId, work.getTitle());

        return wasApproved;
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

    // ==================== 阶段控制 ====================

    /**
     * 获取当前大赛阶段
     */
    public ContestPhase getCurrentPhase(Long activityId) {
        return configRepository.findByActivityId(activityId)
                .map(BuildingContestConfig::getCurrentPhase)
                .orElse(ContestPhase.BEFORE_START);
    }

    /**
     * 获取大赛时间配置
     */
    public BuildingContestConfig getConfig(Long activityId) {
        return configRepository.findByActivityId(activityId).orElse(null);
    }

    /**
     * 是否应该显示评委分数（仅 RESULTS 阶段）
     */
    public boolean shouldShowJudgeScore(Long activityId) {
        return getCurrentPhase(activityId) == ContestPhase.RESULTS;
    }

    /**
     * 是否应该显示投票数（VOTING 和 RESULTS 阶段）
     */
    public boolean shouldShowVoteCount(Long activityId) {
        ContestPhase phase = getCurrentPhase(activityId);
        return phase == ContestPhase.VOTING || phase == ContestPhase.RESULTS;
    }

    /**
     * 获取阶段限制提示消息
     */
    private String getPhaseRestrictionMessage(ContestPhase phase, String action) {
        return switch (phase) {
            case BEFORE_START -> "活动尚未开始，暂不可" + action;
            case SUBMISSION -> "投稿".equals(action) ? "投稿已截止" : "投票尚未开始";
            case REVIEW -> "投稿已截止，评委审核中";
            case JUDGING -> "评委打分中，暂不可" + action;
            case PRE_VOTE -> "评委打分已结束，投票尚未开始";
            case VOTING -> "投票".equals(action) ? "投票进行中" : "投稿已截止";
            case RESULTS -> "活动已结束";
        };
    }

    // ==================== 管理员审核功能 ====================

    /**
     * 分页查询作品（供管理员审核）
     * @param activityId 活动ID
     * @param status 状态筛选，null表示全部
     * @param page 页码（从0开始）
     * @param size 每页数量
     */
    public Page<BuildingContestWork> getWorksForReview(Long activityId, String status, int page, int size) {
        PageRequest pageRequest = PageRequest.of(page, size);
        if (status != null && !status.isEmpty() && !"ALL".equalsIgnoreCase(status)) {
            BuildingContestWork.WorkStatus workStatus = BuildingContestWork.WorkStatus.valueOf(status.toUpperCase());
            return workRepository.findByActivityIdAndStatusOrderByCreatedAtDesc(activityId, workStatus, pageRequest);
        }
        return workRepository.findByActivityIdOrderByCreatedAtDesc(activityId, pageRequest);
    }

    /**
     * 审核作品（通过/拒绝）
     */
    @Transactional
    public void reviewWork(Long workId, BuildingContestWork.WorkStatus newStatus) {
        BuildingContestWork work = workRepository.findById(workId)
                .orElseThrow(() -> new RuntimeException("作品不存在"));
        work.setStatus(newStatus);
        workRepository.save(work);
        log.info("作品 {} 审核结果: {}", workId, newStatus);
    }

    // ==================== 裁判评分功能 ====================

    /**
     * 判断用户是否为指定活动的裁判
     */
    public boolean isJudge(Long activityId, Long userId) {
        return judgeRepository.existsByActivityIdAndUserId(activityId, userId);
    }

    /**
     * 裁判提交评分（受阶段控制，打分后不可修改）
     */
    @Transactional
    public void submitJudgeScore(Long workId, User judge, double score) {
        BuildingContestWork work = workRepository.findById(workId)
                .orElseThrow(() -> new RuntimeException("作品不存在"));

        // 裁判身份检查
        if (!isJudge(work.getActivityId(), judge.getId())) {
            throw new RuntimeException("您不是本次活动的裁判");
        }

        // 阶段检查
        ContestPhase phase = getCurrentPhase(work.getActivityId());
        if (phase != ContestPhase.JUDGING) {
            throw new RuntimeException("当前不是评委打分阶段");
        }

        // 是否已评分
        if (judgeScoreRepository.existsByWorkIdAndJudgeId(workId, judge.getId())) {
            throw new RuntimeException("您已对该作品评分，不可修改");
        }

        // 分数范围校验
        if (score < 0 || score > 10) {
            throw new RuntimeException("分数必须在 0 ~ 10 之间");
        }

        // 精度控制：最多小数后1位
        double roundedScore = Math.round(score * 10.0) / 10.0;

        BuildingContestJudgeScore judgeScore = BuildingContestJudgeScore.builder()
                .work(work)
                .judge(judge)
                .score(roundedScore)
                .build();
        judgeScoreRepository.save(judgeScore);

        // 重新计算作品裁判平均分
        recalculateJudgeScore(workId);

        log.info("裁判 {} 为作品 {} 评分: {}", judge.getNickname(), work.getTitle(), roundedScore);
    }

    /**
     * 获取某裁判对某作品的评分
     * @return 评分值，未评分返回 null
     */
    public Double getJudgeScoreForWork(Long workId, Long judgeId) {
        return judgeScoreRepository.findByWorkIdAndJudgeId(workId, judgeId)
                .map(BuildingContestJudgeScore::getScore)
                .orElse(null);
    }

    /**
     * 获取裁判评分进度（已评数 / 总作品数）
     */
    public int[] getJudgeProgress(Long activityId, Long judgeId) {
        List<BuildingContestWork> approvedWorks = getApprovedWorks(activityId);
        int total = approvedWorks.size();
        List<BuildingContestJudgeScore> scored = judgeScoreRepository.findByJudgeIdAndWorkActivityId(judgeId, activityId);
        int scoredCount = scored.size();
        return new int[]{scoredCount, total};
    }

    /**
     * 重新计算作品的裁判平均分
     * 更新 BuildingContestWork.judgeScore 字段
     */
    @Transactional
    public void recalculateJudgeScore(Long workId) {
        List<BuildingContestJudgeScore> scores = judgeScoreRepository.findByWorkId(workId);
        BuildingContestWork work = workRepository.findById(workId).orElse(null);
        if (work == null) return;

        if (scores.isEmpty()) {
            work.setJudgeScore(null);
        } else {
            double avg = scores.stream()
                    .mapToDouble(BuildingContestJudgeScore::getScore)
                    .average()
                    .orElse(0.0);
            // 保留1位小数
            work.setJudgeScore(Math.round(avg * 10.0) / 10.0);
        }
        workRepository.save(work);
    }
}
