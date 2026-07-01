package com.potato.peacehaven.repository;

import com.potato.peacehaven.entity.BuildingContestJudge;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BuildingContestJudgeRepository extends JpaRepository<BuildingContestJudge, Long> {

    /** 查询指定活动在指定用户的裁判记录 */
    Optional<BuildingContestJudge> findByActivityIdAndUserId(Long activityId, Long userId);

    /** 检查指定用户是否为指定活动的裁判 */
    boolean existsByActivityIdAndUserId(Long activityId, Long userId);

    /** 查询指定活动的所有裁判 */
    List<BuildingContestJudge> findByActivityId(Long activityId);
}
