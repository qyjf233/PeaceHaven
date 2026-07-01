package com.potato.peacehaven.repository;

import com.potato.peacehaven.entity.BuildingContestConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface BuildingContestConfigRepository extends JpaRepository<BuildingContestConfig, Long> {

    /** 根据活动ID查询配置 */
    Optional<BuildingContestConfig> findByActivityId(Long activityId);
}
