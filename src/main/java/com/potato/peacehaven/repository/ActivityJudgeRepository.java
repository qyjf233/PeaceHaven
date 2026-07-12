package com.potato.peacehaven.repository;

import com.potato.peacehaven.entity.ActivityJudge;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ActivityJudgeRepository extends JpaRepository<ActivityJudge, Long> {

    /** 按排序权重查询某活动的裁判列表 */
    List<ActivityJudge> findByActivityIdOrderBySortOrderAsc(Long activityId);

    /** 检查某用户是否为某活动的裁判 */
    boolean existsByActivityIdAndUserId(Long activityId, Long userId);
}
