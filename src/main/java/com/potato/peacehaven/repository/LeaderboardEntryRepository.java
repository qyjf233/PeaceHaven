package com.potato.peacehaven.repository;

import com.potato.peacehaven.entity.LeaderboardEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LeaderboardEntryRepository extends JpaRepository<LeaderboardEntry, Long> {

    List<LeaderboardEntry> findByActivityIdOrderByRankPositionAsc(Long activityId);
}
