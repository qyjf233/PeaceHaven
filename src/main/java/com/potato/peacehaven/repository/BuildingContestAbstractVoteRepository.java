package com.potato.peacehaven.repository;

import com.potato.peacehaven.entity.BuildingContestAbstractVote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface BuildingContestAbstractVoteRepository extends JpaRepository<BuildingContestAbstractVote, Long> {

    /** 查询用户在指定活动的抽象票记录 */
    Optional<BuildingContestAbstractVote> findByActivityIdAndUserId(Long activityId, Long userId);

    /** 检查用户在指定活动是否已投抽象票 */
    boolean existsByActivityIdAndUserId(Long activityId, Long userId);

    /** 删除用户在指定活动的抽象票（撤回） */
    void deleteByActivityIdAndUserId(Long activityId, Long userId);
}
