package com.potato.peacehaven.repository;

import com.potato.peacehaven.entity.NancyDraftTeam;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface NancyDraftTeamRepository extends JpaRepository<NancyDraftTeam, Long> {

    List<NancyDraftTeam> findByActivityIdOrderByTeamSideAsc(Long activityId);

    Optional<NancyDraftTeam> findByActivityIdAndTeamSide(Long activityId, String teamSide);

    long countByActivityId(Long activityId);
}
