package com.potato.peacehaven.repository;

import com.potato.peacehaven.entity.PvpRegistration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PvpRegistrationRepository extends JpaRepository<PvpRegistration, Long> {

    /** 查询某活动的所有报名 */
    List<PvpRegistration> findByActivityIdOrderByCreatedAtAsc(Long activityId);

    /** 查询某活动某轮次报名人数 */
    long countByActivityIdAndRoundId(Long activityId, Integer roundId);

    /** 查询某活动报名人数（全部轮次） */
    long countByActivityId(Long activityId);

    /** 查询某用户在某活动某轮次的报名记录 */
    Optional<PvpRegistration> findByActivityIdAndUserIdAndRoundId(Long activityId, Long userId, Integer roundId);

    /** 查询某用户在某活动的报名记录（不限轮次） */
    Optional<PvpRegistration> findByActivityIdAndUserId(Long activityId, Long userId);

    /** 检查某用户是否已报名某活动某轮次 */
    boolean existsByActivityIdAndUserIdAndRoundId(Long activityId, Long userId, Integer roundId);

    /** 检查某用户是否已报名某活动（不限轮次） */
    boolean existsByActivityIdAndUserId(Long activityId, Long userId);

    /** 按积分降序查询某活动的报名（排行榜） */
    List<PvpRegistration> findByActivityIdOrderByPointsDescWinsDesc(Long activityId);

    /** 按积分降序查询某活动某轮次的报名 */
    List<PvpRegistration> findByActivityIdAndRoundIdOrderByPointsDescWinsDesc(Long activityId, Integer roundId);

    /** 按积分降序查询某活动某轮次的报名（急加载User，避免懒加载） */
    @Query("SELECT r FROM PvpRegistration r JOIN FETCH r.user WHERE r.activityId = :activityId AND r.roundId = :roundId ORDER BY r.points DESC, r.wins DESC")
    List<PvpRegistration> findByActivityIdAndRoundIdWithUser(@Param("activityId") Long activityId, @Param("roundId") Integer roundId);
}
