package com.potato.peacehaven.repository;

import com.potato.peacehaven.entity.ActivityConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ActivityConfigRepository extends JpaRepository<ActivityConfig, Long> {

    /**
     * 根据活动ID查询配置
     */
    Optional<ActivityConfig> findByActivityId(Long activityId);
}
