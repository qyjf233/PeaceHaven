package com.potato.peacehaven.repository;

import com.potato.peacehaven.entity.BuildingContestJudgeScore;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BuildingContestJudgeScoreRepository extends JpaRepository<BuildingContestJudgeScore, Long> {

    /** 查询某裁判对某作品的评分 */
    Optional<BuildingContestJudgeScore> findByWorkIdAndJudgeId(Long workId, Long judgeId);

    /** 查询某作品的所有裁判评分 */
    List<BuildingContestJudgeScore> findByWorkId(Long workId);

    /** 检查某裁判是否已对某作品评分 */
    boolean existsByWorkIdAndJudgeId(Long workId, Long judgeId);

    /** 查询某裁判在指定活动的所有评分记录 */
    List<BuildingContestJudgeScore> findByJudgeIdAndWorkActivityId(Long judgeId, Long activityId);
}
