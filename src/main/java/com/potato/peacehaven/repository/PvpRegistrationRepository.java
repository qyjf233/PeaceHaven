package com.potato.peacehaven.repository;

import com.potato.peacehaven.entity.PvpRegistration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PvpRegistrationRepository extends JpaRepository<PvpRegistration, Long> {

    /** 查询某活动的所有报名 */
    List<PvpRegistration> findByActivityIdOrderByCreatedAtAsc(Long activityId);

    /** 查询某活动报名人数 */
    long countByActivityId(Long activityId);

    /** 查询某用户在某活动的报名记录 */
    Optional<PvpRegistration> findByActivityIdAndUserId(Long activityId, Long userId);

    /** 检查某用户是否已报名某活动 */
    boolean existsByActivityIdAndUserId(Long activityId, Long userId);

    /** 按积分降序查询某活动的报名（排行榜） */
    List<PvpRegistration> findByActivityIdOrderByPointsDescWinsDesc(Long activityId);
}
