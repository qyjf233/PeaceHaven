package com.potato.peacehaven.repository;

import com.potato.peacehaven.entity.SiteStats;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SiteStatsRepository extends JpaRepository<SiteStats, Long> {

    /**
     * 获取首页统计数据（固定 id=1）
     */
    default Optional<SiteStats> findSiteStats() {
        return findById(1L);
    }
}
