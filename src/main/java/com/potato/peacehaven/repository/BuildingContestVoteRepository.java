package com.potato.peacehaven.repository;

import com.potato.peacehaven.entity.BuildingContestVote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface BuildingContestVoteRepository extends JpaRepository<BuildingContestVote, Long> {

    /** 检查用户是否已对某作品投票 */
    boolean existsByWorkIdAndUserId(Long workId, Long userId);

    /** 统计用户在指定活动的总投票数 */
    long countByUserIdAndWorkActivityId(Long userId, Long activityId);

    /** 查询用户对某作品的投票记录（用于撤回） */
    Optional<BuildingContestVote> findByWorkIdAndUserId(Long workId, Long userId);

    /** 删除某作品的所有投票记录 */
    void deleteByWorkId(Long workId);
}
