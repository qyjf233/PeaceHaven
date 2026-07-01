package com.potato.peacehaven.repository;

import com.potato.peacehaven.entity.BuildingContestVote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface BuildingContestVoteRepository extends JpaRepository<BuildingContestVote, Long> {

    /** 检查用户是否已对某作品投票 */
    boolean existsByWorkIdAndUserId(Long workId, Long userId);
}
