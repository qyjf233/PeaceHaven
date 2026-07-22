package com.potato.peacehaven.repository;

import com.potato.peacehaven.entity.SwissMatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SwissMatchRepository extends JpaRepository<SwissMatch, Long> {

    /** 查询某轮所有比赛（按 matchOrder 排序） */
    List<SwissMatch> findByActivityIdAndRoundNumberOrderByMatchOrderAsc(Long activityId, Integer roundNumber);

    /** 按状态查询某活动的所有比赛 */
    List<SwissMatch> findByActivityIdAndStatusOrderByRoundNumberAscMatchOrderAsc(Long activityId, String status);

    /** 裁判查询自己负责的某状态比赛 */
    List<SwissMatch> findByActivityIdAndRefereeIdAndStatusOrderByRoundNumberAscMatchOrderAsc(
            Long activityId, Long refereeId, String status);

    /** 裁判查询自己负责的所有比赛 */
    List<SwissMatch> findByActivityIdAndRefereeIdOrderByRoundNumberAscMatchOrderAsc(
            Long activityId, Long refereeId);

    /** 统计某轮某状态的比赛数量 */
    long countByActivityIdAndRoundNumberAndStatus(Long activityId, Integer roundNumber, String status);

    /** 查询全部赛程（所有轮次，按轮次+序号排序） */
    List<SwissMatch> findByActivityIdOrderByRoundNumberAscMatchOrderAsc(Long activityId);

    /** 查询最大轮次号 */
    @Query("SELECT MAX(m.roundNumber) FROM SwissMatch m WHERE m.activityId = :activityId")
    Integer findMaxRoundByActivityId(@Param("activityId") Long activityId);

    /** 查询某轮的 WAITING 状态比赛（用于调度推进） */
    List<SwissMatch> findByActivityIdAndRoundNumberAndStatusOrderByMatchOrderAsc(
            Long activityId, Integer roundNumber, String status);

    /** 统计某活动当前正在进行的比赛数 */
    long countByActivityIdAndStatus(Long activityId, String status);
}
