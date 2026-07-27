package com.potato.peacehaven.repository;

import com.potato.peacehaven.entity.DraftBattleRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DraftBattleRecordRepository extends JpaRepository<DraftBattleRecord, Long> {

    List<DraftBattleRecord> findByActivityId(Long activityId);

    List<DraftBattleRecord> findByActivityIdAndJob(Long activityId, String job);

    List<DraftBattleRecord> findByActivityIdAndTeam(Long activityId, String team);

    List<DraftBattleRecord> findByActivityIdAndGameId(Long activityId, Integer gameId);

    boolean existsByActivityIdAndUserIdAndGameId(Long activityId, Long userId, Integer gameId);
}
