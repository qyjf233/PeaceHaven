package com.potato.peacehaven.repository;

import com.potato.peacehaven.entity.ContestJudgeScore;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ContestJudgeScoreRepository extends JpaRepository<ContestJudgeScore, Long> {

    /** 查询某裁判对某作品的评分 */
    Optional<ContestJudgeScore> findByWorkIdAndJudgeId(Long workId, Long judgeId);

    /** 查询某作品的所有裁判评分 */
    List<ContestJudgeScore> findByWorkId(Long workId);

    /** 检查某裁判是否已对某作品评分 */
    boolean existsByWorkIdAndJudgeId(Long workId, Long judgeId);

    /** 查询某裁判在指定活动的所有评分记录 */
    List<ContestJudgeScore> findByJudgeIdAndWorkActivityId(Long judgeId, Long activityId);

    /** 查询某作品的裁判平均分 */
    @Query("SELECT AVG(s.score) FROM ContestJudgeScore s WHERE s.work.id = :workId")
    Double avgScoreByWorkId(@Param("workId") Long workId);
}
